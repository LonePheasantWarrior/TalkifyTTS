package com.github.lonepheasantwarrior.talkify.service.provider.impl

import android.util.Base64
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.VolcengineConfig
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.HttpStreamingTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.service.provider.VolcengineErrorParser
import com.github.lonepheasantwarrior.talkify.service.provider.VolcengineParamMapper
import com.github.lonepheasantwarrior.talkify.service.provider.toMaskedString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.Locale

/**
 * 火山引擎 - 豆包语音合成 2.0 供应商实现
 *
 * 继承 [HttpStreamingTtsProvider]，基于 OkHttp 实现 HTTP 流式音频合成（NDJSON 流）。
 * 分块调度、取消、错误分类等通用逻辑由基类提供。
 *
 * 供应商 ID：volcengine
 * 供应商：火山引擎
 * API 文档：https://www.volcengine.com/docs/6561/1598757
 */
class VolcengineProvider : HttpStreamingTtsProvider() {

    companion object {
        const val DEFAULT_API_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    }

    override val chunkMaxLength: Int = 300

    override val configLabels: Map<String, Int> = mapOf(
        "api_key" to R.string.api_key_label
    )

    override val supportedLanguages: Array<String> = arrayOf("zho", "eng")

    override val fallbackVoiceId: String = "zh_female_vv_uranus_bigtts"

    override val voiceIds: List<String> by lazy {
        loadVoiceIdsFromXml(R.xml.volcengine_seed_tts2_voices)
    }

    override fun getProviderId(): String = ProviderIds.Volcengine.providerId

    override fun getProviderName(): String = ProviderIds.Volcengine.provider

    override fun getDefaultApiUrl(): String = DEFAULT_API_URL

    override fun getDefaultModelId(): String = ProviderIds.Volcengine.defaultModelId

    override fun getAudioConfig(): AudioConfig = AudioConfig.SEED_TTS2

    override fun validateConfig(config: BaseProviderConfig): String? {
        if (config !is VolcengineConfig) {
            return TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED)
        }
        if (config.apiKey.isEmpty()) {
            return TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED)
        }
        // N19-f：明文端点在配置校验层显式拦截（平台禁明文，晚拦只会得到"网络不可用"）
        validateCleartextEndpoint(config.apiUrl)?.let { return it }
        return null
    }

    override fun buildHttpRequest(
        text: String,
        config: BaseProviderConfig,
        params: SynthesisParams
    ): Request {
        val volcConfig = config as VolcengineConfig
        val voiceId = if (volcConfig.voiceId.isNotEmpty()) {
            extractRealVoiceName(volcConfig.voiceId) ?: volcConfig.voiceId
        } else {
            voiceIds.firstOrNull() ?: fallbackVoiceId
        }

        val speechRate = VolcengineParamMapper.convertSpeechRate(params.speechRate)
        logDebug("ttsSpeechRate: ${params.speechRate}, seedSpeechRate: $speechRate")

        val loudnessRate = VolcengineParamMapper.convertLoudnessRate(params.volume)
        logDebug("ttsLoudnessRate: ${params.volume}, seedLoudnessRate: $loudnessRate")

        // 构建 additions 参数
        val additions = JSONObject().apply {
            // 按 SynthesisRequest 报告的语言显式设置语种；未知语言不传该参数，
            // 由服务端自动检测（此前硬编码 "zh" 使英文朗读被强制按中文处理，P1-3）
            explicitLanguage(params.language)?.let { put("explicit_language", it) }
            // 禁用 markdown 过滤
            put("disable_markdown_filter", true)
        }

        // 构建请求体
        val requestBody = JSONObject().apply {
            put("user", JSONObject().apply {
                // 安装级稳定 uid（此前每请求随机时间戳，服务端画像/限流维度失效，P2-B15）
                put("uid", "talkify_user")
            })
            put("req_params", JSONObject().apply {
                put("text", text)
                put("speaker", voiceId)
                put("audio_params", JSONObject().apply {
                    put("format", "pcm")
                    put("sample_rate", getAudioConfig().sampleRate)
                    put("speech_rate", speechRate)
                    put("loudness_rate", loudnessRate)
                })
                put("additions", additions.toString())
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBody.toString().toRequestBody(mediaType)

        val effectiveApiUrl = volcConfig.apiUrl.ifBlank { DEFAULT_API_URL }
        val effectiveResourceId = volcConfig.modelId.ifBlank { getDefaultModelId() }

        val request = Request.Builder()
            .url(effectiveApiUrl)
            .post(body)
            .header("x-api-key", volcConfig.apiKey)
            .header("X-Api-Resource-Id", effectiveResourceId)
            .header("Content-Type", "application/json")
            .header("Connection", "keep-alive")
            .build()

        // 打印请求详情（Headers 脱敏处理仅用于日志显示，实际发送的是原始值）
        logDebug("HTTP Request URL: ${request.url}")
        logDebug("HTTP Request Headers (masked for log): ${request.headers.toMaskedString()}")
        logDebug("HTTP Request Body: ${requestBody.toString(2)}")

        return request
    }

    /**
     * 处理流式响应（NDJSON 格式：每行一个 JSON 对象）
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

                    try {
                        val json = JSONObject(line)
                        val code = json.optInt("code", 0)

                        when {
                            // 音频数据
                            code == 0 && json.has("data") -> {
                                val data = json.getString("data")
                                if (data.isNotBlank()) {
                                    val audioData = Base64.decode(data, Base64.DEFAULT)
                                    if (audioData.isNotEmpty()) {
                                        emitAudio(audioData, listener)
                                        logDebug("Received audio data: ${audioData.size} bytes")
                                    }
                                }
                            }

                            // 句子信息（可选）
                            code == 0 && json.has("sentence") -> {
                                logDebug("Sentence data: ${json.optString("sentence")}")
                            }

                            // 合成完成
                            code == 20000000 -> {
                                if (json.has("usage")) {
                                    logDebug("Usage info: ${json.optJSONObject("usage")}")
                                }
                                logDebug("Chunk $chunkIndex synthesis finished")
                                break
                            }

                            // 错误
                            code > 0 -> {
                                val errMsg = json.optString("message")
                                logError("API error: code=$code, message=$errMsg")
                                hasError = true
                                withContext(Dispatchers.Main) {
                                    listener.onError(errMsg)
                                }
                                break
                            }
                        }
                    } catch (e: Exception) {
                        logError("Failed to parse JSON: $line", e)
                        // 继续处理下一行，不中断
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("Error reading response stream", e)
            hasError = true
            // 上抛交由基类 fetchChunk 统一分类上报：在此吞掉会让整次合成既无
            // 完成也无错误回调，服务层静默挂死至 120s 超时（P1-19）
            throw e
        }

        return !hasError
    }

    /** 映射为豆包 `explicit_language` 取值；不支持的语言返回 null（服务端自动检测） */
    private fun explicitLanguage(language: String?): String? = when (language?.lowercase(Locale.US)) {
        "zh", "zho", "chi" -> "zh"
        "en", "eng" -> "en"
        else -> null
    }

    override fun mapHttpError(errorBody: String): String {
        return VolcengineErrorParser.parse(errorBody)
    }

    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        return isConfiguredAs(config) { c: VolcengineConfig -> c.apiKey.isNotBlank() }
    }

    override fun createDefaultConfig(): BaseProviderConfig {
        return VolcengineConfig()
    }
}
