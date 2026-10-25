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
        val rl = AuthRateLimiter(maxTokens = 3)
        repeat(3) { assertTrue(rl.checkAndConsume("1.2.3.4").first, "request $it should be allowed") }
        assertFalse(rl.checkAndConsume("1.2.3.4").first, "4th request in window should be blocked")
        assertTrue(rl.checkAndConsume("5.6.7.8").first, "different client unaffected")
    }

    @Test fun sqliPayloadsReturn400or401Never500() {
        testApplication {
            application { relayModule(InMemoryStore(), "secret-key") }
            val c = createClient { }

            val sqliPayloads = listOf(
                "' OR 1=1--",
                "\"; DROP TABLE users;--",
                "' UNION SELECT 1, 'hacked', 3--",
                "admin' --"
            )

            for (payload in sqliPayloads) {
                // Auth sign-in expects phone lookup
                val authRes = c.post("/api/auth/sign-in") {
                    header("X-Server-Key", "secret-key")
                    contentType(ContentType.Application.Json)
                    setBody("""{"phone":"$payload"}""")
                }
                assertNotEquals(HttpStatusCode.InternalServerError, authRes.status, "SQLi in sign-in returned 500!")
                assertTrue(authRes.status == HttpStatusCode.Unauthorized || authRes.status == HttpStatusCode.BadRequest, "Expected 400/401 for SQLi in sign-in")

                // Store slug
                val encodedPayload = java.net.URLEncoder.encode(payload, "UTF-8")
                val storeRes = c.get("/store/$encodedPayload")
                assertNotEquals(HttpStatusCode.InternalServerError, storeRes.status, "SQLi in store slug returned 500!")
                assertEquals(HttpStatusCode.NotFound, storeRes.status, "Expected 404 Not Found for non-existent agent slug")
            }
        }
    }

    @Test fun headlessBotFilterBlocksBotUserAgents() {
        testApplication {
            application { relayModule(InMemoryStore(), null) }
            val c = createClient { }

            val botUas = listOf(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/90.0.4430.212 Safari/537.36",
                "Puppeteer 10.0.0",
                "Playwright/1.12.0",
                "Selenium/4.0.0",
                "PhantomJS/2.1.1"
            )

            for (botUa in botUas) {
                val res = c.get("/healthz") {
                    header(HttpHeaders.UserAgent, botUa)
                }
                assertEquals(HttpStatusCode.Forbidden, res.status, "Headless bot UA $botUa was not blocked!")
                assertTrue(res.bodyAsText().contains("bot_detected"))
            }

            // Normal UA should pass
            val normalRes = c.get("/healthz") {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
            }
            assertEquals(HttpStatusCode.OK, normalRes.status)
        }
    }

    @Test fun securityHeadersPresent() {
        testApplication {
            application { relayModule(InMemoryStore(), "secret-key") }
            val c = createClient { }

            val healthRes = c.get("/healthz")
            assertEquals("max-age=31536000; includeSubDomains", healthRes.headers["Strict-Transport-Security"])
            assertEquals("nosniff", healthRes.headers["X-Content-Type-Options"])
            assertEquals("DENY", healthRes.headers["X-Frame-Options"])

            val adminRes = c.get("/admin") { header("X-Admin-Key", "secret-key") }
            assertEquals(HttpStatusCode.OK, adminRes.status)
            assertNotNull(adminRes.headers["Content-Security-Policy"])
            assertTrue(adminRes.headers["Content-Security-Policy"]!!.contains("default-src 'self'"))
        }
    }

    @Test fun darajaCallbackVerificationHonestAndForged() = kotlinx.coroutines.runBlocking {
        val store = InMemoryStore()
        testApplication {
            application { relayModule(store, "secret-key") }
            val c = createClient { }

            // Honest transaction
            val honestTxId = store.insertTransaction("u-honest", null, "o1", 5.0, "KES", "checkout-honest-1")
            val honestCb = c.post("/api/payments/callback") {
                contentType(ContentType.Application.Json)
                setBody("""{"checkoutRequestId":"checkout-honest-1","resultCode":0}""")
            }
            assertEquals(HttpStatusCode.OK, honestCb.status)
            assertEquals("paid", store.findTransactionById(honestTxId)?.status)
            assertTrue(store.passesForUser("u-honest").isNotEmpty())

            // Forged transaction (fake checkout id prefix triggers sandbox forged detection)
            val forgedTxId = store.insertTransaction("u-forged", null, "o1", 5.0, "KES", "forged-checkout-99")
            val forgedCb = c.post("/api/payments/callback") {
                contentType(ContentType.Application.Json)
                setBody("""{"checkoutRequestId":"forged-checkout-99","resultCode":0}""")
            }
            assertEquals(HttpStatusCode.BadRequest, forgedCb.status)
            assertEquals("failed", store.findTransactionById(forgedTxId)?.status)
            assertTrue(store.passesForUser("u-forged").isEmpty(), "Forged callback must NEVER grant pass!")
        }
    }

    @Test fun adminUserIntelligenceAndSubscribeEndpoints() = kotlinx.coroutines.runBlocking {
        val store = InMemoryStore()
        val user = store.signUp("+254711223344")
        store.grantPass(user.userId, "o1")
        store.insertTransaction(user.userId, null, "o1", 5.0, "KES", "checkout-sub-1")

        testApplication {
            application { relayModule(store, "secret-key") }
            val c = createClient { install(ClientCNC) { json(Codec.json) } }

            // GET /admin/api/users/{id}
            val detailRes = c.get("/admin/api/users/${user.userId}") {
                header("X-Admin-Key", "secret-key")
            }
            assertEquals(HttpStatusCode.OK, detailRes.status)
            val detail: UserDetail = detailRes.body()
            assertEquals(user.userId, detail.user.id)
            assertEquals("+254711223344", detail.user.phone)
            assertEquals("Pro Pass", detail.activePlan)
            assertEquals(1, detail.passes.size)
            assertEquals(1, detail.transactions.size)

            // POST /api/subscribe
            val subRes = c.post("/api/subscribe") {
                header("X-Server-Key", "secret-key")
                header("X-User-Id", user.userId)
                contentType(ContentType.Application.Json)
                setBody("""{"offerId":"o1"}""")
            }
            assertEquals(HttpStatusCode.OK, subRes.status)
            val sub: SubscribeResponse = subRes.body()
            assertTrue(sub.ok)
            assertTrue(sub.checkoutRequestId.isNotBlank())

            // GET /api/payments/status/{id}
            val statusRes = c.get("/api/payments/status/${sub.transactionId}") {
                header("X-Server-Key", "secret-key")
            }
            assertEquals(HttpStatusCode.OK, statusRes.status)
            val statusTx: Transaction = statusRes.body()
            assertEquals(sub.transactionId, statusTx.id)
        }
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
