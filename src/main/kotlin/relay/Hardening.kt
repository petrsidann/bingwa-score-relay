package relay

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/** CORS allowlist driven by CORS_ORIGINS (comma-separated). Empty/unset => no cross-origin allowed. */
fun Application.installCors() {
    val originsStr = env("CORS_ORIGINS") ?: ""
    val origins = originsStr.split(',').map { it.trim() }.filter { it.isNotBlank() }
    if (origins.isNotEmpty()) {
        install(CORS) {
            origins.forEach { allowHost(it, schemes = listOf("http", "https")) }
            allowHeader("X-Server-Key"); allowHeader("X-Admin-Key"); allowHeader("X-User-Id"); allowHeader("X-Phone")
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post)
        }
    }
}

/** Security Headers: HSTS, X-Content-Type-Options, X-Frame-Options, CSP on /admin. Masking secrets & phones in logs. */
fun Application.installSecurityHeaders() {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")

        if (path.startsWith("/admin")) {
            call.response.header(
                "Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'"
            )
        }
    }
}

/** Request-ID logging & header response per request. Masking phone numbers & redacting secrets. */
fun Application.installRequestLogging() {
    val accessLog = org.slf4j.LoggerFactory.getLogger("relay.access")
    intercept(ApplicationCallPipeline.Plugins) {
        val reqId = call.request.headers["X-Request-ID"]
            ?: ("req-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8))
        call.response.header("X-Request-ID", reqId)

        val uri = sanitizeLogString(call.request.uri)
        val maskedPhoneHeader = call.request.headers["X-Phone"]?.let { maskPhone(it) } ?: ""
        val phoneInfo = if (maskedPhoneHeader.isNotBlank()) " phone=$maskedPhoneHeader" else ""

        accessLog.info("access [{}] {} {}{}", reqId, call.request.httpMethod.value, uri, phoneInfo)
    }
}

fun maskPhone(phone: String): String {
    val clean = phone.trim()
    if (clean.length < 7) return "***"
    val prefixLen = if (clean.startsWith("+")) 4 else 3
    val suffixLen = 4
    if (clean.length <= prefixLen + suffixLen) return clean.take(2) + "***" + clean.takeLast(2)
    val prefix = clean.substring(0, prefixLen)
    val suffix = clean.substring(clean.length - suffixLen)
    return "$prefix***$suffix"
}

fun sanitizeLogString(input: String): String {
    var result = input
    // Redact query parameter values for sensitive keys
    val sensitiveKeys = listOf("key", "secret", "token", "password", "passkey", "X-Server-Key", "X-Admin-Key", "accessToken", "refreshToken")
    for (k in sensitiveKeys) {
        val regex = Regex("(?i)($k=)[^&]+", RegexOption.IGNORE_CASE)
        result = regex.replace(result, "$1[REDACTED]")
    }
    return result
}

// Advanced Rate Limiting & Security Filters: Token bucket, lockout, global IP limiter & Headless bot UA filter.
class AuthRateLimiter(
    val maxTokens: Int = 5,
    val refillIntervalMs: Long = 60_000L
) {
    private class Bucket(var tokens: Double, var lastRefill: Long)
    private class LockoutState(var failCount: Int = 0, var lockedUntil: Long = 0L)

    private val buckets = java.util.concurrent.ConcurrentHashMap<String, Bucket>()
    private val lockouts = java.util.concurrent.ConcurrentHashMap<String, LockoutState>()

    fun checkAndConsume(key: String): Pair<Boolean, Long> {
        val now = System.currentTimeMillis()

        // 1. Lockout check
        val lockout = lockouts[key]
        if (lockout != null && lockout.lockedUntil > now) {
            val retryAfterSeconds = (lockout.lockedUntil - now + 999) / 1000
            return Pair(false, retryAfterSeconds.coerceAtLeast(1L))
        }

        // 2. Token bucket check
        val bucket = buckets.computeIfAbsent(key) { Bucket(maxTokens.toDouble(), now) }
        synchronized(bucket) {
            val elapsed = now - bucket.lastRefill
            if (elapsed > 0) {
                val addedTokens = elapsed.toDouble() * maxTokens.toDouble() / refillIntervalMs.toDouble()
                bucket.tokens = (bucket.tokens + addedTokens).coerceAtMost(maxTokens.toDouble())
                bucket.lastRefill = now
            }

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return Pair(true, 0L)
            } else {
                val needed = 1.0 - bucket.tokens
                val waitMs = (needed * refillIntervalMs.toDouble() / maxTokens.toDouble()).toLong()
                val retryAfterSeconds = (waitMs + 999) / 1000
                return Pair(false, retryAfterSeconds.coerceAtLeast(1L))
            }
        }
    }

    fun recordFailure(key: String) {
        val now = System.currentTimeMillis()
        val lockout = lockouts.computeIfAbsent(key) { LockoutState() }
        synchronized(lockout) {
            lockout.failCount++
            if (lockout.failCount >= 5) {
                val lockoutMultiplier = 1 shl (lockout.failCount - 5).coerceAtMost(10)
                val durationMs = (60_000L * lockoutMultiplier).coerceAtMost(15 * 60_000L) // Max 15 minutes
                lockout.lockedUntil = now + durationMs
            }
        }
    }

    fun recordSuccess(key: String) {
        lockouts.remove(key)
    }

    fun reset() {
        buckets.clear()
        lockouts.clear()
    }
}

