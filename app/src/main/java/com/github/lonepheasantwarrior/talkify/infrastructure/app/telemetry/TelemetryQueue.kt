package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

/**
 * 遥测事件有界队列（纯逻辑，可单测）
 *
 * 资源边界是第一优先级：队列满、超过字节预算、超过最大等待时间时丢弃遥测，
 * 绝不阻塞朗读或 UI。所有丢弃原因分别计数，保证"丢失"是可观测的。
 */
internal class TelemetryQueue(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = MONOTONIC_CLOCK,
) {

    internal data class Entry(
        val event: TelemetryEvent,
        val policyEpoch: Long,
        val enqueuedAtMs: Long,
        val estimatedBytes: Int,
        var attempts: Int = 0,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var bytes = 0

    fun offer(event: TelemetryEvent, policyEpoch: Long): Boolean = synchronized(lock) {
        val estimate = estimateBytes(event)
        if (entries.size >= maxEvents || bytes + estimate > maxBytes) return false
        entries.addLast(Entry(event, policyEpoch, clock(), estimate))
        bytes += estimate
        true
    }

    /**
     * 取出下一条事件；过期条目在出队时丢弃。
     *
     * @param onExpired 过期条目的回调（用于诊断计数）
     */
    fun poll(onExpired: (TelemetryQueue.Entry) -> Unit = {}): Entry? {
        synchronized(lock) {
            while (true) {
                val entry = entries.removeFirstOrNull() ?: return null
                bytes -= entry.estimatedBytes
                if (clock() - entry.enqueuedAtMs > ttlMs) {
                    onExpired(entry)
                    continue
                }
                return entry
            }
        }
    }

    fun clear(): Int = synchronized(lock) {
        val count = entries.size
        entries.clear()
        bytes = 0
        count
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun byteSize(): Int = synchronized(lock) { bytes }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    fun isExpired(entry: Entry): Boolean = clock() - entry.enqueuedAtMs > ttlMs

    /** 距离 TTL 过期还剩多少单调时间；0 表示已过期 */
    fun remainingTtl(entry: Entry): Long =
        (ttlMs - (clock() - entry.enqueuedAtMs)).coerceAtLeast(0)

    private fun estimateBytes(event: TelemetryEvent): Int {
        if (event.wireBytes > 0) return event.wireBytes
        var size = 256 + event.url.length + (event.title?.length ?: 0) + (event.name?.length ?: 0)
        for ((key, value) in event.data) {
            // 按键/值的 UTF-8 上界估算（中文 3 字节/字符），避免字节预算被低估
            size += key.length * 2 + value.toString().length * 3 + 8
        }
        return size
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 256
        const val DEFAULT_MAX_BYTES = 512 * 1024
        const val DEFAULT_TTL_MS = 60_000L

        /** TTL 与限流等待都使用单调时钟，避免用户改系统时间导致队列行为跳变 */
        private val MONOTONIC_CLOCK: () -> Long = { System.nanoTime() / 1_000_000 }
    }
}
