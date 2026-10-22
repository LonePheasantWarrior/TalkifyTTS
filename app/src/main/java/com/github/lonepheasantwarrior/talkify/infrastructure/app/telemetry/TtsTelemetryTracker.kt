package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * TTS 请求遥测（语义层）
 *
 * 从**请求入口**建立计时器，按 `validation → preparation → synthesis → framework_delivery`
 * 记录阶段；终态区分供应商完成、框架交付成功、取消、超时、准备失败与框架拒收。
 *
 * 口径定义：
 * - `success` 仅表示「供应商完成且音频已成功交付给系统 TTS 框架、done() 成功」
 * - `error` 表示准备/合成/框架交付任一阶段失败；`failure_stage` 指出阶段
 * - `ignored` 表示空文本等未进入有效合成的请求，单独计数，不计入请求成功率分母
 * - 取消率单列；对外只能称「框架交付成功率」，不能推导为「用户完整听到音频的比例」
 *
 * 高频明细：失败（含取消/超时）全量保留；成功按请求产生前的统一概率抽样（默认 5%）。
 * 全量统计由 [TtsMetricsAggregator] 的窗口汇总承担，不按结果分别抽样。
 */
object TtsTelemetryTracker {

    const val EVENT_TTS_REQUEST_RESULT = "tts_request_result"
    const val EVENT_TTS_METRICS_WINDOW = "tts_metrics_window"

    const val STATUS_SUCCESS = "success"
    const val STATUS_ERROR = "error"
    const val STATUS_TIMEOUT = "timeout"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_IGNORED = "ignored"

    const val STAGE_VALIDATION = "validation"
    const val STAGE_PREPARATION = "preparation"
    const val STAGE_SYNTHESIS = "synthesis"
    const val STAGE_FRAMEWORK_DELIVERY = "framework_delivery"

    private const val SUCCESS_DETAIL_SAMPLE_RATE = 0.05

    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    internal var successSample: () -> Boolean = { Random.nextDouble() < SUCCESS_DETAIL_SAMPLE_RATE }

    internal var trackingAllowed: () -> Boolean = { TelemetryController.isTelemetryEnabled() }

    /** 创建 attempt/窗口时捕获许可；测试可替换 */
    internal var permitProvider: () -> CollectionPermit = { TelemetryController.capturePermit() }

    /** 提交 attempt/窗口前复核许可；测试可替换 */
    internal var permitValidator: (CollectionPermit) -> Boolean = {
        TelemetryController.isPermitCurrent(it)
    }

    internal var eventReporter: (Map<String, Any>, CollectionPermit) -> Unit = { properties, permit ->
        TalkifyTelemetry.trackEvent(
            EVENT_TTS_REQUEST_RESULT,
            properties,
            ScreenTracker.PATH_TTS_SERVICE,
            TelemetryOrigin.TTS_SERVICE,
            permit,
        )
    }

    internal var metricsAggregator: TtsMetricsAggregator = newAggregator()

    private fun newAggregator(): TtsMetricsAggregator = TtsMetricsAggregator(
        sink = { properties, permit ->
            TalkifyTelemetry.trackEvent(
                EVENT_TTS_METRICS_WINDOW,
                properties,
                ScreenTracker.PATH_TTS_SERVICE,
                TelemetryOrigin.TTS_SERVICE,
                permit,
            )
        },
    )

    fun begin(providerId: String, textLength: Int): Attempt =
        Attempt(TelemetryIds.provider(providerId), textLength, clock(), permitProvider())

    fun flushMetrics() {
        try {
            metricsAggregator.flush()
        } catch (_: Throwable) {
            // 生命周期冲刷失败不影响前后台切换
        }
    }

    fun clearMetrics() {
        metricsAggregator.discardStale()
    }

