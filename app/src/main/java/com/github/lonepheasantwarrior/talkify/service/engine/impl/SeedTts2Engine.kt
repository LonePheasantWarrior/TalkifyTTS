package com.github.lonepheasantwarrior.talkify.service.engine.impl

import android.speech.tts.Voice
import android.util.Base64
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.SeedTts2Config
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.AbstractTtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 火山引擎 - 豆包语音合成 2.0 引擎实现
 *
 * 继承 [AbstractTtsEngine]，实现 TTS 引擎接口
 * 基于 OkHttp 实现 HTTP 流式音频合成，支持连接复用
 * 将音频数据块实时回调给系统
 *
 * 引擎 ID：seed-tts-2.0
 * 服务提供商：火山引擎
 * API 文档：https://www.volcengine.com/docs/6561/1598757
 */
class SeedTts2Engine : AbstractTtsEngine() {

    companion object {
        const val ENGINE_ID = "seed-tts-2.0"
        const val ENGINE_NAME = "豆包语音合成2.0"
        private const val VOICE_NAME_SEPARATOR = "::"
        private const val API_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        private const val RESOURCE_ID = "seed-tts-2.0"

        // 文本分块配置
        private const val MAX_TEXT_LENGTH = 300

        // OkHttp 连接池配置（复用连接，keep-alive 1分钟）
        private val connectionPool = ConnectionPool(5, 1, TimeUnit.MINUTES)

        // 共享的 OkHttpClient 实例（支持连接复用）
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 支持的语言列表（ISO 639-2 三字母代码）
         */
        val SUPPORTED_LANGUAGES = arrayOf("zho", "eng")
    }

    // 协程作用域用于异步处理
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isCancelled = false

    @Volatile
    private var hasCompleted = false

    @Volatile
    private var currentCall: Call? = null

    @Volatile
    private var isFirstChunk = true

    val audioConfig: AudioConfig
        @JvmName("getAudioConfigProperty") get() = AudioConfig.SEED_TTS2

    /**
     * 缓存的声音ID列表，从资源文件加载
     */
    private val voiceIds: List<String> by lazy {
        loadVoiceIdsFromResource()
    }

    /**
     * 从资源文件加载声音ID列表
     */
    private fun loadVoiceIdsFromResource(): List<String> {
        val context = TalkifyAppHolder.getContext()
        return if (context != null) {
            try {
                context.resources.getStringArray(R.array.volcengine_seed_TTS_2_voices).toList()
            } catch (e: Exception) {
                TtsLogger.e("Failed to load voice IDs from resource", throwable = e)
                emptyList()
            }
        } else {
            TtsLogger.w("Context not available, voice IDs will be empty")
            emptyList()
        }
    }

    override fun getEngineId(): String = ENGINE_ID

    override fun getEngineName(): String = ENGINE_NAME

    override fun getAudioConfig(): AudioConfig = audioConfig

