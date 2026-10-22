package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class RetryGateTest {

    @Test
    fun `秒数不封顶且原样保留`() {
        assertEquals(120_000L, RetryAfter.parseMillis("120", wallClockMs = 0))
        assertEquals(3_600_000L, RetryAfter.parseMillis("3600", wallClockMs = 0))
    }

    @Test
    fun `HTTP日期按墙上时钟换算`() {
        val now = Instant.parse("2026-10-22T00:00:00Z").toEpochMilli()
        val target = Instant.parse("2026-10-22T00:02:00Z")
        val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(target.atZone(ZoneOffset.UTC))

        assertEquals(120_000L, RetryAfter.parseMillis(header, now))
    }

    @Test
    fun `非法头返回null且过去日期为零`() {
        assertNull(RetryAfter.parseMillis("not-a-date", 0))
        assertNull(RetryAfter.parseMillis("", 0))
        val header = DateTimeFormatter.RFC_1123_DATE_TIME
            .format(Instant.parse("2020-01-01T00:00:00Z").atZone(ZoneOffset.UTC))
        assertEquals(0L, RetryAfter.parseMillis(header, Instant.parse("2026-10-22T00:00:00Z").toEpochMilli()))
    }

    @Test
    fun `站点级闸门约束后续所有事件且单调时钟到期后放行`() {
        var now = 1_000L
        val gate = RetryGate { now }

        gate.deferFor(120_000)
        assertEquals(120_000L, gate.remainingMs())

        now += 100_000
        assertEquals(20_000L, gate.remainingMs())

        now += 20_000
        assertEquals(0L, gate.remainingMs())
        assertTrue(gate.remainingMs() == 0L)
    }

    @Test
    fun `超大秒数不会溢出`() {
        assertEquals(Long.MAX_VALUE, RetryAfter.parseMillis(Long.MAX_VALUE.toString(), 0))
    }
}
