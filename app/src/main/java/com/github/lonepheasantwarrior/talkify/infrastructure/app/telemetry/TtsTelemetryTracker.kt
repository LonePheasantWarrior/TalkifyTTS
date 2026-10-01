package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.os.SystemClock
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TtsTelemetryTracker.begin

/**
 * TTS 语音合成事件埋点（语义层）
 *
 * 集中定义语音合成事件的事件名、属性 schema 与结果状态词表，使埋点定义收敛在遥测模块内，
 * 业务层（TalkifyTtsService）无需知晓埋点细节。
 *
 * **完成后上报模式**：合成开始前通过 [begin] 创建 [Attempt] 计时器，在合成的各类出口
 * （成功/错误/超时/取消）调用对应的 `mark*()` 标记终态，最终在 service 的 `finally`
 * 统一出口调用 [Attempt.report]，保证每次合成恰上报一条且不遗漏任何出口。
 *
 * 每次语音合成都上报一次（无限频、无采样），通过 [TalkifyTelemetry] 统一上报。
 *
 * @see TalkifyTelemetry
 */
object TtsTelemetryTracker {

    /** 合成成功 */
    const val STATUS_SUCCESS = "success"

    /** 合成失败（供应商错误或异常中断） */
    const val STATUS_ERROR = "error"

    /** 合成超时（120s 上限） */
    const val STATUS_TIMEOUT = "timeout"

    /** 用户取消 */
    const val STATUS_CANCELLED = "cancelled"

    private const val DEFAULT_VALUE = "default"

    /**
     * 创建一次合成尝试的计时器
     *
     * 应在语音合成主作业开始前调用。上报链路全异步，不会阻塞合成流程。
     *
     * @param providerId 供应商 ID（如 "aliyunBailian", "volcengine"）
     * @param modelId    有效模型 ID（已解析默认值，可能为空字符串）
     * @param voiceId    音色 ID（可能为空字符串，表示默认音色）
     * @param textLength 待合成文字字数
     */
    fun begin(providerId: String, modelId: String, voiceId: String, textLength: Int): Attempt =
        Attempt(providerId, modelId, voiceId, textLength)

    /**
     * 一次语音合成尝试的计时器
     *
     * [markFirstAudio] 由 provider 回调线程调用，其余方法均在合成主作业线程调用，
     * 首包字段跨线程读取，用 @Volatile 保证可见性。
     */
    class Attempt internal constructor(
        private val providerId: String,
        private val modelId: String,
        private val voiceId: String,
        private val textLength: Int,
    ) {
        private val startUptimeMs = SystemClock.elapsedRealtime()

        @Volatile
        private var firstAudioUptimeMs = 0L

        @Volatile
        private var firstAudioSampleRate = 0

        private var status: String? = null
        private var errorCode: String? = null
        private var endUptimeMs = 0L

        /** 记录首包音频到达（仅首次有效） */
        fun markFirstAudio(sampleRate: Int) {
            if (firstAudioUptimeMs == 0L) {
                firstAudioUptimeMs = SystemClock.elapsedRealtime()
                firstAudioSampleRate = sampleRate
            }
        }

        fun markSuccess() = mark(STATUS_SUCCESS)

        fun markTimeout() = mark(STATUS_TIMEOUT)

        fun markCancelled() = mark(STATUS_CANCELLED)

        /** 记录失败，[code] 为 TtsErrorCode 数值或异常类名 */
        fun markError(code: String) {
            errorCode = code
            mark(STATUS_ERROR)
        }

        /**
         * 上报本次合成事件（未标记终态时静默跳过）
         *
         * 应在合成主作业的 finally 统一出口调用
         */
        fun report() {
            val finalStatus = status ?: return
            val durationMs = (endUptimeMs - startUptimeMs).toInt()

            val properties = linkedMapOf<String, Any>(
                "provider_id" to providerId,
                "model_id" to modelId.ifBlank { DEFAULT_VALUE },
                "voice_id" to voiceId.ifBlank { DEFAULT_VALUE },
                "text_length" to textLength,
                "status" to finalStatus,
                "duration_ms" to durationMs,
                "duration_bucket" to durationBucket(durationMs),
            )
            errorCode?.let { properties["error_code"] = it }
            if (firstAudioUptimeMs > 0L) {
                properties["first_audio_ms"] = (firstAudioUptimeMs - startUptimeMs).toInt()
                properties["sample_rate"] = firstAudioSampleRate
            }
            TalkifyTelemetry.trackEvent("tts_synthesis", properties)
        }

        private fun mark(finalStatus: String) {
            status = finalStatus
            endUptimeMs = SystemClock.elapsedRealtime()
        }

        private fun durationBucket(durationMs: Int): String = when {
            durationMs < 1_000 -> "<1s"
            durationMs < 3_000 -> "1-3s"
            durationMs < 10_000 -> "3-10s"
            durationMs < 30_000 -> "10-30s"
            else -> ">30s"
        }
    }
}
