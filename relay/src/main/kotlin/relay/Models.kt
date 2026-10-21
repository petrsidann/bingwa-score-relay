package relay

import kotlinx.serialization.Serializable

@Serializable data class Offer(val id: String, val title: String, val price: Double, val verified: Boolean = true)
@Serializable data class Feature(val id: String, val code: String, val name: String, val description: String = "")
@Serializable data class Pass(val id: String, val featureId: String, val userId: String, val active: Boolean = true)
@Serializable data class Message(val id: String, val from: String, val to: String, val body: String)
@Serializable data class SendRequest(val to: String, val body: String)
@Serializable data class SendResult(val ok: Boolean, val messageId: String)
@Serializable data class Tokens(val userId: String, val phone: String, val accessToken: String, val refreshToken: String, val expiresAt: String)
@Serializable data class OtpRequest(val phone: String)
@Serializable data class OtpVerify(val phone: String, val code: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class OkResult(val ok: Boolean)

interface Store {
    suspend fun offers(): List<Offer>
    suspend fun features(): List<Feature>
    suspend fun featureByCode(code: String): Feature?
    suspend fun passes(featureId: String): List<Pass>
    suspend fun sendMessage(from: String, to: String, body: String): Message
    suspend fun signIn(phone: String): Tokens?
    suspend fun signUp(phone: String): Tokens
    suspend fun requestOtp(phone: String): String // returns otp code (dev)
    suspend fun verifyOtp(phone: String, code: String): Tokens?
    suspend fun refresh(refreshToken: String): Tokens?
    fun close() {}
}

class InMemoryStore : Store {
    private val offers = listOf(
        Offer("o1", "Starter Pack", 5.0),
        Offer("o2", "Pro Season", 20.0)
    )
    private val features = listOf(
        Feature("f1", "LIVE_SCORES", "Live Scores", "Real-time match scores"),
        Feature("f2", "AIRTIME", "Airtime Rewards", "Earn airtime")
    )
    private val passes = mapOf(
        "f1" to listOf(Pass("p1", "f1", "u1")),
        "f2" to listOf(Pass("p2", "f2", "u1"))
    )
    private val users = mutableMapOf<String, Tokens>()
    private val otps = mutableMapOf<String, String>()
    private var seq = 0
    private fun next(prefix: String) = "$prefix-${++seq}"

    override suspend fun offers() = offers
    override suspend fun features() = features
    override suspend fun featureByCode(code: String) = features.firstOrNull { it.code == code }
    override suspend fun passes(featureId: String) = passes[featureId] ?: emptyList()
    override suspend fun sendMessage(from: String, to: String, body: String): Message {
        val id = next("m"); return Message(id, from, to, body)
    }
    override suspend fun signIn(phone: String): Tokens? = users["u:$phone"]
    override suspend fun signUp(phone: String): Tokens {
        val t = Tokens(next("u"), phone, next("at"), next("rt"), java.time.Instant.now().plusSeconds(3600).toString())
        users["u:$phone"] = t; users["rt:${t.refreshToken}"] = t; return t
    }
    override suspend fun requestOtp(phone: String): String {
        val code = "%06d".format((100000..999999).random()); otps[phone] = code; return code
    }
    override suspend fun verifyOtp(phone: String, code: String): Tokens? =
        if (otps[phone] == code) users["u:$phone"] ?: signUp(phone).also { otps.remove(phone) } else null
    override suspend fun refresh(refreshToken: String): Tokens? {
        val old = users["rt:$refreshToken"] ?: return null
        val t = old.copy(accessToken = next("at"), refreshToken = next("rt"))
        users["u:${t.phone}"] = t; users["rt:${t.refreshToken}"] = t; return t
    }
}
