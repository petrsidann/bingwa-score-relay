package relay

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.http.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class MetricSample(val t: Long, val wsConnections: Int, val rps: Double, val messagesPerMin: Int)

/** Rolling 5-minute ring of per-second metric samples, for the Overview live charts. */
object MetricsRing {
    private const val CAP = 300 // seconds
    private val ring = java.util.concurrent.ConcurrentLinkedDeque<MetricSample>()
    @Volatile private var lastCounts: Map<String, Long> = emptyMap()
    @Volatile private var lastTotal = 0L
    @Volatile private var lastMs = System.currentTimeMillis()

    fun snapshot(): List<MetricSample> = ring.toList()

    /** Called once per second by the ticker (and lazily on read to stay current). */
    fun tick() {
        val now = System.currentTimeMillis()
        val total = AdminState.routeCounts.values.sumOf { it.get() }
        val dtSec = ((now - lastMs) / 1000.0).coerceAtLeast(1.0)
        val rps = ((total - lastTotal).coerceAtLeast(0)) / dtSec
        lastTotal = total; lastMs = now
        ring.addLast(MetricSample(now / 1000 * 1000, WsRegistry.count(), rps, AdminState.messagesLastMinute()))
        while (ring.size > CAP) ring.pollFirst()
    }

    /** Bring the ring up to date if the ticker has not run recently (e.g. in tests). */
    fun catchUp() {
        while (System.currentTimeMillis() - lastMs >= 1000) tick()
    }
}

fun Application.metricsRoutes(serverKey: String?) {
    routing {
        route("/admin") {
            gateAdmin(serverKey)
            route("/api") {
                get("/metrics/history") {
                    AdminState.count("GET /admin/api/metrics/history")
                    MetricsRing.catchUp()
                    call.respond(MetricsRing.snapshot())
                }
            }
            // SSE stream of live metric samples (one per second). Auth via header or ?key=.
            sse("/sse/metrics") {
                MetricsRing.catchUp()
                var lastT = 0L
                send(ServerSentEvent(event = "metric", data = Codec.json.encodeToString(
                    MetricSample.serializer(), MetricSample(0, WsRegistry.count(), 0.0, AdminState.messagesLastMinute()))))
                while (true) {
                    delay(1000)
                    MetricsRing.tick()
                    val s = MetricsRing.snapshot().lastOrNull() ?: continue
                    if (s.t != lastT) {
                        lastT = s.t
                        send(ServerSentEvent(event = "metric", data = Codec.json.encodeToString(MetricSample.serializer(), s)))
                    }
                }
            }
        }
    }
}
