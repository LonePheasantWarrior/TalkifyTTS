package com.github.lonepheasantwarrior.talkify.service.delivery

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 供应商回调线程与合成线程之间的有界交付桥。
 *
 * 线程契约（文档第 7.2 节）：
 * - 供应商回调线程只把音频/终态作为有界事件放入队列（按事件数与字节数双重有界），
 *   队列满时阻塞生产者形成背压；
 * - 由 `onSynthesizeText` 所在线程消费事件并串行调用 `SynthesisCallback`；
 * - `cancel()` 清空队列并唤醒双方，生产者不得在持有会阻碍停止的锁时无限等待。
 *
 * 本类不依赖 Android，可直接 JVM 测试。
 */
class SynthesisDeliveryBridge(
    private val maxQueuedBytes: Int = DEFAULT_MAX_QUEUED_BYTES,
    private val maxQueuedEvents: Int = DEFAULT_MAX_QUEUED_EVENTS,
) {

    sealed interface Event {
        data class Audio(
            val data: ByteArray,
            val sampleRate: Int,
            val audioFormat: Int,
            val channelCount: Int,
        ) : Event

        data object Completed : Event
        data class Failed(val message: String) : Event
        data object Cancelled : Event
    }

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()
    private val queue = ArrayDeque<Event>()
    private var queuedBytes = 0
    private var cancelled = false
    private var terminalPublished = false

    /**
     * 发布音频；返回 false 表示桥已取消，生产者应尽快停止。
     * 大数据按 [MAX_AUDIO_EVENT_BYTES] 切片，保证字节预算对每个事件都成立。
     */
    fun publishAudio(
        data: ByteArray,
        sampleRate: Int,
        audioFormat: Int,
        channelCount: Int,
    ): Boolean {
        if (data.isEmpty()) return true
        var offset = 0
        while (offset < data.size) {
            val size = minOf(MAX_AUDIO_EVENT_BYTES, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + size)
            lock.lock()
            try {
                while (!cancelled && (queue.size >= maxQueuedEvents || queuedBytes + size > maxQueuedBytes)) {
                    // 定时唤醒而非无限等待：cancel() 会 signalAll，即使错过也最多 100ms 后自检
                    notFull.await(100, TimeUnit.MILLISECONDS)
                }
                if (cancelled) return false
                queue.addLast(Event.Audio(chunk, sampleRate, audioFormat, channelCount))
                queuedBytes += size
                notEmpty.signalAll()
            } finally {
                lock.unlock()
            }
            offset += size
        }
        return true
    }

    fun publishCompleted() = publishTerminal(Event.Completed)

    fun publishFailed(message: String) = publishTerminal(Event.Failed(message))

    private fun publishTerminal(event: Event) {
        lock.lock()
        try {
            if (cancelled || terminalPublished) return
            terminalPublished = true
            queue.addLast(event)
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** 取消并丢弃队列；幂等。取消后不再接受任何事件。 */
    fun cancel() {
        lock.lock()
        try {
            cancelled = true
            queue.clear()
            queuedBytes = 0
            notEmpty.signalAll()
            notFull.signalAll()
        } finally {
            lock.unlock()
        }
    }

    val isCancelled: Boolean
        get() = lock.withLockValue { cancelled }

    /**
     * 阻塞取一个事件。超时返回 null；取消返回 [Event.Cancelled]。
     */
    fun take(timeoutMs: Long): Event? {
        if (timeoutMs <= 0) return Event.Cancelled
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.lock()
        try {
            while (true) {
                if (cancelled) return Event.Cancelled
                val event = queue.pollFirst()
                if (event != null) {
                    if (event is Event.Audio) queuedBytes -= event.data.size
                    notFull.signalAll()
                    return event
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                try {
                    notEmpty.awaitNanos(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Event.Cancelled
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private inline fun <T> ReentrantLock.withLockValue(block: () -> T): T {
        lock()
        return try { block() } finally { unlock() }
    }

    companion object {
        const val DEFAULT_MAX_QUEUED_BYTES = 1 shl 20 // 1 MiB
        const val DEFAULT_MAX_QUEUED_EVENTS = 128
        const val MAX_AUDIO_EVENT_BYTES = 16 * 1024
    }
}