    class Attempt internal constructor(
        private val providerId: String,
        private var textLength: Int,
        private val startUptimeMs: Long,
        private val permit: CollectionPermit,
    ) {
        private enum class Terminal { SUCCESS, ERROR, TIMEOUT, CANCELLED, IGNORED }

        private data class FirstAudio(val elapsedMs: Long, val sampleRate: Int)

        private val lock = Any()
        private val reported = AtomicBoolean(false)
        private val sampledDetail = successSample()

        @Volatile
        private var terminal: Terminal? = null

        @Volatile
        private var endUptimeMs = 0L

        @Volatile
        private var stage: String = STAGE_VALIDATION

        @Volatile
        private var errorCode: String? = null

        @Volatile
        private var failureStage: String? = null

        @Volatile
        private var synthesisStartedMs: Long? = null

        @Volatile
        private var providerCompleted = false

        @Volatile
        private var hasAudio = false

        @Volatile
        private var audioBytes = 0L

        @Volatile
        private var firstAudio: FirstAudio? = null

        @Volatile
        private var frameworkFailure: String? = null

        private var modelId: TelemetryIds.ClassifiedId =
            TelemetryIds.ClassifiedId(TelemetryIds.CATEGORY_UNKNOWN, TelemetryIds.CATEGORY_UNKNOWN)
        private var voiceId: TelemetryIds.ClassifiedId =
            TelemetryIds.ClassifiedId(TelemetryIds.CATEGORY_UNKNOWN, TelemetryIds.CATEGORY_UNKNOWN)

        fun updateTextLength(length: Int) {
            textLength = length.coerceAtLeast(0)
        }

        /** 由供应商/配置解析出的实际生效元信息（已按隐私策略分类） */
        fun updateMetadata(model: TelemetryIds.ClassifiedId, voice: TelemetryIds.ClassifiedId) {
            modelId = model
            voiceId = voice
        }

        /** 基础校验通过，进入供应商/配置/锁/前台状态等准备阶段 */
        fun markPreparationStarted() {
            if (stage == STAGE_VALIDATION) stage = STAGE_PREPARATION
        }

        /** 所有前置准备（供应商/配置/参数）完成后、真正调用供应商前调用 */
        fun markSynthesisStarted() {
            if (stage == STAGE_VALIDATION || stage == STAGE_PREPARATION) {
                stage = STAGE_SYNTHESIS
                synthesisStartedMs = clock()
            }
        }

        fun markProviderCompleted() {
            providerCompleted = true
        }

        fun markFirstAudio(sampleRate: Int) {
            if (firstAudio != null) return
            synchronized(lock) {
                if (firstAudio == null) {
                    firstAudio = FirstAudio(clock() - startUptimeMs, sampleRate)
                }
            }
        }

        /** 记录已成功交给系统框架的音频字节；返回 false 的写入不要调用 */
        fun recordAudioDelivered(bytes: Int) {
            if (bytes <= 0) return
            hasAudio = true
            audioBytes += bytes
        }

        /** 系统框架 start/audioAvailable/done 返回非 SUCCESS：最终按框架交付失败归类 */
        fun markFrameworkFailure(reason: String) {
            if (frameworkFailure == null) frameworkFailure = reason.take(48).ifBlank { "framework_error" }
        }

        fun markSuccess() {
            synchronized(lock) {
                if (terminal != null) return
                val framework = frameworkFailure
                if (framework != null) {
                    terminal = Terminal.ERROR
                    failureStage = STAGE_FRAMEWORK_DELIVERY
                    errorCode = framework
                } else if (!providerCompleted) {
                    terminal = Terminal.ERROR
                    failureStage = STAGE_SYNTHESIS
                    errorCode = "provider_not_completed"
                } else if (!hasAudio) {
                    terminal = Terminal.ERROR
                    failureStage = STAGE_SYNTHESIS
                    errorCode = "empty_audio"
                } else {
                    terminal = Terminal.SUCCESS
                }
                endUptimeMs = clock()
            }
        }

        fun markError(code: String, failureStage: String = currentStage()) {
            synchronized(lock) {
                if (terminal != null) return
                terminal = Terminal.ERROR
                this.failureStage = failureStage
                errorCode = code.take(64).ifBlank { "unknown" }
                endUptimeMs = clock()
            }
        }

        fun markTimeout() {
            synchronized(lock) {
                if (terminal != null) return
                terminal = Terminal.TIMEOUT
                failureStage = STAGE_SYNTHESIS
                errorCode = "request_timeout"
                endUptimeMs = clock()
            }
        }

        fun markCancelled(reason: String = "user_stopped") {
            synchronized(lock) {
                if (terminal != null) return
                terminal = Terminal.CANCELLED
                failureStage = currentStage()
                errorCode = reason.take(64).ifBlank { "cancelled" }
                endUptimeMs = clock()
            }
        }

        fun markIgnored(reason: String) {
            synchronized(lock) {
                if (terminal != null) return
                terminal = Terminal.IGNORED
                failureStage = STAGE_VALIDATION
                errorCode = reason.take(64).ifBlank { "ignored" }
                endUptimeMs = clock()
            }
        }

        fun currentStage(): String = stage

        fun hasAudio(): Boolean = hasAudio

        /** 统一出口：每场只消费一次；未标记终态时按异常收尾，不允许静默漏报 */
        fun report() {
            if (terminal == null) markError("no_terminal")
            if (!reported.compareAndSet(false, true)) return
            // 以创建时捕获的许可为准：关闭期间创建、或跨代际的旧 attempt 不聚合、不上送
            if (!permit.enabled || !permitValidator(permit) || !trackingAllowed()) return

            val finalTerminal = terminal ?: return
            val status = when (finalTerminal) {
                Terminal.SUCCESS -> STATUS_SUCCESS
                Terminal.ERROR -> STATUS_ERROR
                Terminal.TIMEOUT -> STATUS_TIMEOUT
                Terminal.CANCELLED -> STATUS_CANCELLED
                Terminal.IGNORED -> STATUS_IGNORED
            }
            val durationMs = (endUptimeMs - startUptimeMs).coerceAtLeast(0)
            val first = firstAudio
            val summary = TtsMetricsAggregator.Summary(
                providerId = providerId,
                modelCategory = modelId.category,
                status = status,
                hasAudio = hasAudio,
                providerCompleted = providerCompleted,
                textLength = textLength,
                durationMs = durationMs,
                prepareMs = synthesisStartedMs?.minus(startUptimeMs),
                synthesisMs = synthesisStartedMs?.let { endUptimeMs - it },
                firstAudioMs = first?.elapsedMs,
                audioBytes = audioBytes,
            )
            try {
                metricsAggregator.record(summary, permit)
            } catch (_: Throwable) {
                // 汇总失败不影响朗读主流程
            }

            if (status == STATUS_IGNORED) return
            if (status == STATUS_SUCCESS && !sampledDetail) return

            val properties = linkedMapOf<String, Any>(
                "provider_id" to providerId,
                "model_id" to modelId.value,
                "model_category" to modelId.category,
                "voice_id" to voiceId.value,
                "voice_category" to voiceId.category,
                "text_length" to textLength,
                "status" to status,
                "duration_ms" to durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                "duration_bucket" to durationBucket(durationMs),
                "has_audio" to if (hasAudio) 1 else 0,
                "audio_bytes" to audioBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                "provider_completed" to if (providerCompleted) 1 else 0,
                "sampled" to if (status == STATUS_SUCCESS) 1 else 0,
            )
            if (status != STATUS_SUCCESS) {
                properties["failure_stage"] = failureStage ?: currentStage()
            }
            errorCode?.let { properties["error_code"] = it }
            synthesisStartedMs?.let { properties["prepare_ms"] = (it - startUptimeMs).toInt() }
            synthesisStartedMs?.let { properties["synthesis_ms"] = (endUptimeMs - it).toInt() }
            first?.let {
                properties["first_audio_ms"] = it.elapsedMs.toInt()
                properties["sample_rate"] = it.sampleRate
            }
            try {
                if (permitValidator(permit)) eventReporter(properties, permit)
            } catch (_: Throwable) {
                // 遥测绝不向合成线程抛出
            }
        }

        private fun durationBucket(ms: Long): String = when {
            ms < 1_000 -> "<1s"
            ms < 3_000 -> "1-3s"
            ms < 10_000 -> "3-10s"
            ms < 30_000 -> "10-30s"
            else -> ">30s"
        }
    }

    internal fun resetForTest() {
        clock = { SystemClock.elapsedRealtime() }
        successSample = { Random.nextDouble() < SUCCESS_DETAIL_SAMPLE_RATE }
        trackingAllowed = { TelemetryController.isTelemetryEnabled() }
        permitProvider = { TelemetryController.capturePermit() }
        permitValidator = { TelemetryController.isPermitCurrent(it) }
        eventReporter = { properties, permit ->
            TalkifyTelemetry.trackEvent(
                EVENT_TTS_REQUEST_RESULT,
                properties,
                ScreenTracker.PATH_TTS_SERVICE,
                TelemetryOrigin.TTS_SERVICE,
                permit,
            )
        }
        metricsAggregator = newAggregator()
    }
}