class GlobalIpRateLimiter(
    val maxRequests: Int = 60,
    val windowMs: Long = 60_000L
) {
    private val hits = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>>()

    fun allow(ip: String): Pair<Boolean, Long> {
        val now = System.currentTimeMillis()
        val q = hits.computeIfAbsent(ip) { java.util.concurrent.ConcurrentLinkedDeque() }
        while (q.peekFirst() != null && now - q.peekFirst()!! > windowMs) {
            q.pollFirst()
        }
        if (q.size >= maxRequests) {
            val oldest = q.peekFirst() ?: now
            val retryAfterSeconds = ((windowMs - (now - oldest)) + 999) / 1000
            return Pair(false, retryAfterSeconds.coerceAtLeast(1L))
        }
        q.addLast(now)
        return Pair(true, 0L)
    }

    fun reset() {
        hits.clear()
    }
}

val globalAuthRateLimiter = AuthRateLimiter()
val globalIpLimiter = GlobalIpRateLimiter()

fun Application.installAuthRateLimit(authLimiter: AuthRateLimiter = globalAuthRateLimiter, ipLimiter: GlobalIpRateLimiter = globalIpLimiter) {
    val headlessBotRegex = Regex("(?i).*(HeadlessChrome|PhantomJS|Puppeteer|Playwright|Selenium).*")

    intercept(ApplicationCallPipeline.Plugins) {
        val ua = call.request.headers[HttpHeaders.UserAgent] ?: ""
        if (ua.isNotBlank() && headlessBotRegex.matches(ua)) {
            call.respond(HttpStatusCode.Forbidden, ApiError("bot_detected"))
            finish()
            return@intercept
        }

        val ip = call.request.local.remoteHost
        val globalCheck = ipLimiter.allow(ip)
        if (!globalCheck.first) {
            call.response.header(HttpHeaders.RetryAfter, globalCheck.second.toString())
            call.respond(HttpStatusCode.TooManyRequests, ApiError("global_rate_limit_exceeded"))
            finish()
            return@intercept
        }

        val path = call.request.path()
        if (path.startsWith("/api/auth/")) {
            val phone = call.request.headers["X-Phone"] ?: call.request.queryParameters["phone"] ?: ""
            val key = "$ip:$phone"
            val authCheck = authLimiter.checkAndConsume(key)
            if (!authCheck.first) {
                call.response.header(HttpHeaders.RetryAfter, authCheck.second.toString())
                call.respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited"))
                finish()
                return@intercept
            }
        }
    }
}
