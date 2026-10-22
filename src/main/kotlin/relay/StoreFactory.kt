package relay

/**
 * Relay 13: single seam for choosing the persistence backend.
 * DB_URL set  -> PostgresStore (schema.sql applied at boot, idempotent).
 * DB_URL unset -> InMemoryStore (tests / dev without a database).
 * SEED_DEMO=true adds demo users/passes on either backend.
 */
object StoreFactory {
    fun fromEnv(): Store {
        val dbUrl = env("DB_URL")
        val seedDemo = env("SEED_DEMO")?.toBooleanStrictOrNull() == true
        val store: Store = if (!dbUrl.isNullOrBlank()) PostgresStore(dbUrl) else InMemoryStore()
        if (seedDemo) DemoSeed.apply(store)
        return store
    }
}

object DemoSeed {
    /** Idempotent-ish demo rows (guarded by known phone numbers). */
    fun apply(store: Store) = kotlinx.coroutines.runBlocking {
        val phones = listOf("+2348000000001", "+2348000000002")
        for (p in phones) if (store.signIn(p) == null) store.signUp(p)
    }
}
