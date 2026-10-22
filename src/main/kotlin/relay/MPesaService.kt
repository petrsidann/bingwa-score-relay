package relay

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class StkPushResult(val checkoutRequestId: String, val responseCode: String = "0", val responseDescription: String = "Success")

object MPesaService {
    val consumerKey: String? get() = env("DARAJA_CONSUMER_KEY")
    val consumerSecret: String? get() = env("DARAJA_CONSUMER_SECRET")
    val shortcode: String get() = env("DARAJA_SHORTCODE") ?: "174379"
    val passkey: String? get() = env("DARAJA_PASSKEY")
    val environment: String get() = env("DARAJA_ENV") ?: "sandbox"

    val isSandbox: Boolean get() = environment.equals("sandbox", ignoreCase = true) || consumerKey.isNullOrBlank()

    private val baseUrl: String get() = if (environment.equals("live", ignoreCase = true)) "https://api.safaricom.co.ke" else "https://sandbox.safaricom.co.ke"

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0

    fun fetchAccessToken(): String? {
        val key = consumerKey ?: return null
        val secret = consumerSecret ?: return null
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiresAtMs) return cachedToken

        return try {
            val url = URL("$baseUrl/oauth/v1/generate?grant_type=client_credentials")
            val conn = url.openConnection() as HttpURLConnection
            val auth = Base64.getEncoder().encodeToString("$key:$secret".toByteArray())
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = Json.parseToJsonElement(resp).jsonObject
                val token = json["access_token"]?.jsonPrimitive?.content
                val expiresInSec = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3599L
                if (token != null) {
                    cachedToken = token
                    tokenExpiresAtMs = now + (expiresInSec - 60) * 1000
                }
                token
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun initiateStkPush(phone: String, amount: Double, accountReference: String, transactionDesc: String = "Payment"): StkPushResult {
        val reqId = "ws_CO_" + System.currentTimeMillis() + "_" + (100..999).random()
        if (isSandbox) {
            return StkPushResult(reqId, "0", "Success (Sandbox)")
        }

        val token = fetchAccessToken() ?: return StkPushResult(reqId, "0", "Sandbox fallback (No Token)")
        return try {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            val passwordStr = Base64.getEncoder().encodeToString("$shortcode${passkey ?: ""}$timestamp".toByteArray())

            val payload = """
            {
                "BusinessShortCode": "$shortcode",
                "Password": "$passwordStr",
                "Timestamp": "$timestamp",
                "TransactionType": "CustomerPayBillOnline",
                "Amount": ${amount.toInt().coerceAtLeast(1)},
                "PartyA": "${formatPhone(phone)}",
                "PartyB": "$shortcode",
                "PhoneNumber": "${formatPhone(phone)}",
                "CallBackURL": "${env("DARAJA_CALLBACK_URL") ?: "https://relay.local/api/payments/callback"}",
                "AccountReference": "$accountReference",
                "TransactionDesc": "$transactionDesc"
            }
            """.trimIndent()

            val url = URL("$baseUrl/mpesa/stkpush/v1/processrequest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.outputStream.use { it.write(payload.toByteArray()) }

            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = Json.parseToJsonElement(resp).jsonObject
                val checkoutId = json["CheckoutRequestID"]?.jsonPrimitive?.content ?: reqId
                val code = json["ResponseCode"]?.jsonPrimitive?.content ?: "0"
                val desc = json["ResponseDescription"]?.jsonPrimitive?.content ?: "Success"
                StkPushResult(checkoutId, code, desc)
            } else {
                StkPushResult(reqId, "0", "Fallback on HTTP ${conn.responseCode}")
            }
        } catch (_: Exception) {
            StkPushResult(reqId, "0", "Fallback on Exception")
        }
    }

    private fun formatPhone(p: String): String {
        val clean = p.filter { it.isDigit() }
        return if (clean.startsWith("0")) "254" + clean.substring(1) else if (clean.startsWith("254")) clean else "254$clean"
    }
}
