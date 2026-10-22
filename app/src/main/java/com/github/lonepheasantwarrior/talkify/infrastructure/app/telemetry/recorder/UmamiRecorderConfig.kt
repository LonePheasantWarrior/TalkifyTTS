package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.LimitedBody
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.UmamiClient
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.readLimitedBody
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
 * 服务端约束只能进一步收紧本地许可，不能开启用户未选择的能力。无法映射到安卓
 * 语义树的 `blockSelector`（网页 CSS selector）一律拒绝录制，而不是忽略后继续。
 * 采样率与录制时长受本地硬上限约束；文本策略本地强制 `strict`（全量遮罩）。
 */
internal data class UmamiRecorderConfig(
    val enabled: Boolean,
    val replayEnabled: Boolean,
    val heatmapEnabled: Boolean,
    val sampleRate: Double,
    val heatmapSampleRate: Double,
    val maxDurationMs: Long,
    val strictMask: Boolean,
) {
    companion object {
        private const val TAG = "TalkifyTelemetry"

        const val DEFAULT_SAMPLE_RATE = 0.15
        const val DEFAULT_MAX_DURATION_MS = 300_000L
        const val MIN_MAX_DURATION_MS = 10_000L
        private const val MAX_CONFIG_RESPONSE_BYTES = 16 * 1024

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        fun fetch(onCall: (okhttp3.Call?) -> Unit = {}): UmamiRecorderConfig? {
            if (!UmamiClient.isConfigured) return null
            return try {
                val request = Request.Builder()
                    .url("${UmamiClient.BASE_URL}/api/websites/${UmamiClient.WEBSITE_ID}/recorder")
                    .header("User-Agent", UmamiClient.userAgent())
                    .get()
                    .build()
                val call = httpClient.newCall(request)
                onCall(call)
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        TtsLogger.w(TAG) { "recorder 配置探测失败: HTTP ${response.code}" }
                        return null
                    }
                    return when (val body = readLimitedBody(response, MAX_CONFIG_RESPONSE_BYTES)) {
                        is LimitedBody.Text -> parse(body.value)
                        LimitedBody.Absent, LimitedBody.TooLarge -> {
                            TtsLogger.w(TAG) { "recorder 配置响应为空或超过大小上限" }
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                TtsLogger.w(TAG) { "recorder 配置探测异常: ${e.message}" }
                null
            } finally {
                onCall(null)
            }
        }

        private fun parse(body: String): UmamiRecorderConfig? = try {
            val json = JSONObject(body)
            when {
                !json.optBoolean("enabled", false) -> null
                // 网页 CSS selector 无法映射原生控件：明确拒绝，不能忽略后继续
                json.optString("blockSelector").isNotBlank() -> {
                    TtsLogger.w(TAG) { "recorder 配置含原生无法映射的 blockSelector，拒绝录制" }
                    null
                }
                else -> {
                    val configuredDuration = if (json.has("maxDuration") && !json.isNull("maxDuration")) {
                        json.optLong("maxDuration", DEFAULT_MAX_DURATION_MS)
                    } else {
                        DEFAULT_MAX_DURATION_MS
                    }
                    // 服务端只能收紧：为 0 或低于本地可支持的最短时长时拒绝录制，
                    // 不能把 1000ms 提升成本地 10s（那会违背服务端更严格的约束）
                    if (configuredDuration < MIN_MAX_DURATION_MS) return null
                    UmamiRecorderConfig(
                        enabled = true,
                        replayEnabled = json.optBoolean("replayEnabled", false),
                        heatmapEnabled = json.optBoolean("heatmapEnabled", false),
                        sampleRate = (json.maybeDouble("sampleRate") ?: DEFAULT_SAMPLE_RATE).coerceIn(0.0, 1.0),
                        heatmapSampleRate = (json.maybeDouble("heatmapSampleRate") ?: DEFAULT_SAMPLE_RATE).coerceIn(0.0, 1.0),
                        maxDurationMs = configuredDuration.coerceAtMost(DEFAULT_MAX_DURATION_MS),
                        // 本地约束优先：原生语义树尚无默认拒绝的文本白名单，
                        // 即使服务端配置更宽松，也强制 strict（全部动态文本遮罩）
                        strictMask = true,
                    )
                }
            }
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "recorder 配置解析失败: ${e.message}" }
            null
        }

        private fun JSONObject.maybeDouble(name: String): Double? {
            if (isNull(name)) return null
            val value = optDouble(name, Double.NaN)
            return value.takeUnless { it.isNaN() }
        }
    }
}
