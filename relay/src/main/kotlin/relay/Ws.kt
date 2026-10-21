package relay

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

object Codec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

@Serializable
data class Frame(val event: String, val payload: JsonElement? = null)

@Serializable
data class AppStateMsg(val serverId: String, val message: String, val state: String)

@Serializable
data class TaskAck(val taskId: String = "", val ok: Boolean = true)

@Serializable
data class AirtimeBalanceReq(val phone: String = "")

@Serializable
data class AirtimeBalanceAck(val phone: String = "", val balance: Double = 0.0, val currency: String = "NGN")

sealed class Inbound
data object PingIn : Inbound()
data class AppStateSet(val msg: AppStateMsg) : Inbound()
data class TaskAckIn(val ack: TaskAck) : Inbound()
data class AirtimeBalanceGet(val req: AirtimeBalanceReq) : Inbound()
data class Unknown(val name: String) : Inbound()

fun decodeFrame(raw: String): Pair<Frame, Inbound>? = try {
    val f = Codec.json.decodeFromString(Frame.serializer(), raw)
    val inb = when (f.event) {
        "ping" -> PingIn
        "app_state.set" -> AppStateSet(f.payload?.let { Codec.json.decodeFromJsonElement(AppStateMsg.serializer(), it) } ?: AppStateMsg("", "", ""))
        "task.ack" -> TaskAckIn(f.payload?.let { Codec.json.decodeFromJsonElement(TaskAck.serializer(), it) } ?: TaskAck())
        "task.airtime_balance.get" -> AirtimeBalanceGet(f.payload?.let { Codec.json.decodeFromJsonElement(AirtimeBalanceReq.serializer(), it) } ?: AirtimeBalanceReq())
        else -> Unknown(f.event)
    }
    f to inb
} catch (e: Exception) { null }

fun encodeFrame(event: String, payload: JsonElement? = null): String =
    Codec.json.encodeToString(Frame.serializer(), Frame(event, payload))
