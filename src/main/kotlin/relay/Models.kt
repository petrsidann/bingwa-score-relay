package relay

import kotlinx.serialization.Serializable

@Serializable data class Offer(val id: String, val title: String, val price: Double, val verified: Boolean = true, val agentSlug: String? = null)
@Serializable data class Feature(val id: String, val code: String, val name: String, val description: String = "")
@Serializable data class Pass(val id: String, val featureId: String, val userId: String, val active: Boolean = true)
@Serializable data class Message(val id: String, val from: String, val to: String, val body: String, val atMs: Long? = null)
@Serializable data class SendRequest(val to: String, val body: String)
@Serializable data class SendResult(val ok: Boolean, val messageId: String)
@Serializable data class Tokens(val userId: String, val phone: String, val accessToken: String, val refreshToken: String, val expiresAt: String)
@Serializable data class OtpRequest(val phone: String)
@Serializable data class OtpVerify(val phone: String, val code: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class OkResult(val ok: Boolean)
@Serializable data class OtpSent(val ok: Boolean, val devCode: String)
@Serializable data class ApiError(val error: String)
@Serializable data class AdminUser(val id: String, val phone: String, val createdAt: String, val status: String)
@Serializable data class UserStatusRequest(val status: String)
@Serializable data class OfferCreate(val title: String, val price: Double, val verified: Boolean = true)
@Serializable data class Agent(val slug: String, val name: String, val ownerPhone: String? = null, val active: Boolean = true)
@Serializable data class Transaction(
    val id: String,
    val userId: String? = null,
    val agentSlug: String? = null,
    val offerId: String,
    val amount: Double,
    val currency: String = "NGN",
    val status: String = "pending",
    val checkoutRequestId: String? = null,
    val createdAt: String? = null
)
@Serializable data class AgentCreateRequest(val slug: String? = null, val name: String? = null, val ownerPhone: String? = null, val phone: String? = null)
@Serializable data class BuyRequest(val offerId: String, val userId: String? = null)
@Serializable data class AgentResponse(val slug: String, val name: String, val active: Boolean = true)
@Serializable data class BuyResponse(val ok: Boolean = true, val transactionId: String, val status: String = "pending", val amount: Double)
@Serializable data class StkRequest(val phone: String, val offerId: String, val userId: String? = null, val agentSlug: String? = null)
@Serializable data class StkResponse(val ok: Boolean, val transactionId: String, val checkoutRequestId: String, val status: String = "pending")
@Serializable data class CallbackResponse(val ResultCode: Int = 0, val ResultDesc: String = "Accepted", val alreadyProcessed: Boolean? = null)

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
    suspend fun users(): List<AdminUser> = emptyList()
    suspend fun setUserStatus(id: String, status: String): Boolean = false
    suspend fun createOffer(title: String, price: Double, verified: Boolean): Offer? = null
    suspend fun setOfferVerified(id: String, verified: Boolean): Boolean = false
    suspend fun createAgent(slug: String, name: String, ownerPhone: String? = null): Boolean = false
    suspend fun agentExists(slug: String): Boolean = false
    suspend fun offersForAgent(slug: String): List<Offer> = emptyList()
    suspend fun insertTransaction(userId: String?, agentSlug: String?, offerId: String, amount: Double, currency: String = "NGN", checkoutRequestId: String? = null): String = ""
    suspend fun setTransactionStatus(id: String, status: String): Boolean = false
    suspend fun findTransactionByCheckoutId(checkoutRequestId: String): Transaction? = null
    suspend fun findTransactionById(id: String): Transaction? = null
    suspend fun grantPass(userId: String, offerIdOrFeatureId: String): Pass? = null
    suspend fun transactions(status: String? = null): List<Transaction> = emptyList()
    fun close() {}
}

class InMemoryStore : Store {
    @Volatile private var offersList = mutableListOf(
        Offer("o1", "Starter Pack", 5.0),
        Offer("o2", "Pro Season", 20.0)
    )
    private val agents = mutableMapOf<String, Agent>()
    private val transactionsList = mutableListOf<Transaction>()
    private val features = listOf(
        Feature("f1", "LIVE_SCORES", "Live Scores", "Real-time match scores"),
        Feature("f2", "AIRTIME", "Airtime Rewards", "Earn airtime")
    )
    private val passesMap = mutableMapOf(
        "f1" to mutableListOf(Pass("p1", "f1", "u1")),
        "f2" to mutableListOf(Pass("p2", "f2", "u1"))
    )
    private val users = mutableMapOf<String, Tokens>()
    private val userMeta = mutableMapOf<String, AdminUser>()
    private val otps = mutableMapOf<String, String>()
    private var seq = 0
    private fun next(prefix: String) = "$prefix-${++seq}"

