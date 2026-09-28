package com.github.lonepheasantwarrior.talkify.service.provider.impl

import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.GoogleConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.GoogleErrorParser
import com.github.lonepheasantwarrior.talkify.service.provider.GoogleParamMapper
import com.github.lonepheasantwarrior.talkify.service.provider.HttpStreamingTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.ProxyParseResult
import com.github.lonepheasantwarrior.talkify.service.provider.ProxySettings
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.service.provider.toMaskedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google - Gemini TTS 语音合成供应商实现
 *
 * 继承 [HttpStreamingTtsProvider]，基于 OkHttp 实现 HTTP SSE 流式音频合成
 * （POST /v1beta/interactions，`stream: true`）。
 * 分块调度、取消、错误分类等通用逻辑由基类提供。
 *
 * 音频输出：显式指定 `response_format.mime_type = audio/l16`，
 * 返回 headerless raw PCM 16bit 小端 24kHz 单声道，与 [AudioConfig.GEMINI_TTS] 一致。
 *
 * 风格控制：`text` 为逐字稿，语体/情感/语速等持续风格通过 `speech_metadata.style`
 * 自然语言描述（对应配置项"风格指令"）；系统语速明显偏离默认时自动附加
 * 语速提示短语（见 [GoogleParamMapper.speechRateStyleHint]）。
 *
 * 网络适配：中国大陆通常无法直连 Google 服务，支持
 * - 自定义 API 地址（反向代理 / 兼容服务），留空自动回退默认端点
 * - HTTP / SOCKS 代理（以代理协议为开关："无"= 直连；选中协议时主机与端口必填）
 *
 * 供应商 ID：google
 * 供应商：Google
 * API 模型：gemini-3.8-flash-lite-tts
 * API 文档：doc/gemini-tts.md（Text-to-speech generation）
 */
class GoogleProvider : HttpStreamingTtsProvider() {

    companion object {
        const val DEFAULT_API_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"

        /**
         * 保留静态访问入口（TalkifyCheckDataActivity 等无需实例化即可读取）。
         * Gemini TTS 自动检测输入语言，此处声明常用的朗读语言。
         */
        val SUPPORTED_LANGUAGES = arrayOf(
            "zho", "yue", "eng", "jpn", "kor", "fra", "deu", "spa",
            "por", "ita", "rus", "ara", "hin", "tha", "vie"
        )

        /** 流式响应默认音频格式：audio/l16（headerless raw PCM），24kHz */
        private const val MIME_TYPE_RAW_PCM = "audio/l16"
    }

    override val chunkMaxLength: Int = 500

    override val configLabels: Map<String, Int> = mapOf(
        "api_key" to R.string.api_key_label,
        "style_instruction" to R.string.label_style_instruction,
        "proxy_protocol" to R.string.label_proxy_protocol,
        "proxy_host" to R.string.label_proxy_host,
        "proxy_port" to R.string.label_proxy_port
    )

    override val supportedLanguages: Array<String>
        get() = SUPPORTED_LANGUAGES

    override val fallbackVoiceId: String = "Kore"

    override val voiceIds: List<String> by lazy {
        loadVoiceIdsFromXml(R.xml.google_gemini_tts_voices)
    }

    override fun getProviderId(): String = ProviderIds.Google.providerId

    override fun getProviderName(): String = ProviderIds.Google.provider

    override fun getDefaultApiUrl(): String = DEFAULT_API_URL

    override fun getDefaultModelId(): String = ProviderIds.Google.defaultModelId

    override fun getAudioConfig(): AudioConfig = AudioConfig.GEMINI_TTS