    override fun synthesize(
        text: String, params: SynthesisParams, config: BaseEngineConfig, listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val seedConfig = config as? SeedTts2Config
        if (seedConfig == null) {
            logError("Invalid config type, expected SeedTts2Config")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        if (seedConfig.apiKey.isEmpty()) {
            logError("API Key is not configured")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        if (text.isEmpty()) {
            logWarning("待朗读文本内容为空")
            listener.onSynthesisCompleted()
            return
        }

        if (!containsReadableText(text)) {
            logWarning("文本不包含可朗读的文字内容")
            listener.onSynthesisCompleted()
            return
        }

        logInfo("Starting synthesis: textLength=${text.length}, pitch=${params.pitch}, speechRate=${params.speechRate}")
        logDebug("Audio config: ${audioConfig.getFormatDescription()}")

        isCancelled = false
        hasCompleted = false
        isFirstChunk = true

        // 将文本分块处理
        val textChunks = splitTextIntoChunks(text, MAX_TEXT_LENGTH)
        if (textChunks.isEmpty()) {
            listener.onError("文本为空")
            return
        }

        logDebug("Text split into ${textChunks.size} chunks")

        // 使用协程顺序处理所有文本块
        engineScope.launch {
            processChunksSequentially(textChunks, seedConfig, params, listener)
        }
    }

    /**
     * 顺序处理所有文本块
     */
    private suspend fun processChunksSequentially(
        chunks: List<String>,
        config: SeedTts2Config,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ) {
        for ((index, chunk) in chunks.withIndex()) {
            if (isCancelled || hasCompleted) {
                logDebug("Synthesis cancelled or completed, stopping chunk processing")
                return
            }

            logDebug("Processing chunk $index/${chunks.size}, length=${chunk.length}")

            val success = processSingleChunk(chunk, index, chunks.size, config, params, listener)
            if (!success) {
                logError("Failed to process chunk $index")
                return
            }
        }

        // 所有块处理完成
        if (!isCancelled && !hasCompleted) {
            hasCompleted = true
            withContext(Dispatchers.Main) {
                listener.onSynthesisCompleted()
            }
            logInfo("Synthesis completed successfully")
        }
    }

    /**
     * 处理单个文本块
     * @return 是否成功处理
     */
    private suspend fun processSingleChunk(
        text: String,
        chunkIndex: Int,
        totalChunks: Int,
        config: SeedTts2Config,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = buildHttpRequest(text, config, params)

            // 使用同步调用便于顺序处理
            currentCall = sharedClient.newCall(request)

            val response = currentCall?.execute()
            if (response == null) {
                logError("Failed to execute HTTP request")
                withContext(Dispatchers.Main) {
                    listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_UNAVAILABLE))
                }
                return@withContext false
            }

            // 打印响应详情
            logDebug("HTTP Response Code: ${response.code}")
            logDebug("HTTP Response Headers: ${response.headers}")

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "No error body"
                logError("HTTP error: ${response.code}, body: $errorBody")
                
                // 解析火山引擎错误码，提供更有针对性的错误提示
                val errorMessage = parseVolcEngineError(errorBody)
                
                withContext(Dispatchers.Main) {
                    listener.onError(errorMessage)
                }
                response.close()
                return@withContext false
            }

            // 获取 LogId 用于问题定位
            val logId = response.header("X-Tt-Logid")
            if (logId != null) {
                logDebug("X-Tt-Logid: $logId")
            }

