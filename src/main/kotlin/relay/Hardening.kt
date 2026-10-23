package relay

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/** CORS allowlist driven by CORS_ORIGINS (comma-separated). Empty/unset => no cross-origin allowed. */
fun Application.installCors() {
    install(CORS) {
        val origins = (env("CORS_ORIGINS") ?: "").split(',').map { it.trim() }.filter { it.isNotBlank() }
        origins.forEach { allowHost(it, schemes = listOf("http", "https")) }
        allowHeader("X-Server-Key"); allowHeader("X-Admin-Key"); allowHeader("X-User-Id")
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post)
    }
}

/** Request-ID logging & header response per request. */
fun Application.installRequestLogging() {
    val accessLog = org.slf4j.LoggerFactory.getLogger("relay.access")
    intercept(ApplicationCallPipeline.Plugins) {
        val reqId = call.request.headers["X-Request-ID"]
            ?: ("req-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8))
        call.response.header("X-Request-ID", reqId)
        accessLog.info("access [{}] {} {}", reqId, call.request.httpMethod.value, call.request.path())
    }
}

// Rate-limit stub for /api/auth/*: fixed window per remote host, RATE_LIMIT_AUTH_PER_MIN env (default 30).
// Stub = in-process only; a production limiter would use a distributed store.
class RateLimitStub(val perMinute: Int) {
    private val hits = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>>()
    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val q = hits.computeIfAbsent(key) { java.util.concurrent.ConcurrentLinkedDeque() }
        while (let { q.peekFirst() != null && now - q.peekFirst() > 60_000 }) q.pollFirst()
        if (q.size >= perMinute) return false
        q.addLast(now); return true
    }
}

fun Application.installAuthRateLimit(store: RateLimitStub) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path.startsWith("/api/auth/") || path.startsWith("/api/payments/")) {
            val key = call.request.local.remoteHost
            if (!store.allow(key)) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited"))
                finish()
            }
        }
    }
}
