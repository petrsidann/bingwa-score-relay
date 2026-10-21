package relay

import kotlin.test.*

class WsCodecTest {
    @Test fun ping() {
        val (_, i) = decodeFrame("""{"event":"ping"}""")!!
        assertIs<PingIn>(i)
    }

    @Test fun appStateSet() {
        val (f, i) = decodeFrame("""{"event":"app_state.set","payload":{"serverId":"s1","message":"m","state":"ONLINE"}}""")!!
        assertEquals("app_state.set", f.event)
        assertIs<AppStateSet>(i)
        assertEquals("s1", (i as AppStateSet).msg.serverId)
        assertEquals("ONLINE", i.msg.state)
    }

    @Test fun taskAck() {
        val (_, i) = decodeFrame("""{"event":"task.ack","payload":{"taskId":"t9","ok":false}}""")!!
        assertIs<TaskAckIn>(i)
        assertEquals("t9", (i as TaskAckIn).ack.taskId)
        assertFalse(i.ack.ok)
    }

    @Test fun airtimeBalanceGet() {
        val (_, i) = decodeFrame("""{"event":"task.airtime_balance.get","payload":{"phone":"+234"}}""")!!
        assertIs<AirtimeBalanceGet>(i)
        assertEquals("+234", (i as AirtimeBalanceGet).req.phone)
    }

    @Test fun unknownIgnored() {
        val (_, i) = decodeFrame("""{"event":"whatever.xyz"}""")!!
        assertIs<Unknown>(i)
    }

    @Test fun badJsonNull() {
        assertNull(decodeFrame("not json"))
    }

    @Test fun encodeAck() {
        val raw = encodeFrame("task.airtime_balance.get_ack",
            Codec.json.encodeToJsonElement(AirtimeBalanceAck.serializer(), AirtimeBalanceAck("+1", 500.0)))
        val (f, _) = decodeFrame(raw)!!
        assertEquals("task.airtime_balance.get_ack", f.event)
        assertTrue(raw.contains("500.0"))
    }
}
