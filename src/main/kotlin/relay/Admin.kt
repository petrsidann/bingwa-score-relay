package relay

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Shared in-process state for the admin console (metrics, audit, flags, logs). */
object AdminState {
    val startedAtMs: Long = System.currentTimeMillis()
    val routeCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    val wsConnections = java.util.concurrent.atomic.AtomicInteger(0)
    val messageLog = java.util.concurrent.ConcurrentLinkedDeque<Message>() // last 200 /api/messages/send
    val auditLog = java.util.concurrent.ConcurrentLinkedDeque<AuditEntry>() // last 500 admin actions
    @Volatile var flagsJson: String = """{"live_scores":true,"airtime_rewards":true,"push_enabled":false}"""

    fun count(route: String) {
        routeCounts.computeIfAbsent(route) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
    }

    fun addMessage(m: Message) {
        messageLog.addFirst(m)
        while (messageLog.size > 200) messageLog.pollLast()
    }

    fun addAudit(action: String, detail: String) {
        auditLog.addFirst(AuditEntry(java.time.Instant.now().toString(), action, detail))
        while (auditLog.size > 500) auditLog.pollLast()
    }

    /** Messages recorded in the last 60 seconds (for the live chart). */
    fun messagesLastMinute(): Int {
        val cutoff = System.currentTimeMillis() - 60_000
        return messageLog.count { (it.atMs ?: 0L) >= cutoff }
    }

    fun uptimeSeconds(): Long = (System.currentTimeMillis() - startedAtMs) / 1000
}

@kotlinx.serialization.Serializable
data class AuditEntry(val at: String, val action: String, val detail: String)

/** Live WebSocket connection counter used by the dashboard. */
object WsRegistry {
    fun connected() = AdminState.wsConnections.incrementAndGet()
    fun disconnected() = AdminState.wsConnections.decrementAndGet()
    fun count(): Int = AdminState.wsConnections.get()
}

/** Shared admin gate: ADMIN_KEY (falls back to RELAY_SERVER_KEY) via X-Admin-Key header or ?key=. */
fun io.ktor.server.routing.Route.gateAdmin(serverKey: String?) {
    val adminKey = env("ADMIN_KEY") ?: serverKey
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        val provided = call.request.headers["X-Admin-Key"]
            ?: call.request.queryParameters["key"]
            ?: call.request.headers["X-Server-Key"]
        if (adminKey.isNullOrBlank() || provided != adminKey) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("bad_admin_key"))
            finish()
        }
    }
}

