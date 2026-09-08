package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RecordTransportTest {

    private class FakeClock(var nowMs: Long = 1_000_000L)

    private val bodies = mutableListOf<String>()
    private val caches = mutableListOf<String>()

    private fun newTransport(cache: String? = "session-cache", clock: FakeClock = FakeClock()): RecordTransport =
        RecordTransport(
            websiteId = "site-id",
            cacheProvider = { cache },
            post = { body, c ->
                bodies.add(body)
                caches.add(c)
            },
            clockMs = { clock.nowMs }
        )

    private fun payloads(): List<JSONObject> = bodies.map { JSONObject(it).getJSONObject("payload") }

    private fun eventAt(payload: JSONObject, index: Int): JSONObject =
        payload.getJSONArray("events").getJSONObject(index)

    @Test
    fun `增量事件缓冲并随flush成批上报`() {
        val transport = newTransport()
        repeat(3) { transport.addRecordEvent(JSONObject().put("n", it)) }
        assertEquals(0, bodies.size)

        transport.flushRecord()
        assertEquals(1, bodies.size)
        assertEquals("record", JSONObject(bodies[0]).getString("type"))
        assertEquals("session-cache", caches[0])
        val payload = payloads()[0]
        assertEquals("site-id", payload.getString("website"))
        assertEquals(1000L, payload.getLong("timestamp"))
        assertEquals(3, payload.getJSONArray("events").length())
    }

    @Test
    fun `缓冲满100条自动上报`() {
        val transport = newTransport()
        repeat(100) { transport.addRecordEvent(JSONObject().put("n", it)) }
        assertEquals(1, bodies.size)

        transport.addRecordEvent(JSONObject().put("n", 100))
        assertEquals(1, bodies.size)

        transport.flushRecord()
        assertEquals(2, bodies.size)
        assertEquals(100, payloads()[0].getJSONArray("events").length())
        assertEquals(1, payloads()[1].getJSONArray("events").length())
    }

    @Test
    fun `批量体积超限自动上报`() {
        val transport = newTransport()
        val bigEvent = JSONObject().put("k", "a".repeat(200_000))
        repeat(3) { transport.addRecordEvent(bigEvent) }
        // 单事件不超限，累计 3 条约 600KB 超过 500KB → 第 3 条入列时触发上报
        assertEquals(1, bodies.size)
        assertEquals(3, payloads()[0].getJSONArray("events").length())
        assertTrue(bodies[0].toByteArray(StandardCharsets.UTF_8).size > 500_000)
    }

    @Test
    fun `FullSnapshot单独成包并先冲刷缓冲`() {
        val transport = newTransport()
        transport.addRecordEvent(JSONObject().put("n", "incremental"))
        transport.addRecordEvent(JSONObject().put("n", "snapshot"), solo = true)

        assertEquals(2, bodies.size)
        assertEquals(1, payloads()[0].getJSONArray("events").length())
        assertEquals("incremental", eventAt(payloads()[0], 0).getString("n"))
        assertEquals(1, payloads()[1].getJSONArray("events").length())
        assertEquals("snapshot", eventAt(payloads()[1], 0).getString("n"))
    }

    @Test
    fun `超大事件分片上报且可重组还原`() {
        val transport = newTransport()
        val originalEvent = JSONObject().put("k", "a".repeat(1_200_000))
        val original = originalEvent.toString()
        transport.addRecordEvent(originalEvent)

        assertTrue(bodies.size > 1)
        val fragmentIds = mutableSetOf<String>()
        var total = 0
        val valueBuilder = StringBuilder()
        val timestamps = mutableListOf<Long>()
        bodies.forEachIndexed { index, body ->
            val bytes = body.toByteArray(StandardCharsets.UTF_8).size
            assertTrue("分片 #$index 体积 $bytes 超限", bytes <= 500_000)
            val envelope = JSONObject(body)
            assertEquals("record", envelope.getString("type"))
            val payload = envelope.getJSONObject("payload")
            assertEquals("site-id", payload.getString("website"))
            timestamps.add(payload.getLong("timestamp"))
            val fragment = payload.getJSONArray("events").getJSONObject(0)
            assertEquals(Rrweb.FRAGMENT_EVENT_TYPE, fragment.getString("type"))
            val data = fragment.getJSONObject("data")
            fragmentIds.add(data.getString("id"))
            total = data.getInt("total")
            assertEquals(index, data.getInt("index"))
            valueBuilder.append(data.getString("value"))
        }
        assertEquals(1, fragmentIds.size)
        assertEquals(bodies.size, total)
        assertEquals(original, valueBuilder.toString())
        // 分片 payload.timestamp 逐片递增
        assertEquals(timestamps, timestamps.sorted())
        assertEquals(timestamps.last() - timestamps.first(), (bodies.size - 1).toLong())
    }

    @Test
    fun `同秒内多次上报时间戳严格单调递增`() {
        val clock = FakeClock()
        val transport = newTransport(clock = clock)
        transport.addRecordEvent(JSONObject().put("n", 1))
        transport.flushRecord()
        transport.addRecordEvent(JSONObject().put("n", 2))
        transport.flushRecord()
        val first = payloads()[0].getLong("timestamp")
        val second = payloads()[1].getLong("timestamp")
        assertEquals(first + 1, second)
    }

    @Test
    fun `无会话令牌时静默丢弃`() {
        val transport = newTransport(cache = null)
        transport.addRecordEvent(JSONObject().put("n", 1))
        transport.flushAll()
        assertEquals(0, bodies.size)
    }

    @Test
    fun `热图缓冲满20条自动上报`() {
        val transport = newTransport()
        repeat(20) {
            transport.addHeatmapEvent(JSONObject().put("type", "click").put("n", it))
        }
        assertEquals(1, bodies.size)
        assertEquals("heatmap", JSONObject(bodies[0]).getString("type"))
        val payload = payloads()[0]
        assertEquals(1000L, payload.getLong("timestamp"))
        assertEquals(20, payload.getJSONArray("events").length())
        assertEquals(19, eventAt(payload, 19).getInt("n"))

        transport.addHeatmapEvent(JSONObject().put("type", "click").put("n", 20))
        assertEquals(1, bodies.size)
        transport.flushHeatmap()
        assertEquals(2, bodies.size)
        assertEquals(1, payloads()[1].getJSONArray("events").length())
    }

    @Test
    fun `flushAll同时冲刷record与heatmap缓冲`() {
        val transport = newTransport()
        transport.addRecordEvent(JSONObject().put("n", 1))
        transport.addHeatmapEvent(JSONObject().put("type", "scroll"))
        transport.flushAll()
        assertEquals(2, bodies.size)
        val types = bodies.map { JSONObject(it).getString("type") }.toSet()
        assertEquals(setOf("record", "heatmap"), types)
    }

    @Test
    fun `record与heatmap上报携带会话令牌`() {
        val transport = newTransport(cache = "token-1")
        transport.addRecordEvent(JSONObject().put("n", 1))
        transport.addHeatmapEvent(JSONObject().put("type", "scroll"))
        transport.flushAll()
        assertEquals(listOf("token-1", "token-1"), caches)
    }
}
