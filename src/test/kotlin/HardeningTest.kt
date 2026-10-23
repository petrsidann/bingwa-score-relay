package relay

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCNC
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlin.test.*

/** testApplication integration tests for the hardened auth flow + admin gate. */
class HardeningTest {

    @Test fun authFlowTypedEndToEnd() = runBlockingUnit {
        val s = InMemoryStore()
        testApplication {
            application { relayModule(s, null) }
            val c = createClient { install(ClientCNC) { json(Codec.json) } }
            // sign-up -> typed Tokens
            val up = c.post("/api/auth/sign-up") {
                contentType(ContentType.Application.Json); setBody("""{"phone":"+23490"}""")
            }
            assertEquals(HttpStatusCode.OK, up.status)
            val tokens: Tokens = up.body()
            assertEquals("+23490", tokens.phone)
            assertTrue(tokens.accessToken.isNotBlank() && tokens.refreshToken.isNotBlank())
            // email-otp -> verify-otp -> typed Tokens
            val otp = c.post("/api/auth/email-otp") {
                contentType(ContentType.Application.Json); setBody("""{"phone":"+23491"}""")
            }
            assertEquals(HttpStatusCode.OK, otp.status)
            val sent: OtpSent = otp.body()
            assertTrue(sent.ok && sent.devCode.length == 6)
            val ver = c.post("/api/auth/verify-otp") {
                contentType(ContentType.Application.Json); setBody("""{"phone":"+23491","code":"${sent.devCode}"}""")
            }
            assertEquals(HttpStatusCode.OK, ver.status)
            val verified: Tokens = ver.body()
            assertEquals("+23491", verified.phone)
            // refresh rotates tokens
            val ref = c.post("/api/auth/refresh") {
                contentType(ContentType.Application.Json); setBody("""{"refreshToken":"${tokens.refreshToken}"}""")
            }
            assertEquals(HttpStatusCode.OK, ref.status)
            val refreshed: Tokens = ref.body()
            assertNotEquals(tokens.refreshToken, refreshed.refreshToken)
            assertEquals(tokens.userId, refreshed.userId)
        }
    }

    @Test fun adminGateHeaderAndQueryParam() {
        testApplication {
            application { relayModule(InMemoryStore(), "server-secret") }
            val c = createClient { }
            // no key -> 401 on both HTML console and JSON API
            assertEquals(HttpStatusCode.Unauthorized, c.get("/admin").status)
            assertEquals(HttpStatusCode.Unauthorized, c.get("/admin/api/metrics").status)
            // wrong key -> 401
            assertEquals(HttpStatusCode.Unauthorized, c.get("/admin") { header("X-Admin-Key", "nope") }.status)
            // X-Admin-Key header works (falls back to RELAY_SERVER_KEY when ADMIN_KEY unset)
            assertEquals(HttpStatusCode.OK, c.get("/admin") { header("X-Admin-Key", "server-secret") }.status)
            // ?key= query param works
            assertEquals(HttpStatusCode.OK, c.get("/admin?key=server-secret").status)
            assertEquals(HttpStatusCode.OK, c.get("/admin/api/users?key=server-secret").status)
        }
    }

    @Test fun rateLimitStubAllowsThenBlocks() {
        val rl = RateLimitStub(perMinute = 3)
        repeat(3) { assertTrue(rl.allow("1.2.3.4"), "request $it should be allowed") }
        assertFalse(rl.allow("1.2.3.4"), "4th request in window should be blocked")
        assertTrue(rl.allow("5.6.7.8"), "different client unaffected")
    }

    @Test fun requestIdHeaderPresent() {
        testApplication {
            application { relayModule(InMemoryStore(), null) }
            val c = createClient { }
            val res = c.get("/healthz")
            assertEquals(HttpStatusCode.OK, res.status)
            assertNotNull(res.headers["X-Request-ID"])
            assertTrue(res.headers["X-Request-ID"]!!.startsWith("req-"))
        }
    }

    private fun runBlockingUnit(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
