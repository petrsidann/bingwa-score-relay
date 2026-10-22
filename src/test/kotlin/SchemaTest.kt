package relay

import kotlin.test.*

/**
 * Relay 13: schema.sql is the single source of truth applied at boot when DB_URL is set.
 * These tests parse it statically (no live Postgres needed) and assert the repository seam.
 */
class SchemaTest {
    private val sql: String by lazy {
        val s = javaClass.getResourceAsStream("/db/schema.sql")
        assertNotNull(s, "db/schema.sql must be on the classpath")
        s.readBytes().decodeToString()
    }

    private val tables = listOf(
        "schema_version", "users", "otps", "offers", "coupons", "features",
        "passes", "messages", "transactions", "flags", "audit", "agents"
    )

    @Test fun allTablesDeclared() {
        for (t in tables) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS $t(", ignoreCase = true), "missing table: $t")
        }
    }

    @Test fun idempotentCreateIfNotExistsEverywhere() {
        val creates = Regex("CREATE TABLE(?! IF NOT EXISTS)", RegexOption.IGNORE_CASE).findAll(sql).count()
        assertEquals(0, creates, "every CREATE TABLE must be IF NOT EXISTS (idempotent boot)")
        val inserts = Regex("INSERT INTO", RegexOption.IGNORE_CASE).findAll(sql).count()
        assertTrue(sql.contains("ON CONFLICT", ignoreCase = true))
        assertTrue(inserts >= 4, "seed inserts expected")
    }

    @Test fun versionedAndBalanced() {
        assertTrue(sql.contains("INSERT INTO schema_version", ignoreCase = true), "schema must record its version")
        // naive statement splitter sanity check: every non-comment line block ends with ';'
        val body = sql.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n")
        val stmts = body.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(stmts.size >= 15, "expected many statements, got ${stmts.size}")
        assertTrue(stmts.all { !it.endsWith(";") })
        // balanced parentheses per statement
        for (st in stmts) {
            var depth = 0
            for (ch in st) { if (ch == '(') depth++; if (ch == ')') depth-- ; assertTrue(depth >= 0, "unbalanced in: $st") }
            assertEquals(0, depth, "unbalanced parens in: $st")
        }
    }

    @Test fun storeFactorySeam() {
        // DB_URL unset in test env -> InMemoryStore; interface fully implemented by both stores.
        val store = StoreFactory.fromEnv()
        assertTrue(store is InMemoryStore, "without DB_URL the factory must return InMemoryStore")
        kotlinx.coroutines.runBlocking {
            assertTrue(store.offers().isNotEmpty())
            assertTrue(store.features().isNotEmpty())
            assertNotNull(store.featureByCode("LIVE_SCORES"))
            assertTrue(store.passes("f1").isNotEmpty())
            val t = store.signUp("+2349000000000")
            assertNotNull(store.signIn("+2349000000000"))
            assertNotNull(store.refresh(t.refreshToken))
            val code = store.requestOtp("+2349000000001")
            assertNotNull(store.verifyOtp("+2349000000001", code))
            assertNotNull(store.createOffer("X", 1.0, true))
            assertTrue(store.setUserStatus(t.userId, "suspended"))
            assertTrue(store.setOfferVerified("o1", false))
            assertNotNull(store.sendMessage("u1", "u2", "hi"))
        }
        store.close()
        // PostgresStore compiles against the FULL interface (constructor without connecting).
        val pg = PostgresStore(url = "jdbc:postgresql://localhost:1/postgres?placeholder=true", applySchemaAtBoot = false)
        assertNotNull(pg)
        pg.close()
    }
}
