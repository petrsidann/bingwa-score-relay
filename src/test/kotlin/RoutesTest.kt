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
        val authLimiter = AuthRateLimiter(maxTokens = 20)
        testApplication {
            application { relayModule(s, "secret", authLimiter = authLimiter) }
            val c = createClient { }
            val up = c.post("/api/auth/sign-up") { header("X-Server-Key", "secret"); contentType(ContentType.Application.Json); setBody("""{"phone":"+2341"}""") }
            assertEquals(HttpStatusCode.OK, up.status)
            val body = up.bodyAsText()
            assertTrue(body.contains("accessToken") && body.contains("refreshToken") && body.contains("expiresAt") && body.contains("userId") && body.contains("phone"))
            val rt = Regex("\"refreshToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            val uid1 = Regex("\"userId\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            val detail1Raw = c.get("/admin/api/users/$uid1") { header("X-Admin-Key", "secret") }
            val otp1 = Regex("\"otp\":\"([^\"]+)\"").find(detail1Raw.bodyAsText())!!.groupValues[1]
            val ver1 = c.post("/api/auth/verify-otp") { header("X-Server-Key", "secret"); contentType(ContentType.Application.Json); setBody("""{"phone":"+2341","code":"$otp1"}""") }
            assertEquals(HttpStatusCode.OK, ver1.status)
            val in_ = c.post("/api/auth/sign-in") { header("X-Server-Key", "secret"); contentType(ContentType.Application.Json); setBody("""{"phone":"+2341"}""") }
            assertEquals(HttpStatusCode.OK, in_.status)
            val otp = c.post("/api/auth/email-otp") { header("X-Server-Key", "secret"); contentType(ContentType.Application.Json); setBody("""{"phone":"+2342"}""") }
            val code = Regex("\"devCode\":\"([^\"]+)\"").find(otp.bodyAsText())!!.groupValues[1]
            val ver = c.post("/api/auth/verify-otp") { header("X-Server-Key", "secret"); contentType(ContentType.Application.Json); setBody("""{"phone":"+2342","code":"$code"}""") }
            assertEquals(HttpStatusCode.OK, ver.status)
            val bad = c.post("/api/auth/verify-otp") { header("X-Server-Key", "secret"); contentType(ContentType.Application.Json); setBody("""{"phone":"+2342","code":"000000"}""") }
            assertEquals(HttpStatusCode.Unauthorized, bad.status)
            val ref = c.post("/api/auth/refresh") { header("X-Server-Key", "secret"); contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$rt"}""") }
            assertEquals(HttpStatusCode.OK, ref.status)
            val refBad = c.post("/api/auth/refresh") { header("X-Server-Key", "secret"); contentType(ContentType.Application.Json); setBody("""{"refreshToken":"zz"}""") }
            assertEquals(HttpStatusCode.Unauthorized, refBad.status)
        }
    }

    @Test fun stage32OtpAndUpdates() = runBlocking {
        OtpManager.clear()
        val s = InMemoryStore()
        val authLimiter = AuthRateLimiter(maxTokens = 20)
        testApplication {
            application { relayModule(s, "secret", authLimiter = authLimiter) }
            val c = createClient { install(ClientCNC) { json(Codec.json) } }

            // 1) Updates endpoint contract check
            val updatesRes = c.get("/updates/latest.json")
            assertEquals(HttpStatusCode.OK, updatesRes.status)
            val updateInfo = updatesRes.body<UpdateInfo>()
            assertEquals(100, updateInfo.versionCode)
            assertEquals("1.0.0", updateInfo.versionName)
            assertEquals("https://example.com/app.apk", updateInfo.url)
            assertEquals("Initial release", updateInfo.notes)

            // 2) Sign-up creates status=pending_verification
            val phone = "+254712345678"
            val signupRes = c.post("/api/auth/signup") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$phone"}""")
            }
            assertEquals(HttpStatusCode.OK, signupRes.status)

            val users = s.users()
            val createdUser = users.first { it.phone == phone }
            assertEquals("pending_verification", createdUser.status)
            assertNull(createdUser.verifiedAt)

            // Unverified login => 403 VERIFICATION_REQUIRED
            val unverifiedLogin = c.post("/api/auth/sign-in") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$phone"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, unverifiedLogin.status)
            assertTrue(unverifiedLogin.bodyAsText().contains("VERIFICATION_REQUIRED"))

            // Resend cooldown check (within 60s)
            val resendCooldown = c.post("/api/auth/signup") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$phone"}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, resendCooldown.status)
            assertTrue(resendCooldown.bodyAsText().contains("resend_cooldown"))

            // Get sandbox OTP from Admin API
            val adminUserDetail = c.get("/admin/api/users/${createdUser.id}") { header("X-Admin-Key", "secret") }.body<UserDetail>()
            val activeOtp = adminUserDetail.otp
            assertNotNull(activeOtp)
            assertEquals(6, activeOtp.length)

            // 3) Verify with bad code decreases attempts left
            val badCodeRes = c.post("/api/auth/verify") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$phone","code":"000000"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, badCodeRes.status)
            assertTrue(badCodeRes.bodyAsText().contains("attempts_left"))

            // 4) Verify with valid code marks user verified
            val verifyRes = c.post("/api/auth/verify") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$phone","code":"$activeOtp"}""")
            }
            assertEquals(HttpStatusCode.OK, verifyRes.status)
            val tokens = verifyRes.body<Tokens>()
            assertEquals(phone, tokens.phone)

            val verifiedUser = s.users().first { it.phone == phone }
            assertEquals("active", verifiedUser.status)
            assertNotNull(verifiedUser.verifiedAt)

            // Verified user login succeeds
            val verifiedLogin = c.post("/api/auth/sign-in") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$phone"}""")
            }
            assertEquals(HttpStatusCode.OK, verifiedLogin.status)

            // 5) Test Lockout after 5 wrong codes on new phone
            val lockPhone = "+254799999999"
            c.post("/api/auth/signup") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$lockPhone"}""")
            }
            for (i in 1..4) {
                val badTry = c.post("/api/auth/verify") {
                    header("X-Server-Key", "secret")
                    contentType(ContentType.Application.Json)
                    setBody("""{"phone":"$lockPhone","code":"000000"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, badTry.status)
            }
            // 5th wrong code triggers 15min lockout
            val fifthTry = c.post("/api/auth/verify") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$lockPhone","code":"000000"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, fifthTry.status)
            assertTrue(fifthTry.bodyAsText().contains("account_locked"))

            // 6) OTP Expiry test
            val expirePhone = "+254788888888"
            c.post("/api/auth/signup") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$expirePhone"}""")
            }
            val expUser = s.users().first { it.phone == expirePhone }
            val expOtp = c.get("/admin/api/users/${expUser.id}") { header("X-Admin-Key", "secret") }.body<UserDetail>().otp!!
            // verify with simulated past time (+11 min)
            val expiredResult = OtpManager.verifyOtp(expirePhone, expOtp, nowMs = System.currentTimeMillis() + 11 * 60 * 1000)
            assertTrue(expiredResult is OtpVerifyResult.Expired)
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

    @Test fun mpesaSandboxAndIdempotentCallback() = runBlocking {
        val s = InMemoryStore()
        testApplication {
            application { relayModule(s, "secret") }
            val c = createClient { }

            // 1) Sandbox STK Push
            val stkRes = c.post("/api/payments/stk") {
                header("X-Server-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"+254700000000","offerId":"o1","userId":"u-mpesa"}""")
            }
            assertEquals(HttpStatusCode.OK, stkRes.status)
            val stkBody = stkRes.bodyAsText()
            assertTrue(stkBody.contains("\"ok\":true"))
            assertTrue(stkBody.contains("checkoutRequestId"))

            // Verify pass granted automatically in sandbox mode
            val passes = s.passes("f1")
            assertTrue(passes.any { it.userId == "u-mpesa" })

            // 2) Idempotent Callback check
            val txId = s.insertTransaction("u-idem", null, "o1", 5.0, "KES", "checkout-idem-1")
            val cb1 = c.post("/api/payments/callback") {
                contentType(ContentType.Application.Json)
                setBody("""{"checkoutRequestId":"checkout-idem-1","resultCode":0}""")
            }
            assertEquals(HttpStatusCode.OK, cb1.status)
            val initialPassesCount = s.passes("f1").count { it.userId == "u-idem" }
            assertEquals(1, initialPassesCount)

            // Second callback call with same CheckoutRequestID must NOT double grant
            val cb2 = c.post("/api/payments/callback") {
                contentType(ContentType.Application.Json)
                setBody("""{"checkoutRequestId":"checkout-idem-1","resultCode":0}""")
            }
            assertEquals(HttpStatusCode.OK, cb2.status)
            assertTrue(cb2.bodyAsText().contains("alreadyProcessed"))
            val duplicatePassesCount = s.passes("f1").count { it.userId == "u-idem" }
            assertEquals(1, duplicatePassesCount) // Remains 1 pass!
        }
    }

    @Test fun adminPaymentsReconciliationGate() {
        testApplication {
            application { relayModule(InMemoryStore(), "secret") }
            val c = createClient { }

            assertEquals(HttpStatusCode.Unauthorized, c.get("/admin/api/transactions").status)
            assertEquals(HttpStatusCode.Unauthorized, c.post("/admin/api/transactions/tx-1/confirm").status)

            assertEquals(HttpStatusCode.OK, c.get("/admin/api/transactions") { header("X-Admin-Key", "secret") }.status)
        }
    }

    @Test fun adminPortalV3DashboardAndAssets() {
        val s = InMemoryStore()
        runBlocking {
            s.signUp("+254711223344")
            s.createAgent("agent-v3", "Agent V3 Store", "+254700112233")
            val txId = s.insertTransaction("u-v3", "agent-v3", "o1", 500.0, "KES")
            s.setTransactionStatus(txId, "paid")
        }

        testApplication {
            application { relayModule(s, "secret") }
            val c = createClient { }

            // 1. GET /admin 200 html and CSP header present
            val adminRes = c.get("/admin") { header("X-Admin-Key", "secret") }
            assertEquals(HttpStatusCode.OK, adminRes.status)
            assertTrue(adminRes.contentType()?.match(ContentType.Text.Html) == true)
            val cspHeader = adminRes.headers["Content-Security-Policy"]
            assertNotNull(cspHeader)
            assertEquals("default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'", cspHeader)

            // 2. Asset routes app.css and app.js serve 200 OK with correct Content-Type
            val cssRes = c.get("/admin/assets/app.css")
            assertEquals(HttpStatusCode.OK, cssRes.status)
            assertTrue(cssRes.contentType()?.match(ContentType.Text.CSS) == true)

            val jsRes = c.get("/admin/assets/app.js")
            assertEquals(HttpStatusCode.OK, jsRes.status)
            assertTrue(jsRes.contentType()?.match(ContentType.Application.JavaScript) == true || jsRes.contentType()?.match(ContentType("text", "javascript")) == true)

            // 3. GET /admin/api/overview returns seeded data, sparkline, and activity feed
            val overviewRes = c.get("/admin/api/overview") { header("X-Admin-Key", "secret") }
            assertEquals(HttpStatusCode.OK, overviewRes.status)
            val overviewText = overviewRes.bodyAsText()
            assertTrue(overviewText.contains("totalUsers"))
            assertTrue(overviewText.contains("revenueThisWeekKsh"))
            assertTrue(overviewText.contains("sparkline7d"))
            assertTrue(overviewText.contains("activityFeed"))

            // 4. GET /admin/api/agents returns store list
            val agentsRes = c.get("/admin/api/agents") { header("X-Admin-Key", "secret") }
            assertEquals(HttpStatusCode.OK, agentsRes.status)
            assertTrue(agentsRes.bodyAsText().contains("agent-v3"))
        }
    }
}
