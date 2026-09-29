package com.github.lonepheasantwarrior.talkify.service.provider.impl

import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.OpenAIConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.HttpStreamingTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.OpenAIErrorParser
import com.github.lonepheasantwarrior.talkify.service.provider.OpenAIParamMapper
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
 * OpenAI 语音合成供应商实现
 *
 * 继承 [HttpStreamingTtsProvider]，基于 OkHttp 调用 OpenAI
 * `POST /v1/audio/speech` 接口。分块调度、取消、错误分类等通用逻辑由基类提供。
 *
 * 音频输出：显式指定 `response_format = pcm`（headerless raw PCM 16bit
 * 小端 24kHz 单声道，与 [AudioConfig.OPENAI_TTS] 一致），响应体为纯音频
 * 字节流（chunk transfer encoding），无需解码即可对接系统 TTS 管道，
 * 也是 OpenAI 文档推荐的首包延迟最优格式。
 *
 * 风格控制：gpt-4o-mini-tts 不支持数值化 speed 参数，语体/情感/语速等
 * 持续风格经 `instructions` 自然语言描述（对应配置项"风格指令"）；
 * 系统语速明显偏离默认时自动附加语速提示短语
 * （见 [OpenAIParamMapper.speechRateStyleHint]）。
 *
 * 兼容性：大量第三方转接平台遵循 OpenAI TTS 规范，支持
 * - 自定义 API 地址（完整端点），留空自动回退官方默认端点
 * - 自定义模型 ID，留空自动回退 gpt-4o-mini-tts
 * - HTTP / SOCKS 代理（以代理协议为开关："无"= 直连；选中协议时主机与端口必填）
 *
 * 供应商 ID：openAI
 * 供应商：OpenAI
 * API 模型：gpt-4o-mini-tts
 * API 文档：doc/provider/openAI-tts.md（Text to speech）
 */
class OpenAIProvider : HttpStreamingTtsProvider() {

    companion object {
        const val DEFAULT_API_URL = "https://api.openai.com/v1/audio/speech"

        /**
         * 保留静态访问入口（TalkifyCheckDataActivity 等无需实例化即可读取）。
         * OpenAI TTS 语言支持跟随 Whisper 模型，此处声明常用的朗读语言。
         */
        val SUPPORTED_LANGUAGES = arrayOf(
            "zho", "yue", "eng", "jpn", "kor", "fra", "deu", "spa",
            "por", "ita", "rus", "ara", "hin", "tha", "vie"
        )

        /** 音色 ID 列表为空时的兜底音色（官方文档默认音色，全模型可用） */
        const val FALLBACK_VOICE_ID = "alloy"

        /** 响应体读取缓冲区大小（raw PCM 流按块读取） */
        private const val READ_BUFFER_SIZE_BYTES = 8 * 1024

        /** 流式进度日志的累计字节间隔 */
        private const val LOG_PROGRESS_INTERVAL_BYTES = 64L * 1024
    }

    /**
     * 单块文本最大字符数。
     * 官方 input 上限为 4096 字符，取较小分块以获得更细的流水线
     * 并行度与取消粒度，同时留足第三方平台的限额余量
     */
    override val chunkMaxLength: Int = 1000

    override val configLabels: Map<String, Int> = mapOf(
        "api_key" to R.string.api_key_label,
        "style_instruction" to R.string.label_style_instruction,
        "proxy_protocol" to R.string.label_proxy_protocol,
        "proxy_host" to R.string.label_proxy_host,
        "proxy_port" to R.string.label_proxy_port
    )

    override val supportedLanguages: Array<String>
        get() = SUPPORTED_LANGUAGES

    override val fallbackVoiceId: String = FALLBACK_VOICE_ID

    override val voiceIds: List<String> by lazy {
        loadVoiceIdsFromXml(R.xml.openai_tts_voices)
    }

    override fun getProviderId(): String = ProviderIds.OpenAI.providerId

    override fun getProviderName(): String = ProviderIds.OpenAI.provider

    override fun getDefaultApiUrl(): String = DEFAULT_API_URL

    override fun getDefaultModelId(): String = ProviderIds.OpenAI.defaultModelId

    override fun getAudioConfig(): AudioConfig = AudioConfig.OPENAI_TTS

