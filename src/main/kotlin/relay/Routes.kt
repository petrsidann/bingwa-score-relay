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
import kotlinx.serialization.json.*

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
                // Route-level interceptor guards every /api/* call (except public webhook callbacks); finish() halts the pipeline.
                intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                    if (!call.request.path().endsWith("/payments/callback")) {
                        val key = call.request.headers["X-Server-Key"]
                            ?: call.request.headers["X-Admin-Key"]
                            ?: call.request.queryParameters["key"]
                        if (key != serverKey) {
                            call.respond(HttpStatusCode.Unauthorized, ApiError("bad_server_key"))
                            finish()
                        }
                    }
                }
            }
            relayApi(store)
        }
        relaySocketAndSse()
        storefrontRoutes(store, serverKey)
        paymentsRoutes(store)
    }
    adminRoutes(store, serverKey)
    metricsRoutes(serverKey)
}

fun Route.paymentsRoutes(store: Store) {
    route("/api/payments") {
        post("/stk") {
            AdminState.count("POST /api/payments/stk")
            val bodyText = call.receiveText()
            val req = if (bodyText.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
            } else {
                try { Codec.json.decodeFromString<StkRequest>(bodyText) } catch (_: Exception) { return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request")) }
            }
            val offer = store.offers().firstOrNull { it.id == req.offerId }
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("offer_not_found"))

            val stkResult = MPesaService.initiateStkPush(req.phone, offer.price, offer.id)
            val txId = store.insertTransaction(
                userId = req.userId,
                agentSlug = req.agentSlug,
                offerId = offer.id,
                amount = offer.price,
                currency = "KES",
                checkoutRequestId = stkResult.checkoutRequestId
            )

            val status = if (MPesaService.isSandbox) {
                // Sandbox mode: simulate success and self-invoke callback logic idempotently
                store.setTransactionStatus(txId, "paid")
                store.grantPass(req.userId ?: "anon", offer.id)
                "paid"
            } else {
                "pending"
            }

            call.respond(StkResponse(ok = true, transactionId = txId, checkoutRequestId = stkResult.checkoutRequestId, status = status))
        }

        post("/callback") {
            AdminState.count("POST /api/payments/callback")
            val bodyText = call.receiveText()
            var checkoutId: String? = null
            var resultCode = 0

            if (bodyText.isNotBlank()) {
                try {
                    val root = Json.parseToJsonElement(bodyText).jsonObject
                    val stkCallback = root["Body"]?.jsonObject?.get("stkCallback")?.jsonObject
                    if (stkCallback != null) {
                        checkoutId = stkCallback["CheckoutRequestID"]?.jsonPrimitive?.content
                        resultCode = stkCallback["ResultCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    } else {
                        checkoutId = root["checkoutRequestId"]?.jsonPrimitive?.content ?: root["CheckoutRequestID"]?.jsonPrimitive?.content
                        resultCode = root["resultCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    }
                } catch (_: Exception) {
                    try {
                        val params = parseQueryString(bodyText)
                        checkoutId = params["checkoutRequestId"] ?: params["CheckoutRequestID"]
                        resultCode = params["resultCode"]?.toIntOrNull() ?: 0
                    } catch (_: Exception) { }
                }
            }

            if (checkoutId.isNullOrBlank()) {
                checkoutId = call.request.queryParameters["checkoutRequestId"] ?: call.request.queryParameters["CheckoutRequestID"]
            }

            if (checkoutId.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("missing_checkout_id"))
            }

            val tx = store.findTransactionByCheckoutId(checkoutId)
                ?: store.findTransactionById(checkoutId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("transaction_not_found"))

            // Idempotency: if already processed (paid or failed), do not grant pass again
            if (tx.status == "paid" || tx.status == "failed") {
                return@post call.respond(CallbackResponse(0, "Accepted (already processed)", alreadyProcessed = true))
            }

            if (resultCode == 0) {
                store.setTransactionStatus(tx.id, "paid")
                store.grantPass(tx.userId ?: "anon", tx.offerId)
            } else {
                store.setTransactionStatus(tx.id, "failed")
            }

            call.respond(CallbackResponse(0, "Accepted"))
        }
    }
}

fun Route.storefrontRoutes(store: Store, serverKey: String?) {
    route("/api/agents") {
        gateAdmin(serverKey)
        post {
            AdminState.count("POST /api/agents")
            val bodyText = call.receiveText()
            val req = if (bodyText.isBlank()) AgentCreateRequest() else try { Codec.json.decodeFromString<AgentCreateRequest>(bodyText) } catch (_: Exception) { AgentCreateRequest() }
            val slug = req.slug?.ifBlank { null }
                ?: req.name?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.ifBlank { null }
                ?: "agent-${System.currentTimeMillis()}"
            val name = req.name?.ifBlank { null } ?: slug
            val phone = req.ownerPhone ?: req.phone
            store.createAgent(slug, name, phone)
            call.respond(AgentResponse(slug, name, active = true))
        }
    }

    route("/store/{slug}") {
        get {
            AdminState.count("GET /store/{slug}")
            val slug = call.parameters["slug"] ?: ""
            if (!store.agentExists(slug)) {
                call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                return@get
            }
            val offers = store.offersForAgent(slug)
            val tx = call.request.queryParameters["tx"]
            val bought = call.request.queryParameters["bought"]
            call.respondText(renderStorefront(slug, offers, bought, tx), ContentType.Text.Html)
        }

        get("/offers") {
            AdminState.count("GET /store/{slug}/offers")
            val slug = call.parameters["slug"] ?: ""
            if (!store.agentExists(slug)) {
                call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                return@get
            }
            call.respond(store.offersForAgent(slug))
        }

        post("/buy") {
            AdminState.count("POST /store/{slug}/buy")
            val slug = call.parameters["slug"] ?: ""
            if (!store.agentExists(slug)) {
                call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                return@post
            }
            val bodyText = call.receiveText()
            val offerId = try {
                call.request.queryParameters["offerId"]
                    ?: Codec.json.decodeFromString<BuyRequest>(bodyText).offerId
            } catch (_: Exception) {
                try {
                    parseQueryString(bodyText)["offerId"]
                } catch (_: Exception) { null }
            } ?: ""

            val offer = store.offersForAgent(slug).firstOrNull { it.id == offerId }
                ?: store.offers().firstOrNull { it.id == offerId }
            if (offer == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("offer_not_found"))
                return@post
            }
            val txId = store.insertTransaction(userId = null, agentSlug = slug, offerId = offer.id, amount = offer.price)
            val isJsonReq = call.request.headers["Accept"]?.contains("application/json") == true ||
                            call.request.contentType().match(ContentType.Application.Json)
            if (isJsonReq) {
                call.respond(BuyResponse(ok = true, transactionId = txId, status = "pending", amount = offer.price))
            } else {
                call.respondRedirect("/store/$slug?tx=$txId&bought=${offer.id}")
            }
        }
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