    override suspend fun offers() = offersList.toList()
    override suspend fun features() = features
    override suspend fun featureByCode(code: String) = features.firstOrNull { it.code == code }
    override suspend fun passes(featureId: String) = passesMap[featureId]?.toList() ?: emptyList()
    override suspend fun sendMessage(from: String, to: String, body: String): Message {
        val id = next("m"); return Message(id, from, to, body)
    }
    override suspend fun signIn(phone: String): Tokens? = users["u:$phone"]
    override suspend fun signUp(phone: String): Tokens {
        val t = Tokens(next("u"), phone, next("at"), next("rt"), java.time.Instant.now().plusSeconds(3600).toString())
        users["u:$phone"] = t; users["rt:${t.refreshToken}"] = t
        userMeta[t.userId] = AdminUser(t.userId, phone, java.time.Instant.now().toString(), "active")
        return t
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

    override suspend fun users(): List<AdminUser> = userMeta.values.sortedBy { it.createdAt }

    override suspend fun setUserStatus(id: String, status: String): Boolean {
        val u = userMeta[id] ?: return false
        userMeta[id] = u.copy(status = status); return true
    }

    override suspend fun createOffer(title: String, price: Double, verified: Boolean): Offer {
        val o = Offer(next("o"), title, price, verified)
        offersList = (offersList + o).toMutableList()
        return o
    }

    override suspend fun setOfferVerified(id: String, verified: Boolean): Boolean {
        val o = offersList.firstOrNull { it.id == id } ?: return false
        offersList = offersList.map { if (it.id == id) it.copy(verified = verified) else it }.toMutableList()
        return true
    }

    override suspend fun createAgent(slug: String, name: String, ownerPhone: String?): Boolean {
        agents[slug] = Agent(slug, name, ownerPhone, active = true)
        return true
    }

    override suspend fun agentExists(slug: String): Boolean =
        agents[slug]?.active == true

    override suspend fun offersForAgent(slug: String): List<Offer> {
        val specific = offersList.filter { it.agentSlug == slug && it.verified }
        return if (specific.isNotEmpty()) specific else offersList.filter { (it.agentSlug == slug || it.agentSlug == null) && it.verified }
    }

    override suspend fun insertTransaction(userId: String?, agentSlug: String?, offerId: String, amount: Double, currency: String, checkoutRequestId: String?): String {
        val id = next("tx")
        val now = java.time.Instant.now().toString()
        transactionsList.add(Transaction(id, userId, agentSlug, offerId, amount, currency, status = "pending", checkoutRequestId = checkoutRequestId, createdAt = now))
        return id
    }

    override suspend fun setTransactionStatus(id: String, status: String): Boolean {
        val idx = transactionsList.indexOfFirst { it.id == id || it.checkoutRequestId == id }
        if (idx < 0) return false
        transactionsList[idx] = transactionsList[idx].copy(status = status)
        return true
    }

    override suspend fun findTransactionByCheckoutId(checkoutRequestId: String): Transaction? =
        transactionsList.firstOrNull { it.checkoutRequestId == checkoutRequestId || it.id == checkoutRequestId }

    override suspend fun findTransactionById(id: String): Transaction? =
        transactionsList.firstOrNull { it.id == id || it.checkoutRequestId == id }

    override suspend fun grantPass(userId: String, offerIdOrFeatureId: String): Pass {
        val passId = next("p")
        val featureId = features.firstOrNull { it.id == offerIdOrFeatureId || it.code == offerIdOrFeatureId }?.id ?: "f1"
        val pass = Pass(passId, featureId, userId, active = true)
        val list = passesMap.getOrPut(featureId) { mutableListOf() }
        list.add(pass)
        return pass
    }

    override suspend fun transactions(status: String?): List<Transaction> =
        if (status.isNullOrBlank()) transactionsList.toList() else transactionsList.filter { it.status.equals(status, ignoreCase = true) }
}