    override fun validateConfig(config: BaseProviderConfig): String? {
        if (config !is OpenAIConfig) {
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
        val openaiConfig = config as? OpenAIConfig ?: return super.httpClientFor(config)
        val proxyResult = ProxySettings.parse(openaiConfig.proxyProtocol, openaiConfig.proxyHost, openaiConfig.proxyPort)
        val setting = (proxyResult as? ProxyParseResult.Valid)?.setting
            ?: return super.httpClientFor(config)
        return proxiedHttpClient(setting)
    }

    override fun buildHttpRequest(
        text: String,
        config: BaseProviderConfig,
        params: SynthesisParams
    ): Request {
        val openaiConfig = config as OpenAIConfig
        val voiceId = if (openaiConfig.voiceId.isNotEmpty()) {
            extractRealVoiceName(openaiConfig.voiceId) ?: openaiConfig.voiceId
        } else {
            voiceIds.firstOrNull() ?: fallbackVoiceId
        }

        val effectiveApiUrl = openaiConfig.apiUrl.ifBlank { DEFAULT_API_URL }
        val effectiveModel = openaiConfig.modelId.ifBlank { getDefaultModelId() }
        val instructions = buildInstructions(openaiConfig.styleInstruction, params.speechRate)
        if (instructions != null) {
            logDebug("instructions: $instructions")
        }

        val requestBody = OpenAIRequestBuilder.build(effectiveModel, text, voiceId, instructions)

        val body = requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(effectiveApiUrl)
            .post(body)
            .header("Authorization", "Bearer ${openaiConfig.apiKey}")
            .header("Content-Type", "application/json")
            .build()

        // 打印请求详情（Headers 脱敏处理仅用于日志显示，实际发送的是原始值）
        logDebug("HTTP Request URL: ${request.url}")
        logDebug("HTTP Request Headers (masked for log): ${request.headers.toMaskedString()}")
        logDebug("HTTP Request Body: ${requestBody.toString(2)}")

        return request
    }

    /**
     * 处理流式响应（`response_format=pcm` 返回纯音频字节流，非 SSE）
     *
     * 服务端经 chunk transfer encoding 逐步推送 PCM 字节，
     * 直接经 [emitAudio] 回传；不逐包打日志（原始字节流的分包频率远高于
     * SSE 事件），改为按累计字节数周期性记录进度
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
        var totalBytes = 0L
        var nextLogBytes = LOG_PROGRESS_INTERVAL_BYTES

        try {
            body.byteStream().use { input ->
                val buffer = ByteArray(READ_BUFFER_SIZE_BYTES)
                while (!isCancelled) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read == 0) continue

                    emitAudio(buffer.copyOf(read), listener)
                    totalBytes += read
                    if (totalBytes >= nextLogBytes) {
                        logDebug("Stream progress for chunk $chunkIndex: $totalBytes bytes received")
                        nextLogBytes += LOG_PROGRESS_INTERVAL_BYTES
                    }
                }
            }
        } catch (e: Exception) {
            logError("Error reading response stream", e)
            hasError = true
        }

        if (totalBytes > 0) {
            logDebug("Chunk $chunkIndex stream finished: $totalBytes bytes total")
        } else {
            logWarning("Chunk $chunkIndex stream finished without audio data")
        }
        return !hasError
    }

    override fun mapHttpError(errorBody: String): String {
        return OpenAIErrorParser.parse(errorBody)
    }

    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        return isConfiguredAs(config) { c: OpenAIConfig -> c.apiKey.isNotBlank() }
    }

    override fun createDefaultConfig(): BaseProviderConfig {
        return OpenAIConfig()
    }

    /**
     * 组装 instructions：用户风格指令 + 系统语速提示。
     * 两者皆缺省时返回 null（不携带 instructions，由模型按默认风格朗读）。
     */
    private fun buildInstructions(styleInstruction: String, speechRate: Float): String? {
        val userStyle = styleInstruction.trim()
        val rateHint = OpenAIParamMapper.speechRateStyleHint(speechRate)
        return listOfNotNull(userStyle.ifBlank { null }, rateHint)
            .joinToString(", ")
            .ifBlank { null }
    }
}

/**
 * OpenAI /v1/audio/speech 请求体构建（纯函数，便于单元测试）
 */
internal object OpenAIRequestBuilder {

    /** 指定响应格式为 headerless raw PCM（24kHz 16bit 小端，无文件头） */
    const val RESPONSE_FORMAT_PCM = "pcm"

    /**
     * 构建语音合成请求体。
     *
     * @param model 模型 ID（如 gpt-4o-mini-tts）
     * @param input 待合成文本
     * @param voice 音色 ID（如 alloy）
     * @param instructions 可选风格指令，null/空白时不携带该字段
     */
    fun build(model: String, input: String, voice: String, instructions: String? = null): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("input", input)
            put("voice", voice)
            put("response_format", RESPONSE_FORMAT_PCM)
            if (!instructions.isNullOrBlank()) {
                put("instructions", instructions)
            }
        }
    }
}
