package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `Retry-After` 解析
 *
 * 秒数与原样保留（不因本地等待预算而截短）；HTTP-date 按墙上时钟换算。
 * 服务端要求 120 秒时，客户端要么等满，要么丢弃，不能 10 秒后提前重试。
 */
internal object RetryAfter {

    /** RFC 9110 允许的三种 HTTP-date 形式 */
    private val HTTP_DATE_FORMATTERS = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
    )
    private val ASCTIME_FORMATTER =
        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US)

    fun parseMillis(value: String?, wallClockMs: Long): Long? {
        val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        header.toLongOrNull()?.let { seconds ->
            if (seconds <= 0) return 0
            return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }
        val zoned = HTTP_DATE_FORMATTERS.firstNotNullOfOrNull { formatter ->
            runCatching { ZonedDateTime.parse(header, formatter) }.getOrNull()
        }
        val targetMillis = zoned?.toInstant()?.toEpochMilli()
            ?: runCatching {
                // asctime 不带时区，按 HTTP-date 约定视为 GMT
                LocalDateTime.parse(header, ASCTIME_FORMATTER)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrNull()
            ?: return null
        return (targetMillis - wallClockMs).coerceAtLeast(0)
    }
}

/**
 * 站点级重试闸门
 *
 * 429 的 `Retry-After` 不只约束当前事件：在允许时刻之前，所有后续事件都不得发送。
 * 使用单调时钟，避免系统时间调整导致提前放行。
 */
internal class RetryGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private var nextAllowedAtMs = 0L

    @Synchronized
    fun deferFor(waitMs: Long) {
        if (waitMs <= 0) return
        val now = clock()
        val target = if (waitMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + waitMs
        if (target > nextAllowedAtMs) nextAllowedAtMs = target
    }

    @Synchronized
    fun remainingMs(): Long = (nextAllowedAtMs - clock()).coerceAtLeast(0)

    @Synchronized
    fun reset() {
        nextAllowedAtMs = 0
    }
}
