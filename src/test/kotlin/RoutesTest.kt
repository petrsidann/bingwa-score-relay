package relay

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCNC
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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

    @Test fun emailOtpTypedResponse() {
        withServer { c ->
            val r = c.post("/api/auth/email-otp") { contentType(ContentType.Application.Json); setBody("""{"phone":"+2349"}""") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"ok\":true"))
            assertTrue(r.bodyAsText().contains("\"devCode\""))
        }
    }

    @Test fun errorResponsesAreTypedApiError() {
        withServer { c ->
            val r = c.get("/api/features/NOPE")
            assertEquals(HttpStatusCode.NotFound, r.status)
            assertEquals("""{"error":"not_found"}""", r.bodyAsText())
        }
    }

    @Test fun adminGatedByAdminKey() {
        testApplication {
            application { relayModule(InMemoryStore(), "secret") }
            val c = createClient { }
            assertEquals(HttpStatusCode.Unauthorized, c.get("/admin").status)
            assertEquals(HttpStatusCode.Unauthorized, c.get("/admin/api/users").status)
            assertEquals(HttpStatusCode.OK, c.get("/admin/api/users") { header("X-Admin-Key", "secret") }.status)
        }
    }

    @Test fun adminDashboardAndActions() {
        val s = InMemoryStore()
        testApplication {
            application { relayModule(s, "secret") }
            val c = createClient { install(ClientCNC) { json(Codec.json) } }
            // register a user first — decode sign-up response with kotlinx.serialization into the typed DTO
            val signUpRaw = c.post("/api/auth/sign-up") {
                header("X-Server-Key", "secret") // /api/* is guarded when RELAY_SERVER_KEY is set
                contentType(ContentType.Application.Json); setBody("""{"phone":"+23477"}""")
            }
            assertEquals(HttpStatusCode.OK, signUpRaw.status) // assert sign-up status before decoding
            val signUp: Tokens = signUpRaw.body<Tokens>()
            assertTrue(signUp.userId.isNotBlank() && signUp.phone == "+23477")
            // HTML dashboard
            val html = c.get("/admin") { header("X-Admin-Key", "secret") }
            assertEquals(HttpStatusCode.OK, html.status)
            assertTrue(html.contentType()?.match(ContentType.Text.Html) == true)
            assertTrue(html.bodyAsText().contains("Bingwa Relay"))
            // users list + kill-switch round-trip (typed decode instead of regex scraping)
            val users: List<AdminUser> = c.get("/admin/api/users") { header("X-Admin-Key", "secret") }.body()
            val uid = users.first { it.phone == "+23477" }.id
            val kill = c.post("/admin/api/users/$uid/status") {
                header("X-Admin-Key", "secret"); contentType(ContentType.Application.Json)
                setBody("""{"status":"disabled"}""")
            }
            assertEquals(HttpStatusCode.OK, kill.status)
            assertEquals("disabled", c.get("/admin/api/users") { header("X-Admin-Key", "secret") }.body<List<AdminUser>>().first { it.id == uid }.status)
            // reactivate to prove full round-trip
            assertEquals(HttpStatusCode.OK, c.post("/admin/api/users/$uid/status") {
                header("X-Admin-Key", "secret"); contentType(ContentType.Application.Json)
                setBody("""{"status":"active"}""")
            }.status)
            assertEquals("active", c.get("/admin/api/users") { header("X-Admin-Key", "secret") }.body<List<AdminUser>>().first { it.id == uid }.status)
            // offers create + disable
            val created: Offer = c.post("/admin/api/offers") {
                header("X-Admin-Key", "secret"); contentType(ContentType.Application.Json)
                setBody("""{"title":"Test Offer","price":3.5,"verified":true}""")
            }.body<Offer>()
            assertEquals(HttpStatusCode.OK, c.post("/admin/api/offers/${created.id}/disable") { header("X-Admin-Key", "secret") }.status)
            // feature flags editor
            assertEquals(HttpStatusCode.OK, c.post("/admin/api/flags") {
                header("X-Admin-Key", "secret"); contentType(ContentType.Application.Json)
                setBody("""{"live_scores":false}""")
            }.status)
            assertEquals(HttpStatusCode.BadRequest, c.post("/admin/api/flags") {
                header("X-Admin-Key", "secret"); contentType(ContentType.Application.Json)
                setBody("""not json""")
            }.status)
            // message log after /api/messages/send (X-Server-Key needed for guarded /api/*)
            c.post("/api/messages/send") { header("X-Server-Key", "secret"); contentType(ContentType.Application.Json); setBody("""{"to":"+1","body":"logged"}""") }
            assertTrue(c.get("/admin/api/messages") { header("X-Admin-Key", "secret") }.bodyAsText().contains("logged"))
            // metrics + ws count + audit
            val m = c.get("/admin/api/metrics") { header("X-Admin-Key", "secret") }.bodyAsText()
            assertTrue(m.contains("uptimeSeconds") && m.contains("wsConnections") && m.contains("routes"))
            assertTrue(c.get("/admin/api/audit") { header("X-Admin-Key", "secret") }.bodyAsText().contains("user.status"))
            // CSV export
            val csv = c.get("/admin/api/export/users.csv") { header("X-Admin-Key", "secret") }
            assertEquals(HttpStatusCode.OK, csv.status)
            assertTrue(csv.bodyAsText().startsWith("id,phone,created_at,status"))
            assertTrue(csv.bodyAsText().contains("+23477"))
        }
    }

    @Test fun agentAdminGateRejectsMissingKey() {
        testApplication {
            application { relayModule(InMemoryStore(), "secret") }
            val c = createClient { }
            val noKey = c.post("/api/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Agent One"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, noKey.status)

            val withKey = c.post("/api/agents") {
                header("X-Admin-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"agent-one","name":"Agent One"}""")
            }
            assertEquals(HttpStatusCode.OK, withKey.status)
            assertTrue(withKey.bodyAsText().contains("agent-one"))
        }
    }

    @Test fun storefrontRendersOnlyDbPricesAndCreatesPendingTx() = runBlocking {
        val s = InMemoryStore()
        s.createAgent("agent-alpha", "Agent Alpha", "+2348000000000")
        val offer = s.createOffer("Special Bundle", 99.99, true)

        testApplication {
            application { relayModule(s, null) }
            val c = createClient { }

            // 1) Storefront renders DB offer title and price (99.99)
            val storeHtml = c.get("/store/agent-alpha")
            assertEquals(HttpStatusCode.OK, storeHtml.status)
            val htmlBody = storeHtml.bodyAsText()
            assertTrue(htmlBody.contains("Special Bundle"))
            assertTrue(htmlBody.contains("99.99"))

            // 2) JSON endpoint for agent's verified offers
            val storeOffers = c.get("/store/agent-alpha/offers")
            assertEquals(HttpStatusCode.OK, storeOffers.status)
            assertTrue(storeOffers.bodyAsText().contains("99.99"))

            // 3) Nonexistent agent store returns 404
            assertEquals(HttpStatusCode.NotFound, c.get("/store/unknown-agent").status)

            // 4) Buy action creates pending transaction
            val buyRes = c.post("/store/agent-alpha/buy") {
                header("Accept", "application/json")
                contentType(ContentType.Application.Json)
                setBody("""{"offerId":"${offer.id}"}""")
            }
            assertEquals(HttpStatusCode.OK, buyRes.status)
            val buyBody = buyRes.bodyAsText()
            assertTrue(buyBody.contains("\"ok\":true"))
            assertTrue(buyBody.contains("pending"))

            val txs = s.transactions()
            assertEquals(1, txs.size)
            assertEquals("pending", txs[0].status)
            assertEquals("agent-alpha", txs[0].agentSlug)
            assertEquals(99.99, txs[0].amount)

            // 5) Confirmation state in HTML storefront
            val confirmHtml = c.get("/store/agent-alpha?tx=${txs[0].id}&bought=${offer.id}").bodyAsText()
            assertTrue(confirmHtml.contains("Transaction pending confirmation"))
        }
    }
}