fun Application.adminRoutes(store: Store, serverKey: String?) {
    // SPA assets: serve from the classpath (works in tests + fat jar); WEB_DIR overrides with a
    // filesystem directory for live-editing during development.
    val webDir = env("WEB_DIR")
    routing {
        if (webDir != null && java.io.File(webDir).isDirectory) {
            staticFiles("/admin/assets", java.io.File(webDir))
        } else {
            staticResources("/admin/assets", "web")
        }

        route("/admin") {
            // Gate everything else (HTML shell + JSON API) behind ADMIN_KEY.
            gateAdmin(serverKey)

            // ---- SPA shell (static assets under /admin/assets/*) ----
            get { call.respondText(AdminPortal.shell, ContentType.Text.Html) }

            // ---- JSON API for a future web UI ----
            route("/api") {
                get("/users") { AdminState.count("GET /admin/api/users"); call.respond(store.users()) }
                get("/metrics/history") {
                    AdminState.count("GET /admin/api/metrics/history")
                    MetricsRing.catchUp()
                    call.respond(MetricsRing.snapshot())
                }
                post("/users/{id}/status") {
                    AdminState.count("POST /admin/api/users/{id}/status")
                    val id = call.parameters["id"] ?: ""
                    val req = call.receive<UserStatusRequest>()
                    val ok = store.setUserStatus(id, req.status)
                    AdminState.addAudit("user.status", "$id -> ${req.status}")
                    if (ok) call.respond(OkResult(true)) else call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                }
                get("/offers") { AdminState.count("GET /admin/api/offers"); call.respond(store.offers()) }
                post("/agents") {
                    AdminState.count("POST /admin/api/agents")
                    val req = try { call.receive<AgentCreateRequest>() } catch (_: Exception) { AgentCreateRequest() }
                    val slug = req.slug?.ifBlank { null }
                        ?: req.name?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.ifBlank { null }
                        ?: "agent-${System.currentTimeMillis()}"
                    val name = req.name?.ifBlank { null } ?: slug
                    val phone = req.ownerPhone ?: req.phone
                    store.createAgent(slug, name, phone)
                    call.respond(AgentResponse(slug, name, active = true))
                }
                post("/offers") {
                    AdminState.count("POST /admin/api/offers")
                    val r = call.receive<OfferCreate>()
                    val o = store.createOffer(r.title, r.price, r.verified) ?: return@post call.respond(HttpStatusCode.InternalServerError, ApiError("create_failed"))
                    AdminState.addAudit("offer.create", o.id + " " + o.title)
                    call.respond(o)
                }
                post("/offers/{id}/disable") {
                    AdminState.count("POST /admin/api/offers/{id}/disable")
                    val id = call.parameters["id"] ?: ""
                    val ok = store.setOfferVerified(id, false)
                    AdminState.addAudit("offer.disable", id)
                    if (ok) call.respond(OkResult(true)) else call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                }
                post("/offers/{id}/verify") {
                    AdminState.count("POST /admin/api/offers/{id}/verify")
                    val id = call.parameters["id"] ?: ""
                    val verified = call.request.queryParameters["value"]?.toBooleanStrictOrNull() ?: true
                    val ok = store.setOfferVerified(id, verified)
                    AdminState.addAudit("offer.verify", "$id -> $verified")
                    if (ok) call.respond(OkResult(true)) else call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                }
                get("/flags") {
                    AdminState.count("GET /admin/api/flags")
                    call.respondText(AdminState.flagsJson, ContentType.Application.Json)
                }
                post("/flags") {
                    AdminState.count("POST /admin/api/flags")
                    val body = call.receiveText()
                    try {
                        Json.parseToJsonElement(body).jsonObject // validate it is a JSON object
                        AdminState.flagsJson = body
                        AdminState.addAudit("flags.update", "${body.length} bytes")
                        call.respond(OkResult(true))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_json"))
                    }
                }
                get("/transactions") {
                    AdminState.count("GET /admin/api/transactions")
                    val status = call.request.queryParameters["status"]
                    call.respond(store.transactions(status))
                }
                post("/transactions/{id}/confirm") {
                    AdminState.count("POST /admin/api/transactions/{id}/confirm")
                    val id = call.parameters["id"] ?: ""
                    val tx = store.findTransactionById(id) ?: store.findTransactionByCheckoutId(id)
                    if (tx == null) {
                        call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                    } else {
                        store.setTransactionStatus(tx.id, "paid")
                        store.grantPass(tx.userId ?: "anon", tx.offerId)
                        call.respond(OkResult(true))
                    }
                }
                post("/transactions/{id}/fail") {
                    AdminState.count("POST /admin/api/transactions/{id}/fail")
                    val id = call.parameters["id"] ?: ""
                    val ok = store.setTransactionStatus(id, "failed")
                    if (ok) call.respond(OkResult(true)) else call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                }
                get("/messages") {
                    AdminState.count("GET /admin/api/messages")
                    call.respond(AdminState.messageLog.toList())
                }
                get("/audit") {
                    AdminState.count("GET /admin/api/audit")
                    call.respond(AdminState.auditLog.toList())
                }
                get("/metrics") {
                    AdminState.count("GET /admin/api/metrics")
                    call.respond(MetricsSnapshot(
                        uptimeSeconds = AdminState.uptimeSeconds(),
                        wsConnections = WsRegistry.count(),
                        routes = AdminState.routeCounts.mapValues { it.value.get() }
                    ))
                }
                get("/export/users.csv") {
                    AdminState.count("GET /admin/api/export/users.csv")
                    val users = store.users()
                    val csv = buildString {
                        appendLine("id,phone,created_at,status")
                        users.forEach { appendLine("${csvField(it.id)},${csvField(it.phone)},${csvField(it.createdAt)},${csvField(it.status)}") }
                    }
                    AdminState.addAudit("export.users.csv", "${users.size} rows")
                    call.respondText(csv, ContentType.Text.CSV)
                }
            }
        }
    }
}

@Serializable
data class MetricsSnapshot(val uptimeSeconds: Long, val wsConnections: Int, val routes: Map<String, Long>)

private fun csvField(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
