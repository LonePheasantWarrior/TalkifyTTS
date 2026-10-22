package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 可取消的一次性窗口到期任务 */
internal fun interface WindowTimer {
    fun cancel()
}

/** 窗口调度器（单测注入虚拟时钟/手动触发实现） */
internal interface WindowScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): WindowTimer
}

internal object DefaultWindowScheduler : WindowScheduler {

    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "talkify-telemetry-window").apply { isDaemon = true }
    }.apply {
        // 取消的 5 分钟任务立即从队列移除，避免频繁撤回时积累已取消任务
        removeOnCancelPolicy = true
    }

    override fun schedule(delayMs: Long, task: () -> Unit): WindowTimer {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return WindowTimer { future.cancel(false) }
    }
}

/**
 * 汇总提交的许可边界
 *
 * [commitIfCurrent] 在许可锁内执行 [block]，让“许可仍当前”与窗口变更为一个原子提交：
 * 关闭/重开要么发生在提交之前（本次提交整体失效），要么发生在提交之后（由撤回清理
 * 处理旧窗口），不会插入两者之间删除新代际数据。
 *
 * 锁顺序固定为 **许可锁 → 窗口锁**：聚合器在持有窗口锁时绝不调用本接口，避免与
 * 关闭/重开路径形成锁序反转。
 */
internal interface PermitCommitBoundary {
    fun isCurrent(permit: CollectionPermit): Boolean

    fun commitIfCurrent(permit: CollectionPermit, block: () -> Unit): Boolean

    fun currentPermit(): CollectionPermit
}

private object ControllerPermitCommitBoundary : PermitCommitBoundary {

    override fun isCurrent(permit: CollectionPermit): Boolean =
        TelemetryController.isPermitCurrent(permit)

    override fun commitIfCurrent(permit: CollectionPermit, block: () -> Unit): Boolean =
        TelemetryController.runIfPermitCurrent(permit, block) != null

    override fun currentPermit(): CollectionPermit = TelemetryController.capturePermit()
}

/**
 * TTS 请求结果的固定窗口本地汇总
 *
 * 高频系统合成不再逐条全量上送：每个窗口按 `provider_id + model_category` 聚合
 * 有界计数与耗时直方图，窗口到期或达到请求上限时发送 `tts_metrics_window`。
 * 逐次明细只保留失败样本与少量成功样本（由 [TtsTelemetryTracker] 决定）。
 *
 * 窗口计时使用单调时钟；墙上时钟只用于报表时间标签。第一个有效结果建立窗口时
 * 安排可取消的一次性到期任务，第 [MAX_REQUESTS] 个结果入账后立即冲刷，不依赖
 * 下一次请求或 Activity 生命周期。窗口绑定创建时的 [CollectionPermit]，
 * 许可校验与窗口变更通过 [PermitCommitBoundary] 原子提交，旧代际的迟到提交
 * 不能替换或删除较新的窗口。
 */