    override fun validateConfig(config: BaseProviderConfig): String? {
        if (config !is GoogleConfig) {
            return TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED)
        }
        if (config.apiKey.isBlank()) {
            return TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED)
        }
        // 代理配置不完整时在合成入口即给出明确提示，而非等待网络错误
        val proxyResult = ProxySettings.parse(config.proxyProtocol, config.proxyHost, config.proxyPort)
        if (proxyResult is ProxyParseResult.Invalid) {
            return proxyResult.reason
        }
        return null
    }

    override fun httpClientFor(config: BaseProviderConfig): OkHttpClient {
        val googleConfig = config as? GoogleConfig ?: return super.httpClientFor(config)
        val proxyResult = ProxySettings.parse(googleConfig.proxyProtocol, googleConfig.proxyHost, googleConfig.proxyPort)
        val setting = (proxyResult as? ProxyParseResult.Valid)?.setting
            ?: return super.httpClientFor(config)
        return proxiedHttpClient(setting)
    }

    override fun buildHttpRequest(
        text: String,
        config: BaseProviderConfig,
        params: SynthesisParams
    ): Request {
        val googleConfig = config as GoogleConfig
        val voiceId = if (googleConfig.voiceId.isNotEmpty()) {
            extractRealVoiceName(googleConfig.voiceId) ?: googleConfig.voiceId
        } else {
            voiceIds.firstOrNull() ?: fallbackVoiceId
        }

        val effectiveApiUrl = googleConfig.apiUrl.ifBlank { DEFAULT_API_URL }
        val effectiveModel = googleConfig.modelId.ifBlank { getDefaultModelId() }
        val style = buildStyle(googleConfig.styleInstruction, params.speechRate)
        if (style != null) {
            logDebug("speech_metadata.style: $style")
        }

        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", text)
            if (style != null) {
                put(
                    "annotations",
                    JSONArray().put(
                        JSONObject().apply {
                            put("type", "speech_metadata")
                            put("style", style)
                        }
                    )
                )
            }
        }

        val requestBody = JSONObject().apply {
            put("model", effectiveModel)
            put(
                "input",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "user_input")
                        put("content", JSONArray().put(textContent))
                    }
                )
            )
            put(
                "response_format",
                JSONObject().apply {
                    put("type", "audio")
                    put("mime_type", MIME_TYPE_RAW_PCM)
                    put("sample_rate", getAudioConfig().sampleRate)
                }
            )
            put(
                "generation_config",
                JSONObject().apply {
                    put(
                        "speech_config",
                        JSONArray().put(JSONObject().put("voice", voiceId))
                    )
                }
            )
            put("stream", true)
        }

        val body = requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(effectiveApiUrl)
            .post(body)
            .header("x-goog-api-key", googleConfig.apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .build()

        // 打印请求详情（Headers 脱敏处理仅用于日志显示，实际发送的是原始值）
        logDebug("HTTP Request URL: ${request.url}")
        logDebug("HTTP Request Headers (masked for log): ${request.headers.toMaskedString()}")
        logDebug("HTTP Request Body: ${requestBody.toString(2)}")

        return request
    }

    /**
     * 处理流式响应（SSE 格式：`data: {...}` 行）
     *
     * 音频增量事件的文档化形态：
     * `{"event_type": "step.delta", "delta": {"type": "audio", "data": "<base64>"}}`
     */
    override suspend fun processStreamResponse(
        response: Response,
        chunkIndex: Int,
        config: BaseProviderConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ): Boolean {
        val body = response.body
        if (body == null) {
            logError("Response body is null")
            return false
        }

        var hasError = false

        try {
            body.source().use { source ->
                while (!source.exhausted() && !isCancelled) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue

                    // SSE 格式: data: {...}（event:/id:/注释行忽略）
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue

                    try {
                        val json = JSONObject(data)

                        val errorMessage = GeminiStreamEvents.extractError(json)
                        if (errorMessage != null) {
                            logError("API error: $errorMessage")
                            hasError = true
                            withContext(Dispatchers.Main) {
                                listener.onError(errorMessage)
                            }
                            break
                        }

                        val audioData = GeminiStreamEvents.extractAudio(json)
                        if (audioData != null && audioData.isNotEmpty()) {
                            emitAudio(audioData, listener)
                            logDebug("Received audio data: ${audioData.size} bytes")
                        }
                    } catch (e: Exception) {
                        logError("Failed to parse SSE data: $data", e)
                        // 继续处理下一行，不中断
                    }
                }
            }
        } catch (e: Exception) {
            logError("Error reading response stream", e)
            hasError = true
        }

        return !hasError
    }

    override fun mapHttpError(errorBody: String): String {
        return GoogleErrorParser.parse(errorBody)
    }

    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        return isConfiguredAs(config) { c: GoogleConfig -> c.apiKey.isNotBlank() }
    }

    override fun createDefaultConfig(): BaseProviderConfig {
        return GoogleConfig()
    }

    /**
     * 组装 speech_metadata.style：用户风格指令 + 系统语速提示。
     * 两者皆缺省时返回 null（不携带 style 注解，由模型按默认风格朗读）。
     */
    private fun buildStyle(styleInstruction: String, speechRate: Float): String? {
        val userStyle = styleInstruction.trim()
        val rateHint = GoogleParamMapper.speechRateStyleHint(speechRate)
        return listOfNotNull(userStyle.ifBlank { null }, rateHint)
            .joinToString(", ")
            .ifBlank { null }
    }
}

/**
 * Gemini 流式响应事件提取（纯函数，便于单元测试）
 */
internal object GeminiStreamEvents {

    /**
     * 从 SSE 事件 JSON 中提取音频数据。
     *
     * 识别 `delta` 为 `{"type": "audio", "data": "<base64>"}` 的增量事件；
     * 非音频事件或无法解析时返回 null。
     */
    fun extractAudio(json: JSONObject): ByteArray? {
        val delta = json.optJSONObject("delta") ?: return null
        if (delta.optString("type") != "audio") return null
        val data = delta.optString("data")
        if (data.isBlank()) return null
        return try {
            // MIME 解码器对换行等空白容错，避免分块 base64 偶发换行导致失败
            java.util.Base64.getMimeDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 从 SSE 事件 JSON 中提取错误消息；非错误事件返回 null。
     *
     * 覆盖两类形态：
     * - 结构化错误：`{"error": {"message": "...", "status": "..."}}`
     * - 错误事件：`{"event_type": "...error...", "message": "..."}`
     */
    fun extractError(json: JSONObject): String? {
        json.optJSONObject("error")?.let { error ->
            val message = error.optString("message", "")
            val status = error.optString("status", "")
            return when {
                message.isNotBlank() -> message
                status.isNotBlank() -> "语音合成失败: $status"
                else -> "语音合成失败"
            }
        }

        val eventType = json.optString("event_type", "")
        if (eventType.isNotBlank() && eventType.contains("error", ignoreCase = true)) {
            val message = json.optString("message", "")
            return message.ifBlank { "语音合成失败: $eventType" }
        }
        return null
    }
}
