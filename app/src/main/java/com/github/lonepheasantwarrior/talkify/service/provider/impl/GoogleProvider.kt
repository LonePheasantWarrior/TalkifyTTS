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
import org.json.JSONObject

/**
 * Google - Gemini TTS 语音合成供应商实现
 *
 * 继承 [HttpStreamingTtsProvider]，基于 OkHttp 实现 HTTP SSE 流式音频合成。
 * 分块调度、取消、错误分类等通用逻辑由基类提供。
 *
 * 双接口规范：同一供应商下经配置项"接口规范"（[GoogleConfig.apiSpec]）分派两套实现，
 * 报文结构差异收敛于 [GoogleApiSpec]：
 * - [GoogleInteractionsApi]（默认）：`POST /v1beta/interactions`（`stream: true`）
 * - [GoogleGenerateContentApi]：`POST /v1beta/models/{model}:streamGenerateContent?alt=sse`
 *
 * 音频输出：两套规范均显式指定 headerless raw PCM 16bit 小端 24kHz 单声道
 * （与 [AudioConfig.GEMINI_TTS] 一致），与系统 TTS 管道零转换对接。
 *
 * 风格控制：`text` 为逐字稿，语体/情感/语速等持续风格通过 `speech_metadata.style`
 * 自然语言描述（对应配置项"风格指令"）；系统语速明显偏离默认时自动附加
 * 语速提示短语（见 [GoogleParamMapper.speechRateStyleHint]）。
 *
 * 网络适配：中国大陆通常无法直连 Google 服务，支持
 * - 自定义 API 地址（反向代理 / 兼容服务），留空按接口规范回退默认端点；
 *   地址语义随规范变化（interactions 为完整端点，generateContent 为 "/models" 基础前缀）
 * - HTTP / SOCKS 代理（以代理协议为开关："无"= 直连；选中协议时主机与端口必填）
 *
 * 供应商 ID：google
 * 供应商：Google
 * API 模型：gemini-3.8-flash-lite-tts
 * API 文档：doc/provider/google-tts-generateContentAPI.md（generateContent 规范）
 */
class GoogleProvider : HttpStreamingTtsProvider() {

    companion object {
        /**
         * 保留静态访问入口（TalkifyCheckDataActivity 等无需实例化即可读取）。
         * Gemini TTS 自动检测输入语言，此处声明常用的朗读语言。
         */
        val SUPPORTED_LANGUAGES = arrayOf(
            "zho", "yue", "eng", "jpn", "kor", "fra", "deu", "spa",
            "por", "ita", "rus", "ara", "hin", "tha", "vie"
        )
    }

    override val chunkMaxLength: Int = 500

    override val configLabels: Map<String, Int> = mapOf(
        "api_key" to R.string.api_key_label,
        "style_instruction" to R.string.label_style_instruction,
        "api_spec" to R.string.label_api_spec,
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

    override fun getDefaultApiUrl(): String = GoogleInteractionsApi.DEFAULT_API_URL

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
        val spec = specFor(googleConfig)
        val voiceId = resolveVoiceId(googleConfig)
        val effectiveModel = googleConfig.modelId.ifBlank { getDefaultModelId() }
        val style = buildStyle(googleConfig.styleInstruction, params.speechRate)
        if (style != null) {
            logDebug("speech_metadata.style: $style")
        }

        val endpoint = spec.buildEndpoint(googleConfig.apiUrl, effectiveModel)
        val requestBody = spec.buildRequestBody(
            model = effectiveModel,
            text = text,
            voiceId = voiceId,
            style = style,
            sampleRate = getAudioConfig().sampleRate
        )

        val body = requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
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
     * 两套规范均以 SSE 传输（generateContent 经 `?alt=sse`），事件解析路径由
     * 当前 [GoogleApiSpec] 提供；本方法只承担统一的 SSE 循环与错误出口。
     */
    override suspend fun processStreamResponse(
        response: Response,
        chunkIndex: Int,
        config: BaseProviderConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ): Boolean {
        val spec = specFor(config as GoogleConfig)
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

                        val errorMessage = spec.extractError(json)
                        if (errorMessage != null) {
                            logError("API error: $errorMessage")
                            hasError = true
                            withContext(Dispatchers.Main) {
                                listener.onError(errorMessage)
                            }
                            break
                        }

                        val audioData = spec.extractAudio(json)
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

    /** 按配置的接口规范分派报文实现；未知取值兜底默认规范（interactions） */
    private fun specFor(config: GoogleConfig): GoogleApiSpec {
        return when (config.apiSpec) {
            GoogleConfig.SPEC_GENERATE_CONTENT -> GoogleGenerateContentApi
            else -> GoogleInteractionsApi
        }
    }

    /** 解析生效音色：配置音色（剥离 Android Voice 展示后缀）> 预置列表首个 > 兜底音色 */
    private fun resolveVoiceId(config: GoogleConfig): String {
        return if (config.voiceId.isNotEmpty()) {
            extractRealVoiceName(config.voiceId) ?: config.voiceId
        } else {
            voiceIds.firstOrNull() ?: fallbackVoiceId
        }
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
