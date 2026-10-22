package relay

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.*
import io.ktor.util.pipeline.PipelineContext
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.sse.*
import io.ktor.http.*
import io.ktor.websocket.readText

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.time.Duration.Companion.seconds
import io.ktor.websocket.Frame as WsFrame


val bus = MutableSharedFlow<String>(extraBufferCapacity = 256)

fun Application.relayModule(store: Store, serverKey: String?) {
    install(ContentNegotiation) { json(Codec.json) }
    install(WebSockets) {
        pingPeriod = 12.seconds
        timeout = 12.seconds
    }
    install(SSE)
    installCors()
    installRequestLogging()
    val authRateLimiter = RateLimitStub((env("RATE_LIMIT_AUTH_PER_MIN") ?: "30").toInt())
    installAuthRateLimit(authRateLimiter)
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ApiError(cause.message ?: "internal_error"))
        }
    }

    routing {
        get("/healthz") { AdminState.count("GET /healthz"); call.respondText("ok") }

        route("/api") {
            if (serverKey != null) {
                // Route-level interceptor guards every /api/* call; finish() halts the pipeline.
                intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                    val key = call.request.headers["X-Server-Key"]
                    if (key != serverKey) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("bad_server_key"))
                        finish()
                    }
                }
            }
            relayApi(store)
        }
        relaySocketAndSse()
        adminRoutes(store, serverKey)
        metricsRoutes(serverKey)
    }
}

fun Route.relayApi(store: Store) {
    get("/offers/verified") { AdminState.count("GET /api/offers/verified"); call.respond(store.offers().filter { it.verified }) }
    get("/features") { AdminState.count("GET /api/features"); call.respond(store.features()) }
    get("/features/{code}") {
        AdminState.count("GET /api/features/{code}")
        val f = store.featureByCode(call.parameters["code"] ?: "")
        if (f == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found")) else call.respond(f)
    }
    get("/features/{id}/passes") {
        AdminState.count("GET /api/features/{id}/passes")
        val id = call.parameters["id"] ?: ""
        val passes = store.passes(id).ifEmpty { store.featureByCode(id)?.let { store.passes(it.id) } ?: emptyList() }
        call.respond(passes)
    }
    post("/messages/send") {
        AdminState.count("POST /api/messages/send")
        val req = call.receive<SendRequest>()
        val from = call.request.headers["X-User-Id"] ?: "anon"
        val m = store.sendMessage(from, req.to, req.body)
        AdminState.addMessage(m.copy(from = from, atMs = System.currentTimeMillis()))
        bus.tryEmit(encodeFrame("message.sent", Codec.json.encodeToJsonElement(Message.serializer(), m)))
        call.respond(SendResult(true, m.id))
    }
    post("/auth/sign-up") {
        AdminState.count("POST /api/auth/sign-up")
        val r = call.receive<OtpRequest>()
        call.respond(store.signUp(r.phone))
    }
    post("/auth/sign-in") {
        AdminState.count("POST /api/auth/sign-in")
        val r = call.receive<OtpRequest>()
        val t = store.signIn(r.phone)
        if (t == null) call.respond(HttpStatusCode.Unauthorized, ApiError("unknown_user")) else call.respond(t)
    }
    post("/auth/email-otp") {
        AdminState.count("POST /api/auth/email-otp")
        val r = call.receive<OtpRequest>()
        val code = store.requestOtp(r.phone)
        call.respond(OtpSent(true, code))
    }
    post("/auth/verify-otp") {
        AdminState.count("POST /api/auth/verify-otp")
        val r = call.receive<OtpVerify>()
        val t = store.verifyOtp(r.phone, r.code)
        if (t == null) call.respond(HttpStatusCode.Unauthorized, ApiError("bad_otp")) else call.respond(t)
    }
    post("/auth/refresh") {
        AdminState.count("POST /api/auth/refresh")
        val r = call.receive<RefreshRequest>()
        val t = store.refresh(r.refreshToken)
        if (t == null) call.respond(HttpStatusCode.Unauthorized, ApiError("bad_refresh")) else call.respond(t)
    }
}

fun Route.relaySocketAndSse() {
    webSocket("/socket") {
        WsRegistry.connected()
        try {
            for (f in incoming) {
                if (f is WsFrame.Text) {
                    val dec = decodeFrame(f.readText()) ?: continue
                    when (val inbound = dec.second) {
                        is PingIn -> send(WsFrame.Text(encodeFrame("pong")))
                        is AppStateSet -> {
                            bus.tryEmit(encodeFrame("app_state.set", Codec.json.encodeToJsonElement(AppStateMsg.serializer(), inbound.msg)))
                            send(WsFrame.Text(encodeFrame("app_state.set_ack")))
                        }
                        is TaskAckIn -> {
                            bus.tryEmit(encodeFrame("task.ack", Codec.json.encodeToJsonElement(TaskAck.serializer(), inbound.ack)))
                            send(WsFrame.Text(encodeFrame("task.ack_ok")))
                        }
                        is AirtimeBalanceGet -> {
                            val ack = AirtimeBalanceAck(inbound.req.phone, 1500.0)
                            send(WsFrame.Text(encodeFrame("task.airtime_balance.get_ack", Codec.json.encodeToJsonElement(AirtimeBalanceAck.serializer(), ack))))
                        }
                        is Unknown -> { /* ignore unknown events */ }
                    }
                }
            }
        } finally {
            WsRegistry.disconnected()
        }
    }
    sse("/events") {
        send(io.ktor.sse.ServerSentEvent(data = encodeFrame("hello")))
        bus.collect { send(io.ktor.sse.ServerSentEvent(data = it)) }
    }
}
