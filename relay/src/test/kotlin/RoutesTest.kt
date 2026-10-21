package relay

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RoutesTest {
    private val store = InMemoryStore()

    private fun withServer(key: String? = null, block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { relayModule(store, key) }
        block(createClient { })
    }

    @Test fun healthz() {
        withServer { c -> assertEquals("ok", c.get("/healthz").bodyAsText()) }
    }

    @Test fun offersVerified() {
        withServer { c ->
            val r = c.get("/api/offers/verified")
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("Starter Pack"))
        }
    }

    @Test fun featuresList() {
        withServer { c ->
            val r = c.get("/api/features")
            assertTrue(r.bodyAsText().contains("LIVE_SCORES"))
        }
    }

    @Test fun featureByCodeAnd404() {
        withServer { c ->
            assertEquals(HttpStatusCode.OK, c.get("/api/features/LIVE_SCORES").status)
            assertEquals(HttpStatusCode.NotFound, c.get("/api/features/NOPE").status)
        }
    }

    @Test fun passesByIdAndCode() {
        withServer { c ->
            assertTrue(c.get("/api/features/f1/passes").bodyAsText().contains("p1"))
            assertTrue(c.get("/api/features/AIRTIME/passes").bodyAsText().contains("p2"))
        }
    }

    @Test fun messageSend() {
        withServer { c ->
            val r = c.post("/api/messages/send") {
                contentType(ContentType.Application.Json)
                setBody("""{"to":"+1","body":"hi"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"ok\":true"))
        }
    }

    @Test fun authFlow() = runBlocking {
        val s = InMemoryStore()
        testApplication {
            application { relayModule(s, null) }
            val c = createClient { }
            val up = c.post("/api/auth/sign-up") { contentType(ContentType.Application.Json); setBody("""{"phone":"+2341"}""") }
            assertEquals(HttpStatusCode.OK, up.status)
            val body = up.bodyAsText()
            assertTrue(body.contains("accessToken") && body.contains("refreshToken") && body.contains("expiresAt") && body.contains("userId") && body.contains("phone"))
            val rt = Regex("\"refreshToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            val in_ = c.post("/api/auth/sign-in") { contentType(ContentType.Application.Json); setBody("""{"phone":"+2341"}""") }
            assertEquals(HttpStatusCode.OK, in_.status)
            val otp = c.post("/api/auth/email-otp") { contentType(ContentType.Application.Json); setBody("""{"phone":"+2342"}""") }
            val code = Regex("\"devCode\":\"([^\"]+)\"").find(otp.bodyAsText())!!.groupValues[1]
            val ver = c.post("/api/auth/verify-otp") { contentType(ContentType.Application.Json); setBody("""{"phone":"+2342","code":"$code"}""") }
            assertEquals(HttpStatusCode.OK, ver.status)
            val bad = c.post("/api/auth/verify-otp") { contentType(ContentType.Application.Json); setBody("""{"phone":"+2342","code":"000000"}""") }
            assertEquals(HttpStatusCode.Unauthorized, bad.status)
            val ref = c.post("/api/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$rt"}""") }
            assertEquals(HttpStatusCode.OK, ref.status)
            val refBad = c.post("/api/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"zz"}""") }
            assertEquals(HttpStatusCode.Unauthorized, refBad.status)
        }
    }

    @Test fun serverKeyMiddleware() {
        testApplication {
            application { relayModule(InMemoryStore(), "secret") }
            val c = createClient { }
            assertEquals(HttpStatusCode.Unauthorized, c.get("/api/features").status)
            assertEquals(HttpStatusCode.OK, c.get("/api/features") { header("X-Server-Key", "secret") }.status)
            assertEquals("ok", c.get("/healthz").bodyAsText()) // healthz unguarded
        }
    }
}
