package relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

/**
 * Relay 13 repository seam: full Store implementation over Postgres.
 * On first connection it applies db/schema.sql (versioned + idempotent — safe every boot).
 * Only constructed by StoreFactory when DB_URL is set; tests use applySchemaAtBoot=false
 * to avoid connecting.
 */
class PostgresStore(
    private val url: String,
    private val applySchemaAtBoot: Boolean = true
) : Store {
    private var conn: Connection? = null

    /** Statements from schema.sql, comments stripped, split on ';'. */
    internal fun schemaStatements(): List<String> {
        val raw = javaClass.getResourceAsStream("/db/schema.sql")
            ?.readBytes()?.decodeToString()
            ?: error("db/schema.sql missing from classpath")
        return raw.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun c(): Connection {
        conn?.takeIf { !it.isClosed }?.let { return it }
        val n = java.sql.DriverManager.getConnection(url)
        conn = n
        if (applySchemaAtBoot) {
            n.autoCommit = false
            n.createStatement().use { s ->
                for (st in schemaStatements()) s.execute(st)
            }
            n.commit()
            n.autoCommit = true
        }
        return n
    }

    override suspend fun offers() = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT * FROM offers").executeQuery().let { rs ->
            buildList { while (rs.next()) add(Offer(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getBoolean(4))) }
        }
    }

    override suspend fun features() = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT * FROM features").executeQuery().let { rs ->
            buildList { while (rs.next()) add(Feature(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))) }
        }
    }

    override suspend fun featureByCode(code: String) = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT * FROM features WHERE code=?").apply { setString(1, code) }.executeQuery().let { rs ->
            if (rs.next()) Feature(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) else null
        }
    }

    override suspend fun passes(featureId: String) = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT * FROM passes WHERE feature_id=?").apply { setString(1, featureId) }.executeQuery().let { rs ->
            buildList { while (rs.next()) add(Pass(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))) }
        }
    }

    override suspend fun sendMessage(from: String, to: String, body: String) = withContext(Dispatchers.IO) {
        val id = "m-" + System.nanoTime()
        c().prepareStatement("INSERT INTO messages VALUES(?,?,?,?)").apply {
            setString(1, id); setString(2, from); setString(3, to); setString(4, body)
        }.executeUpdate()
        Message(id, from, to, body)
    }

    override suspend fun signIn(phone: String) = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT * FROM users WHERE phone=?").apply { setString(1, phone) }.executeQuery().let { rs ->
            if (rs.next()) Tokens(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) else null
        }
    }

    override suspend fun signUp(phone: String) = withContext(Dispatchers.IO) {
        val t = Tokens("u-" + System.nanoTime(), phone, "at-" + System.nanoTime(), "rt-" + System.nanoTime(),
            java.time.Instant.now().plusSeconds(3600).toString())
        c().prepareStatement("INSERT INTO users(user_id,phone,access_token,refresh_token,expires_at) VALUES(?,?,?,?,?)").apply {
            setString(1, t.userId); setString(2, t.phone); setString(3, t.accessToken); setString(4, t.refreshToken); setString(5, t.expiresAt)
        }.executeUpdate()
        t
    }

    override suspend fun requestOtp(phone: String) = withContext(Dispatchers.IO) {
        val code = "%06d".format((100000..999999).random())
        c().prepareStatement("INSERT INTO otps VALUES(?,?) ON CONFLICT(phone) DO UPDATE SET code=EXCLUDED.code").apply {
            setString(1, phone); setString(2, code)
        }.executeUpdate()
        code
    }

    override suspend fun verifyOtp(phone: String, code: String) = withContext(Dispatchers.IO) {
        val ok = c().prepareStatement("SELECT 1 FROM otps WHERE phone=? AND code=?").apply {
            setString(1, phone); setString(2, code)
        }.executeQuery().next()
        if (!ok) null else signIn(phone) ?: signUp(phone).also {
            c().prepareStatement("DELETE FROM otps WHERE phone=?").apply { setString(1, phone) }.executeUpdate()
        }
    }

    override suspend fun refresh(refreshToken: String) = withContext(Dispatchers.IO) {
        val old = c().prepareStatement("SELECT * FROM users WHERE refresh_token=?").apply { setString(1, refreshToken) }
            .executeQuery().let { rs -> if (rs.next()) Tokens(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) else null }
        old?.let {
            val t = it.copy(accessToken = "at-" + System.nanoTime(), refreshToken = "rt-" + System.nanoTime())
            c().prepareStatement("UPDATE users SET access_token=?, refresh_token=? WHERE user_id=?").apply {
                setString(1, t.accessToken); setString(2, t.refreshToken); setString(3, t.userId)
            }.executeUpdate()
            t
        }
    }

    override suspend fun users(): List<AdminUser> = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT user_id, phone, created_at, status FROM users").executeQuery().let { rs ->
            buildList { while (rs.next()) add(AdminUser(rs.getString(1), rs.getString(2), rs.getString(3) ?: "", rs.getString(4) ?: "active")) }
        }
    }

    override suspend fun setUserStatus(id: String, status: String): Boolean = withContext(Dispatchers.IO) {
        c().prepareStatement("UPDATE users SET status=? WHERE user_id=?").apply {
            setString(1, status); setString(2, id)
        }.executeUpdate() > 0
    }

    override suspend fun createOffer(title: String, price: Double, verified: Boolean): Offer = withContext(Dispatchers.IO) {
        val o = Offer("o-" + System.nanoTime(), title, price, verified)
        c().prepareStatement("INSERT INTO offers(id,title,price,verified) VALUES(?,?,?,?)").apply {
            setString(1, o.id); setString(2, o.title); setDouble(3, o.price); setBoolean(4, o.verified)
        }.executeUpdate()
        o
    }

    // ---- helpers used by Relay 15 (store generator) & audit ----
    /** Insert a transaction row (status defaults to 'pending'). */
    override suspend fun insertTransaction(userId: String?, agentSlug: String?, offerId: String, amount: Double, currency: String, checkoutRequestId: String?): String =
        withContext(Dispatchers.IO) {
            val id = "tx-" + System.nanoTime()
            c().prepareStatement("INSERT INTO transactions(id,user_id,agent_slug,offer_id,amount,currency,status,checkout_request_id) VALUES(?,?,?,?,?,?, 'pending',?)").apply {
                setString(1, id); userId?.let { setString(2, it) } ?: setNull(2, java.sql.Types.NULL)
                agentSlug?.let { setString(3, it) } ?: setNull(3, java.sql.Types.NULL)
                setString(4, offerId); setDouble(5, amount); setString(6, currency)
                checkoutRequestId?.let { setString(7, it) } ?: setNull(7, java.sql.Types.NULL)
            }.executeUpdate()
            id
        }

    override suspend fun setTransactionStatus(id: String, status: String): Boolean = withContext(Dispatchers.IO) {
        c().prepareStatement("UPDATE transactions SET status=? WHERE id=? OR checkout_request_id=?").apply {
            setString(1, status); setString(2, id); setString(3, id)
        }.executeUpdate() > 0
    }

    override suspend fun findTransactionByCheckoutId(checkoutRequestId: String): Transaction? = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT id, user_id, agent_slug, offer_id, amount, currency, status, checkout_request_id, created_at FROM transactions WHERE checkout_request_id=? OR id=?").apply {
            setString(1, checkoutRequestId); setString(2, checkoutRequestId)
        }.executeQuery().let { rs ->
            if (rs.next()) Transaction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)) else null
        }
    }

    override suspend fun findTransactionById(id: String): Transaction? = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT id, user_id, agent_slug, offer_id, amount, currency, status, checkout_request_id, created_at FROM transactions WHERE id=? OR checkout_request_id=?").apply {
            setString(1, id); setString(2, id)
        }.executeQuery().let { rs ->
            if (rs.next()) Transaction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)) else null
        }
    }

    override suspend fun grantPass(userId: String, offerIdOrFeatureId: String): Pass = withContext(Dispatchers.IO) {
        val passId = "p-" + System.nanoTime()
        val featureId = "f1"
        c().prepareStatement("INSERT INTO passes(id, feature_id, user_id, active) VALUES(?,?,?,true)").apply {
            setString(1, passId); setString(2, featureId); setString(3, userId)
        }.executeUpdate()
        Pass(passId, featureId, userId, active = true)
    }

    override suspend fun transactions(status: String?): List<Transaction> = withContext(Dispatchers.IO) {
        val sql = if (status.isNullOrBlank()) "SELECT id, user_id, agent_slug, offer_id, amount, currency, status, checkout_request_id, created_at FROM transactions"
                  else "SELECT id, user_id, agent_slug, offer_id, amount, currency, status, checkout_request_id, created_at FROM transactions WHERE status=?"
        val stmt = c().prepareStatement(sql)
        if (!status.isNullOrBlank()) stmt.setString(1, status)
        stmt.executeQuery().let { rs ->
            buildList {
                while (rs.next()) add(Transaction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)))
            }
        }
    }

    /** Verified offers belonging to one agent storefront. */
    override suspend fun offersForAgent(slug: String): List<Offer> = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT id,title,price,verified FROM offers WHERE (agent_slug=? OR agent_slug IS NULL) AND verified=true").apply {
            setString(1, slug)
        }.executeQuery().let { rs -> buildList { while (rs.next()) add(Offer(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getBoolean(4))) } }
    }

    override suspend fun agentExists(slug: String): Boolean = withContext(Dispatchers.IO) {
        c().prepareStatement("SELECT 1 FROM agents WHERE slug=? AND active=true").apply { setString(1, slug) }
            .executeQuery().next()
    }

    override suspend fun createAgent(slug: String, name: String, ownerPhone: String?): Boolean = withContext(Dispatchers.IO) {
        c().prepareStatement("INSERT INTO agents(slug,name,owner_phone) VALUES(?,?,?) ON CONFLICT (slug) DO NOTHING").apply {
            setString(1, slug); setString(2, name); ownerPhone?.let { setString(3, it) } ?: setNull(3, java.sql.Types.NULL)
        }.executeUpdate() > 0
    }

    suspend fun recordAudit(action: String, detail: String) = withContext(Dispatchers.IO) {
        try {
            c().prepareStatement("INSERT INTO audit(action,detail) VALUES(?,?)").apply {
                setString(1, action); setString(2, detail)
            }.executeUpdate()
        } catch (_: Exception) { /* audit is best-effort; in-memory log remains authoritative */ }
    }

    override suspend fun setOfferVerified(id: String, verified: Boolean): Boolean = withContext(Dispatchers.IO) {
        c().prepareStatement("UPDATE offers SET verified=? WHERE id=?").apply {
            setBoolean(1, verified); setString(2, id)
        }.executeUpdate() > 0
    }

    override fun close() { try { conn?.close() } catch (_: Exception) {} }
}
