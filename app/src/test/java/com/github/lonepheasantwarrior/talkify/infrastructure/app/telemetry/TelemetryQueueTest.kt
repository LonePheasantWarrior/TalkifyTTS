package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryQueueTest {

    private var nowMs = 1_000L

    private fun queue(
        maxEvents: Int = 4,
        maxBytes: Int = 4 * 1024,
        ttlMs: Long = 60_000L,
    ) = TelemetryQueue(maxEvents, maxBytes, ttlMs) { nowMs }

    private fun event(name: String = "preview_play_click", textLength: Int = 10) = TelemetryEvent(
        name = name,
        data = mapOf("text_length" to textLength),
    )

    @Test
    fun `队列满时拒绝新事件`() {
        val q = queue(maxEvents = 2)
        assertTrue(q.offer(event(), 1))
        assertTrue(q.offer(event(), 1))
        assertFalse(q.offer(event(), 1))
        assertEquals(2, q.size())
    }

    @Test
    fun `字节预算不足时拒绝新事件`() {
        val q = queue(maxBytes = 500)
        assertTrue(q.offer(event(textLength = 1), 1))
        // 单条约 295 字节估算，第二条超过 500 字节预算
        assertFalse(q.offer(event(textLength = 2), 1))
    }

    @Test
    fun `过期事件在出队时丢弃并回调`() {
        val q = queue(ttlMs = 1_000)
        q.offer(event(), 1)
        nowMs += 1_001
        var expired = 0
        assertNull(q.poll { expired++ })
        assertEquals(1, expired)
        assertEquals(0, q.size())
    }

    @Test
    fun `按 FIFO 出队并携带许可代际`() {
        val q = queue()
        q.offer(event(textLength = 1), 7)
        q.offer(event(textLength = 2), 7)
        assertEquals(1, q.poll()!!.event.data["text_length"])
        assertEquals(7L, q.poll()!!.policyEpoch)
        assertEquals(0, q.byteSize())
    }

    @Test
    fun `使用schema计算的实际线宽字节并暴露剩余TTL`() {
        val q = queue(maxBytes = 2_000, ttlMs = 1_000)
        val event = event().copy(wireBytes = 1_234)
        assertTrue(q.offer(event, 1))
        assertEquals(1_234, q.byteSize())
        val entry = q.poll()!!
        assertEquals(1_000L, q.remainingTtl(entry))
        nowMs += 400
        assertEquals(600L, q.remainingTtl(entry))
    }

    @Test
    fun `清空队列释放字节预算`() {
        val q = queue()
        q.offer(event(), 1)
        assertTrue(q.byteSize() > 0)
        assertEquals(1, q.clear())
        assertEquals(0, q.byteSize())
        assertTrue(q.isEmpty())
    }
}
