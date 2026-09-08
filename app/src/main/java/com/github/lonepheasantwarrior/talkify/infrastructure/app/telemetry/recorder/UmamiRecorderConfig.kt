package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.UmamiClient
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * recorder 服务端配置（对齐 recorder.js 的配置探测）
 *
 * 探测 `GET /api/websites/{id}/recorder`，客户端一切以服务端响应为准：
 * 未启用 / 探测失败时整场静默不录；回放与热图各自独立采样。
 *
 * maskLevel/blockSelector 为网页 DOM 专用配置，安卓语义树方案不使用。
 * 默认采样率 0.15、时长上限 300s（与 recorder.js 缺省一致），仅在响应
 * 携带对应数值字段时覆盖
 */
internal data class UmamiRecorderConfig(
    val enabled: Boolean,
    val replayEnabled: Boolean,
    val heatmapEnabled: Boolean,
    val sampleRate: Double,
    val heatmapSampleRate: Double,
    val maxDurationMs: Long,
) {
    companion object {
        private const val TAG = "TalkifyTelemetry"

        /** recorder.js 缺省采样率 */
        const val DEFAULT_SAMPLE_RATE = 0.15

        /** recorder.js 缺省录制时长上限 */
        const val DEFAULT_MAX_DURATION_MS = 300_000L

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 同步探测配置（网络调用，必须在 IO 线程调用）
         *
         * 任何失败返回 null（调用方静默放弃本场录制，对齐 recorder.js 的 catch-return）
         */
        fun fetch(): UmamiRecorderConfig? {
            return try {
                val request = Request.Builder()
                    .url("${UmamiClient.BASE_URL}/api/websites/${UmamiClient.WEBSITE_ID}/recorder")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        TtsLogger.w(TAG) { "recorder 配置探测失败: HTTP ${response.code}" }
                        return null
                    }
                    val body = response.body?.string() ?: return null
                    parse(body)
                }
            } catch (e: Exception) {
                TtsLogger.w(TAG) { "recorder 配置探测异常: ${e.message}" }
                null
            }
        }

        private fun parse(body: String): UmamiRecorderConfig? = try {
            val json = JSONObject(body)
            if (!json.optBoolean("enabled", false)) {
                null
            } else {
                UmamiRecorderConfig(
                    enabled = true,
                    replayEnabled = json.optBoolean("replayEnabled", false),
                    heatmapEnabled = json.optBoolean("heatmapEnabled", false),
                    sampleRate = json.maybeDouble("sampleRate") ?: DEFAULT_SAMPLE_RATE,
                    heatmapSampleRate = json.maybeDouble("heatmapSampleRate") ?: DEFAULT_SAMPLE_RATE,
                    maxDurationMs = if (json.has("maxDuration") && !json.isNull("maxDuration")) {
                        json.optLong("maxDuration", DEFAULT_MAX_DURATION_MS)
                    } else {
                        DEFAULT_MAX_DURATION_MS
                    },
                )
            }
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "recorder 配置解析失败: ${e.message}" }
            null
        }

        /** optDouble 缺省返回 NaN，此处转为 null 以区分"未携带"与 0 值 */
        private fun JSONObject.maybeDouble(name: String): Double? {
            if (isNull(name)) return null
            val value = optDouble(name, Double.NaN)
            return value.takeUnless { it.isNaN() }
        }
    }
}