internal class TtsMetricsAggregator(
    private val monoClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val scheduler: WindowScheduler = DefaultWindowScheduler,
    private val permitBoundary: PermitCommitBoundary = ControllerPermitCommitBoundary,
    private val sink: (Map<String, Any>, CollectionPermit) -> Unit,
) {

    private data class Key(val providerId: String, val modelCategory: String)

    private data class Drained(val events: List<Map<String, Any>>, val permit: CollectionPermit)

    private class Bucket {
        var requestCount = 0
        var successCount = 0
        var errorCount = 0
        var timeoutCount = 0
        var cancelledCount = 0
        var ignoredCount = 0
        var hasAudioCount = 0
        var providerCompletedCount = 0
        var textChars = 0L
        var prepareMsTotal = 0L
        var synthesisMsTotal = 0L
        var audioBytesTotal = 0L
        val durationBuckets = IntArray(DURATION_BUCKETS.size)
        val firstAudioBuckets = IntArray(FIRST_AUDIO_BUCKETS.size)
        var firstAudioMissing = 0
    }

    private val lock = Any()
    private val buckets = LinkedHashMap<Key, Bucket>()
    private var windowPermit: CollectionPermit? = null
    private var windowStartWallMs = 0L
    private var lastRecordWallMs = 0L
    private var totalRequests = 0
    private var overflowed = false
    private var timerGeneration = 0L
    private var timer: WindowTimer? = null

    internal data class Summary(
        val providerId: String,
        val modelCategory: String,
        val status: String,
        val hasAudio: Boolean,
        val providerCompleted: Boolean,
        val textLength: Int,
        val durationMs: Long,
        val prepareMs: Long?,
        val synthesisMs: Long?,
        val firstAudioMs: Long?,
        val audioBytes: Long,
    )

    fun record(summary: Summary, permit: CollectionPermit) {
        if (!permit.enabled) return
        var drained: Drained? = null
        // 许可校验与窗口变更同处一个提交边界：关闭/重开无法插入两者之间
        val committed = permitBoundary.commitIfCurrent(permit) {
            synchronized(lock) {
                val current = windowPermit
                if (current != permit) {
                    // 本 permit 已在许可锁内确认当前，任何不同窗口都只能是旧代际
                    if (current != null) discardLocked()
                    windowPermit = permit
                    windowStartWallMs = wallClock()
                    lastRecordWallMs = windowStartWallMs
                    scheduleExpiryLocked(permit)
                }
                lastRecordWallMs = wallClock()
                totalRequests++
                val key = keyOf(summary.providerId, summary.modelCategory)
                val bucket = buckets.getOrPut(key) { Bucket() }
                bucket.requestCount++
                bucket.textChars += summary.textLength.coerceAtLeast(0).toLong()
                bucket.audioBytesTotal += summary.audioBytes.coerceAtLeast(0)
                summary.prepareMs?.let { bucket.prepareMsTotal += it }
                summary.synthesisMs?.let { bucket.synthesisMsTotal += it }
                if (summary.hasAudio) bucket.hasAudioCount++
                if (summary.providerCompleted) bucket.providerCompletedCount++
                when (summary.status) {
                    TtsTelemetryTracker.STATUS_SUCCESS -> bucket.successCount++
                    TtsTelemetryTracker.STATUS_ERROR -> bucket.errorCount++
                    TtsTelemetryTracker.STATUS_TIMEOUT -> bucket.timeoutCount++
                    TtsTelemetryTracker.STATUS_CANCELLED -> bucket.cancelledCount++
                    TtsTelemetryTracker.STATUS_IGNORED -> {
                        bucket.ignoredCount++
                        bucket.requestCount--
                    }
                }
                if (summary.status != TtsTelemetryTracker.STATUS_IGNORED) {
                    bucket.durationBuckets[durationBucketIndex(summary.durationMs)]++
                    summary.firstAudioMs?.let {
                        bucket.firstAudioBuckets[firstAudioBucketIndex(it)]++
                    } ?: bucket.firstAudioMissing++
                }
                if (totalRequests >= MAX_REQUESTS) {
                    drained = drainLocked()
                }
            }
        }
        if (!committed) return
        drained?.let { emit(it.events, it.permit) }
    }

    fun flush() {
        // 先在窗口锁内读取观察对象并释放，再按“许可锁 → 窗口锁”顺序提交
        val observed = synchronized(lock) { windowPermit } ?: return
        var drained: Drained? = null
        val committed = permitBoundary.commitIfCurrent(observed) {
            synchronized(lock) {
                if (windowPermit == observed) drained = drainLocked()
            }
        }
        if (!committed) {
            // 失效 flush 只能清理它观察到的同一窗口；新代际窗口即使已建立也不触碰
            synchronized(lock) {
                if (windowPermit == observed) discardLocked()
            }
            return
        }
        drained?.let { emit(it.events, it.permit) }
    }

    /**
     * 撤回许可时的清理
     *
     * 只清除早于当前代际的窗口。许可代际单调递增，因此重开后的新窗口不会因
     * 旧请求的清理而丢失；先取许可快照再进窗口锁，保持锁序。
     */
    fun discardStale() {
        val policy = permitBoundary.currentPermit()
        synchronized(lock) {
            val current = windowPermit ?: return
            if (!policy.enabled || current.epoch < policy.epoch) discardLocked()
        }
    }

    /** 单事件体积与维度均有界，sink 在锁外执行，避免网络入队阻塞采集方 */
    private fun emit(events: List<Map<String, Any>>, permit: CollectionPermit) {
        if (!permitBoundary.isCurrent(permit)) return
        events.forEach { event ->
            try {
                sink(event, permit)
            } catch (_: Throwable) {
                // 遥测汇总绝不向朗读/生命周期线程抛出
            }
        }
    }

    private fun scheduleExpiryLocked(permit: CollectionPermit) {
        timer?.cancel()
        val generation = ++timerGeneration
        timer = scheduler.schedule(WINDOW_MS) { onWindowExpired(generation, permit) }
    }

    private fun onWindowExpired(generation: Long, permit: CollectionPermit) {
        try {
            var drained: Drained? = null
            permitBoundary.commitIfCurrent(permit) {
                synchronized(lock) {
                    if (generation == timerGeneration && windowPermit == permit) {
                        drained = drainLocked()
                    }
                }
            }
            drained?.let { emit(it.events, it.permit) }
        } catch (_: Throwable) {
            // 调度线程异常不得终止后续窗口任务或影响业务；只清理由本任务负责的窗口
            synchronized(lock) {
                if (generation == timerGeneration) discardLocked()
            }
        }
    }

    private fun drainLocked(): Drained? {
        val permit = windowPermit ?: return null
        cancelTimerLocked()
        if (buckets.isEmpty()) {
            resetWindowLocked()
            return null
        }
        val events = ArrayList<Map<String, Any>>(buckets.size)
        val startSec = windowStartWallMs / 1000
        // 墙上时钟可能被用户调整；只作为报表标签，回拨时钳制避免 end < start
        val endSec = maxOf(
            startSec,
            (if (lastRecordWallMs > 0) lastRecordWallMs else windowStartWallMs) / 1000,
        )
        for ((key, bucket) in buckets) {
            val event = linkedMapOf<String, Any>(
                "provider_id" to key.providerId,
                "model_category" to key.modelCategory,
                "window_start_s" to startSec,
                "window_end_s" to endSec,
                "request_count" to bucket.requestCount,
                "success_count" to bucket.successCount,
                "error_count" to bucket.errorCount,
                "timeout_count" to bucket.timeoutCount,
                "cancelled_count" to bucket.cancelledCount,
                "ignored_count" to bucket.ignoredCount,
                "has_audio_count" to bucket.hasAudioCount,
                "provider_completed_count" to bucket.providerCompletedCount,
                "text_chars" to cap(bucket.textChars),
                "audio_bytes" to cap(bucket.audioBytesTotal),
                "prepare_ms_total" to cap(bucket.prepareMsTotal),
                "synthesis_ms_total" to cap(bucket.synthesisMsTotal),
                "first_audio_missing" to bucket.firstAudioMissing,
            )
            DURATION_BUCKETS.forEachIndexed { index, name ->
                event["duration_$name"] = bucket.durationBuckets[index]
            }
            FIRST_AUDIO_BUCKETS.forEachIndexed { index, name ->
                event["first_audio_$name"] = bucket.firstAudioBuckets[index]
            }
            if (overflowed) event["has_other_dimensions"] = 1
            events.add(event)
        }
        resetWindowLocked()
        return Drained(events, permit)
    }

    private fun cancelTimerLocked() {
        timer?.cancel()
        timer = null
        timerGeneration++
    }

    private fun discardLocked() {
        cancelTimerLocked()
        resetWindowLocked()
    }

    private fun resetWindowLocked() {
        buckets.clear()
        windowPermit = null
        windowStartWallMs = 0
        lastRecordWallMs = 0
        totalRequests = 0
        overflowed = false
    }

    private fun keyOf(providerId: String, modelCategory: String): Key {
        val key = Key(
            providerId.take(64).ifBlank { "unknown" },
            modelCategory.take(16).ifBlank { "unknown" },
        )
        if (buckets.containsKey(key) || buckets.size < MAX_BUCKETS) return key
        overflowed = true
        return Key("other", "other")
    }

    private fun cap(value: Long): Long = value.coerceIn(0, Int.MAX_VALUE.toLong())

    private fun durationBucketIndex(ms: Long): Int = when {
        ms < 1_000 -> 0
        ms < 3_000 -> 1
        ms < 10_000 -> 2
        ms < 30_000 -> 3
        else -> 4
    }

    private fun firstAudioBucketIndex(ms: Long): Int = when {
        ms < 500 -> 0
        ms < 1_000 -> 1
        ms < 3_000 -> 2
        else -> 3
    }

    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L
        const val MAX_REQUESTS = 500
        const val MAX_BUCKETS = 32

        val DURATION_BUCKETS = listOf("lt_1s", "1_3s", "3_10s", "10_30s", "gt_30s")
        val FIRST_AUDIO_BUCKETS = listOf("lt_500ms", "500ms_1s", "1_3s", "gt_3s")
    }
}
