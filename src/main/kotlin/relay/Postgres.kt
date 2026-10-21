package relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

class PostgresStore(private val url: String) : Store {
    private var conn: Connection? = null

    private fun c(): Connection {
        conn?.takeIf { !it.isClosed }?.let { return it }
        val n = java.sql.DriverManager.getConnection(url)
        conn = n
        n.createStatement().use { s ->
            s.execute("CREATE TABLE IF NOT EXISTS offers(id TEXT PRIMARY KEY,title TEXT,price DOUBLE PRECISION,verified BOOLEAN)")
            s.execute("CREATE TABLE IF NOT EXISTS features(id TEXT PRIMARY KEY,code TEXT,name TEXT,description TEXT)")
            s.execute("CREATE TABLE IF NOT EXISTS passes(id TEXT PRIMARY KEY,feature_id TEXT,user_id TEXT,active BOOLEAN)")
            s.execute("CREATE TABLE IF NOT EXISTS users(user_id TEXT PRIMARY KEY,phone TEXT,access_token TEXT,refresh_token TEXT,expires_at TEXT)")
            s.execute("CREATE TABLE IF NOT EXISTS otps(phone TEXT PRIMARY KEY,code TEXT)")
            s.execute("CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,from_id TEXT,to_id TEXT,body TEXT)")
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TEXT")
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS status TEXT")
            // seed
            s.execute("INSERT INTO offers VALUES('o1','Starter Pack',5.0,true),('o2','Pro Season',20.0,true) ON CONFLICT DO NOTHING")
            s.execute("INSERT INTO features VALUES('f1','LIVE_SCORES','Live Scores','Real-time'),('f2','AIRTIME','Airtime Rewards','Earn airtime') ON CONFLICT DO NOTHING")
            s.execute("INSERT INTO passes VALUES('p1','f1','u1',true),('p2','f2','u1',true) ON CONFLICT DO NOTHING")
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
        c().prepareStatement("INSERT INTO users VALUES(?,?,?,?,?,?,?)").apply {
            setString(1, t.userId); setString(2, t.phone); setString(3, t.accessToken); setString(4, t.refreshToken); setString(5, t.expiresAt)
            setString(6, java.time.Instant.now().toString()); setString(7, "active")
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
        c().prepareStatement("INSERT INTO offers VALUES(?,?,?,?)").apply {
            setString(1, o.id); setString(2, o.title); setDouble(3, o.price); setBoolean(4, o.verified)
        }.executeUpdate()
        o
    }

    override suspend fun setOfferVerified(id: String, verified: Boolean): Boolean = withContext(Dispatchers.IO) {
        c().prepareStatement("UPDATE offers SET verified=? WHERE id=?").apply {
            setBoolean(1, verified); setString(2, id)
        }.executeUpdate() > 0
    }

    override fun close() { try { conn?.close() } catch (_: Exception) {} }
}