            // 处理流式响应
            processStreamResponse(response, chunkIndex, listener)

        } catch (e: SocketTimeoutException) {
            logError("Network timeout", e)
            withContext(Dispatchers.Main) {
                listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_TIMEOUT))
            }
            false
        } catch (e: IOException) {
            logError("Network error", e)
            withContext(Dispatchers.Main) {
                listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_UNAVAILABLE))
            }
            false
        } catch (e: Exception) {
            logError("Unexpected error during synthesis", e)
            withContext(Dispatchers.Main) {
                listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
            }
            false
        } finally {
            currentCall = null
        }
    }

    /**
     * 处理流式响应
     */
    private suspend fun processStreamResponse(
        response: Response,
        chunkIndex: Int,
        listener: TtsSynthesisListener
    ): Boolean = withContext(Dispatchers.IO) {
        val body = response.body
        if (body == null) {
            logError("Response body is null")
            return@withContext false
        }

        var hasError = false
        var chunkStarted = false

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
                                        // 首个音频块，触发开始回调
                                        if (!chunkStarted && isFirstChunk) {
                                            chunkStarted = true
                                            isFirstChunk = false
                                            listener.onSynthesisStarted()
                                        }

                                        listener.onAudioAvailable(
                                            audioData,
                                            audioConfig.sampleRate,
                                            audioConfig.audioFormat,
                                            audioConfig.channelCount
                                        )
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
                                logError("API error: code=$code, message=${json.optString("message")}")
                                hasError = true
                                withContext(Dispatchers.Main) {
                                    listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
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
        } catch (e: Exception) {
            logError("Error reading response stream", e)
            hasError = true
        } finally {
            response.close()
        }

        !hasError
    }

    /**
     * 构建 HTTP 请求
     */
    private fun buildHttpRequest(
        text: String,
        config: SeedTts2Config,
        params: SynthesisParams
    ): Request {
        val voiceId = if (config.voiceId.isNotEmpty()) {
            extractRealVoiceName(config.voiceId) ?: config.voiceId
        } else {
            // 默认声音
            voiceIds.firstOrNull() ?: "zh_female_vv_uranus_bigtts"
        }

        val speechRate = convertSpeechRate(params.speechRate)
        logDebug("ttsSpeechRate: ${params.speechRate}, seedSpeechRate: $speechRate")

        val loudnessRate = convertLoudnessRate(params.volume)
        logDebug("ttsLoudnessRate: ${params.volume}, seedLoudnessRate: $loudnessRate")

        // 构建 additions 参数
        val additions = JSONObject().apply {
            // 明确语种设置
            put("explicit_language", "zh")
            // 禁用 markdown 过滤
            put("disable_markdown_filter", true)
        }

        // 构建请求体
        val requestBody = JSONObject().apply {
            put("user", JSONObject().apply {
                put("uid", "talkify_user_${System.currentTimeMillis()}")
            })
            put("req_params", JSONObject().apply {
                put("text", text)
                put("speaker", voiceId)
                put("audio_params", JSONObject().apply {
                    put("format", audioConfig.getVolcEngineFormat())
                    put("sample_rate", audioConfig.sampleRate)
                    put("speech_rate", speechRate)
                    put("loudness_rate", loudnessRate)
                })
                put("additions", additions.toString())
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(API_URL)
            .post(body)
            .header("x-api-key", config.apiKey)
            .header("X-Api-Resource-Id", RESOURCE_ID)
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
     * 将 Headers 转换为脱敏字符串用于日志
     */
    private fun okhttp3.Headers.toMaskedString(): String {
        val sb = StringBuilder("{")
        for (i in 0 until this.size) {
            val name = this.name(i)
            val value = this.value(i)
            val maskedValue = when (name.lowercase()) {
                "x-api-key" -> "${value.take(4)}****${value.takeLast(4)}"
                else -> value
            }
            sb.append("$name=$maskedValue")
            if (i < this.size - 1) sb.append(", ")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * 解析火山引擎错误响应，返回用户友好的错误信息
     */
    private fun parseVolcEngineError(errorBody: String): String {
        return try {
            val json = JSONObject(errorBody)
            val header = json.optJSONObject("header")
            val code = header?.optInt("code", 0) ?: 0
            val message = header?.optString("message", "") ?: ""
            
            when (code) {
                45000030 -> "资源未授权：请在火山引擎控制台开通对应服务服务 (code: $code)"
                45000001 -> "认证失败：请检查 App ID 和 Access Key 是否正确 (code: $code)"
                45000002 -> "参数错误：$message (code: $code)"
                45000003 -> "请求过于频繁，请稍后重试 (code: $code)"
                45000004 -> "服务暂时不可用，请稍后重试 (code: $code)"
                45000005 -> "余额不足：请充值后再试 (code: $code)"
                else -> "语音合成失败：$message (code: $code)"
            }
        } catch (e: Exception) {
            TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED)
        }
    }

    /**
     * 将 AudioConfig 的音频格式转换为火山引擎 API 格式字符串
     */
    private fun AudioConfig.getVolcEngineFormat(): String {
        return when (this.audioFormat) {
            android.media.AudioFormat.ENCODING_PCM_16BIT -> "pcm"
            android.media.AudioFormat.ENCODING_PCM_8BIT -> "pcm"
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> "pcm"
            else -> "pcm"
        }
    }

    /**
     * 转换语速参数
     * Android: [0, 200]，100 为默认值（表示 1.0x 倍速）
     * 火山: [-50, 100]，0 为默认值
     *
     * 转换规则：
     * - Android 50 (0.5x) -> 火山 -50
     * - Android 100 (1.0x) -> 火山 0
     * - Android 200 (2.0x) -> 火山 100
     */
    private fun convertSpeechRate(androidRate: Float): Int {
        return when {
            androidRate <= 50f -> -50
            androidRate >= 200f -> 100
            else -> ((androidRate - 100f) / 100f * 100f).roundToInt()
        }
    }

    /**
     * 转换音量参数
     * 火山: [-50, 100]，0 为默认值
     *
     * 注意：SynthesisRequest.getVolume() 返回的是 [0.0, 1.0] 的浮点数
     * 而 getSpeechRate() 和 getPitch() 返回的是 [0, 200] 的整数
     */
    private fun convertLoudnessRate(androidVolume: Float): Int {
        return when {
            androidVolume <= 0.25f -> -50
            androidVolume >= 1.0f -> 100
            else -> ((androidVolume - 0.5f) / 0.5f * 100f).roundToInt()
        }
    }

    /**
     * 将文本分割为块
     * 参考 Qwen3TtsEngine 的实现
     */
    private fun splitTextIntoChunks(text: String, maxLength: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var lastSplitPos = 0

        var i = 0
        while (i < text.length) {
            val remainingLength = text.length - lastSplitPos

            if (remainingLength <= maxLength) {
                chunks.add(text.substring(lastSplitPos))
                break
            }

            val isSentenceEnd = checkSentenceEnd(text, i)
            val isMidPause = checkMidPause(text, i)

            if (isSentenceEnd || isMidPause) {
                val chunkLength = i - lastSplitPos + 1
                if (chunkLength <= maxLength) {
                    chunks.add(text.substring(lastSplitPos, i + 1))
                    lastSplitPos = i + 1
                    i++
                    continue
                }
            }

            val splitPos = findBestSplitPos(text, lastSplitPos, maxLength)
            if (splitPos > lastSplitPos) {
                chunks.add(text.substring(lastSplitPos, splitPos))
                lastSplitPos = splitPos
            } else {
                chunks.add(text.substring(lastSplitPos, lastSplitPos + maxLength))
                lastSplitPos += maxLength
            }
            i = lastSplitPos
        }

        return chunks
    }

    private fun checkSentenceEnd(text: String, index: Int): Boolean {
        if (index < 0) return false
        val sentenceEnds = listOf("。", "！", "？", ".", "!", "?")
        for (ender in sentenceEnds) {
            if (text.regionMatches(index, ender, 0, ender.length)) {
                return true
            }
        }
        return false
    }

    private fun checkMidPause(text: String, index: Int): Boolean {
        if (index < 0) return false
        val midPauses = listOf("，", "、", ",", ";", "；", "：", ":")
        for (pause in midPauses) {
            if (text.regionMatches(index, pause, 0, pause.length)) {
                return true
            }
        }
        return false
    }

    private fun findBestSplitPos(text: String, startPos: Int, maxLength: Int): Int {
        val searchEnd = minOf(startPos + maxLength, text.length)

        for (i in searchEnd - 1 downTo startPos + 1) {
            if (checkMidPause(text, i)) {
                return i + 1
            }
        }

        for (i in searchEnd - 1 downTo startPos + 1) {
            val char = text[i]
            if (char == ' ' || char == '\n' || char == '\t') {
                return i + 1
            }
        }

        return searchEnd
    }

    override fun getSupportedLanguages(): Set<String> {
        return SUPPORTED_LANGUAGES.toSet()
    }

    override fun getDefaultLanguages(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.language, Locale.SIMPLIFIED_CHINESE.country, "")
    }

    override fun getSupportedVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()

        for (langCode in getSupportedLanguages()) {
            for (voiceId in voiceIds) {
                voices.add(
                    Voice(
                        "$voiceId$VOICE_NAME_SEPARATOR$langCode",
                        Locale.forLanguageTag(langCode),
                        Voice.QUALITY_NORMAL,
                        Voice.LATENCY_NORMAL,
                        true,
                        emptySet()
                    )
                )
            }
        }
        return voices
    }

    override fun getDefaultVoiceId(
        lang: String?,
        country: String?,
        variant: String?,
        currentVoiceId: String?
    ): String {
        val defaultVoice = voiceIds.firstOrNull() ?: "zh_female_vv_uranus_bigtts"
        if (currentVoiceId != null && currentVoiceId.isNotBlank()) {
            return "$currentVoiceId$VOICE_NAME_SEPARATOR$lang"
        }
        return "$defaultVoice$VOICE_NAME_SEPARATOR$lang"
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        if (voiceId == null) {
            return false
        }
        val realVoiceName = extractRealVoiceName(voiceId)
        return realVoiceName != null && voiceIds.contains(realVoiceName)
    }

    private fun extractRealVoiceName(androidVoiceName: String?): String? {
        if (androidVoiceName == null) return null
        return if (androidVoiceName.contains(VOICE_NAME_SEPARATOR)) {
            androidVoiceName.substringBefore(VOICE_NAME_SEPARATOR)
        } else {
            androidVoiceName
        }
    }

    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
        currentCall?.cancel()
        currentCall = null
    }

    override fun release() {
        logInfo("Releasing engine")
        isCancelled = true
        currentCall?.cancel()
        currentCall = null
        engineScope.cancel()
        super.release()
    }

    override fun isConfigured(config: BaseEngineConfig?): Boolean {
        val seedConfig = config as? SeedTts2Config
        var result = false
        if (seedConfig != null) {
            result = seedConfig.apiKey.isNotBlank()
        }
        TtsLogger.d("$tag: isConfigured = $result")
        return result
    }

    override fun createDefaultConfig(): BaseEngineConfig {
        return SeedTts2Config()
    }

    override fun getConfigLabel(configKey: String, context: android.content.Context): String? {
        return when (configKey) {
            "api_key" -> context.getString(R.string.api_key_label)
            "voice_id" -> context.getString(R.string.voice_select_label)
            else -> null
        }
    }
}
