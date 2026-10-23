package relay

import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun env(name: String, default: String? = null): String? = System.getenv(name) ?: default

fun main() {
    val port = (env("PORT", "8080"))!!.toInt()
    val store = StoreFactory.fromEnv()
    val serverKey = env("RELAY_SERVER_KEY")
    println("Bingwa Relay starting on :$port storage=${store.javaClass.simpleName} keyAuth=${serverKey != null}")
    val server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
        relayModule(store, serverKey)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        println("Bingwa Relay shutting down gracefully...")
        server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        store.close()
    })

    server.start(wait = true)
}
