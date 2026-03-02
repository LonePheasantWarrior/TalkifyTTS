package com.github.lonepheasantwarrior.talkify.service.engine.impl

import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TencentTtsConfig
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.AbstractTtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import com.tencent.cloud.stream.tts.FlowingSpeechSynthesizer
import com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerListener
import com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerRequest
import com.tencent.cloud.stream.tts.SpeechSynthesizerResponse
import com.tencent.cloud.stream.tts.core.exception.SynthesizerException
import com.tencent.cloud.stream.tts.core.ws.Credential
import com.tencent.cloud.stream.tts.core.ws.SpeechClient
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

/**
 * 腾讯语音合成引擎实现
 *
 * 继承 [AbstractTtsEngine]，实现 TTS 引擎接口
 * 支持流式音频合成，将音频数据块实时回调给系统
 *
 * 引擎 ID：tencent-tts
 * 服务提供商：腾讯云
 */
class TencentTtsEngine : AbstractTtsEngine() {

    companion object {
        const val ENGINE_ID = "tencent-tts"
        const val ENGINE_NAME = "腾讯语音合成"

        private const val MAX_TEXT_LENGTH = 300
        private const val VOICE_NAME_SEPARATOR = "::"

        /**
         * 默认音色 ID
         */
        const val DEFAULT_VOICE_ID = 502001

        /**
         * 支持的语言列表（ISO 639-2 三字母代码）
         */
        val SUPPORTED_LANGUAGES = arrayOf("zho", "eng")
    }

    private val speechClient = SpeechClient()

    @Volatile
    private var currentSynthesizer: FlowingSpeechSynthesizer? = null

    @Volatile
    private var isCancelled = false

    private var hasCompleted = false

    val audioConfig: AudioConfig
        @JvmName("getAudioConfigProperty") get() = AudioConfig.TENCENT_TTS

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
                context.resources.getStringArray(R.array.tencent_tts_voices).toList()
            } catch (e: Exception) {
                TtsLogger.e("Failed to load voice IDs from resource", throwable = e)
                listOf(DEFAULT_VOICE_ID.toString())
            }
        } else {
            TtsLogger.w("Context not available, voice IDs will contain only default")
            listOf(DEFAULT_VOICE_ID.toString())
        }
    }

    override fun getEngineId(): String = ENGINE_ID

    override fun getEngineName(): String = ENGINE_NAME

    override fun synthesize(
        text: String, params: SynthesisParams, config: BaseEngineConfig, listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val tencentConfig = config as? TencentTtsConfig
        if (tencentConfig == null) {
            logError("Invalid config type, expected TencentTtsConfig")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        if (tencentConfig.appId.isEmpty() || tencentConfig.secretId.isEmpty() || tencentConfig.secretKey.isEmpty()) {
            logError("API credentials are not configured")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        val textChunks = splitTextIntoChunks(text, MAX_TEXT_LENGTH)
        if (textChunks.isEmpty()) {
            logWarning("待朗读文本内容为空")
            listener.onSynthesisCompleted()
            return
        }

        logInfo("Starting streaming synthesis: textLength=${text.length}, chunks=${textChunks.size}, pitch=${params.pitch}, speechRate=${params.speechRate}")
        logDebug("Audio config: ${audioConfig.getFormatDescription()}")

        isCancelled = false
        hasCompleted = false

        processNextChunk(textChunks, 0, params, tencentConfig, listener)
    }

    private fun processNextChunk(
        chunks: List<String>,
        index: Int,
        params: SynthesisParams,
        config: TencentTtsConfig,
        listener: TtsSynthesisListener
    ) {
        if (isCancelled || hasCompleted) {
            return
        }

        if (index >= chunks.size) {
            logDebug("All chunks processed")
            hasCompleted = true
            listener.onSynthesisCompleted()
            return
        }

        val chunk = chunks[index]
        logDebug("Processing chunk $index/${chunks.size}, length=${chunk.length}")

        try {
            val credential = Credential(config.appId, config.secretId, config.secretKey, "")
            val request = buildSynthesizerRequest(params, config)
            val synthesizerListener = createSynthesizerListener(chunks, index, params, config, listener)

            currentSynthesizer = FlowingSpeechSynthesizer(speechClient, credential, request, synthesizerListener)
            
            Thread {
                try {
                    currentSynthesizer?.start()
                    currentSynthesizer?.process(chunk)
                    currentSynthesizer?.stop()
                } catch (e: Exception) {
                    if (!hasCompleted && !isCancelled) {
                        hasCompleted = true
                        val (errorCode, errorMessage) = mapExceptionToErrorCode(e)
                        logError("Synthesis error: $errorMessage", e)
                        listener.onError(TtsErrorCode.getErrorMessage(errorCode, errorMessage))
                    } else {
                        logDebug("Ignoring subsequent error after completion/cancel")
                    }
                }
            }.start()

        } catch (e: Exception) {
            if (!hasCompleted && !isCancelled) {
                hasCompleted = true
                val (errorCode, errorMessage) = mapExceptionToErrorCode(e)
                logError("Synthesis error: $errorMessage", e)
                listener.onError(TtsErrorCode.getErrorMessage(errorCode, errorMessage))
            } else {
                logDebug("Ignoring subsequent error after completion/cancel")
            }
        }
    }

    private fun mapTencentError(code: Int, message: String): Pair<Int, String> {
        return when (code) {
            3022 -> {
                TtsErrorCode.ERROR_SYNTHESIS_FAILED to "资源包配额已用尽，请前往腾讯云控制台购买资源包或开启按量付费"
            }
            else -> {
                val friendlyMessage = when {
                    message.contains("APPID_IS_EMPTY", ignoreCase = true) ||
                    message.contains("SECRETID_IS_EMPTY", ignoreCase = true) ||
                    message.contains("SECRETKEY_IS_EMPTY", ignoreCase = true) -> {
                        "API 凭证未配置"
                    }
                    message.contains("CONNECT_SERVER_FAIL", ignoreCase = true) -> {
                        "无法连接到服务器，请检查网络连接"
                    }
                    message.contains("START_SYNTHESIZER_FAIL", ignoreCase = true) -> {
                        "启动合成器失败"
                    }
                    message.contains("resource pack allowance", ignoreCase = true) -> {
                        "资源包配额已用尽，请前往腾讯云控制台购买资源包或开启按量付费"
                    }
                    else -> {
                        message
                    }
                }
                TtsErrorCode.ERROR_SYNTHESIS_FAILED to friendlyMessage
            }
        }
    }

    private fun mapExceptionToErrorCode(e: Exception): Pair<Int, String> {
        return when (e) {
            is SynthesizerException -> {
                val message = e.message ?: ""
                when {
                    message.contains("APPID_IS_EMPTY", ignoreCase = true) ||
                    message.contains("SECRETID_IS_EMPTY", ignoreCase = true) ||
                    message.contains("SECRETKEY_IS_EMPTY", ignoreCase = true) -> {
                        TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED to "API 凭证未配置"
                    }
                    message.contains("CONNECT_SERVER_FAIL", ignoreCase = true) -> {
                        TtsErrorCode.ERROR_NETWORK_UNAVAILABLE to "无法连接到服务器，请检查网络连接"
                    }
                    message.contains("START_SYNTHESIZER_FAIL", ignoreCase = true) -> {
                        TtsErrorCode.ERROR_SYNTHESIS_FAILED to "启动合成器失败"
                    }
                    else -> {
                        TtsErrorCode.ERROR_SYNTHESIS_FAILED to message
                    }
                }
            }
            is SocketTimeoutException -> {
                TtsErrorCode.ERROR_NETWORK_TIMEOUT to "网络连接超时，请检查网络设置"
            }
            is ConnectException -> {
                TtsErrorCode.ERROR_NETWORK_UNAVAILABLE to "无法连接到服务器，请检查网络连接"
            }
            else -> {
                TtsErrorCode.ERROR_GENERIC to "发生错误：${e.message ?: "未知错误"}"
            }
        }
    }

    private fun createSynthesizerListener(
        chunks: List<String>,
        index: Int,
        params: SynthesisParams,
        config: TencentTtsConfig,
        listener: TtsSynthesisListener
    ): FlowingSpeechSynthesizerListener {
        return object : FlowingSpeechSynthesizerListener() {
            private var isFirstChunk = index == 0

            override fun onSynthesisStart(response: SpeechSynthesizerResponse) {
                if (isFirstChunk) {
                    listener.onSynthesisStarted()
                    isFirstChunk = false
                }
            }

            override fun onSynthesisEnd(response: SpeechSynthesizerResponse) {
                logDebug("Chunk $index completed")
                if (!isCancelled && !hasCompleted) {
                    processNextChunk(chunks, index + 1, params, config, listener)
                }
            }

            override fun onAudioResult(buffer: ByteBuffer) {
                if (isCancelled || hasCompleted) {
                    return
                }

                try {
                    val audioData = ByteArray(buffer.remaining())
                    buffer.get(audioData)
                    if (audioData.isNotEmpty()) {
                        logDebug("Received audio chunk: ${audioData.size} bytes")
                        listener.onAudioAvailable(
                            audioData,
                            audioConfig.sampleRate,
                            audioConfig.audioFormat,
                            audioConfig.channelCount
                        )
                    }
                } catch (e: Exception) {
                    logError("Error processing audio chunk", e)
                    val (errorCode, errorMessage) = mapExceptionToErrorCode(e)
                    listener.onError(TtsErrorCode.getErrorMessage(errorCode, errorMessage))
                }
            }

            override fun onTextResult(response: SpeechSynthesizerResponse) {
                logDebug("Text result received")
            }

            override fun onSynthesisCancel() {
                logDebug("Synthesis cancelled")
            }

            override fun onSynthesisFail(response: SpeechSynthesizerResponse) {
                logError("Synthesis failed: code=${response.code}, message=${response.message}")
                if (!hasCompleted && !isCancelled) {
                    hasCompleted = true
                    val (errorCode, errorMessage) = mapTencentError(response.code, response.message ?: "")
                    listener.onError(TtsErrorCode.getErrorMessage(errorCode, errorMessage))
                }
            }
        }
    }

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

    private fun buildSynthesizerRequest(
        params: SynthesisParams, config: TencentTtsConfig
    ): FlowingSpeechSynthesizerRequest {
        val request = FlowingSpeechSynthesizerRequest()

        val voiceType = if (config.voiceId.isNotEmpty()) {
            parseVoiceType(config.voiceId)
        } else {
            logWarning("Voice ID not configured, using default $DEFAULT_VOICE_ID")
            DEFAULT_VOICE_ID
        }

        val speed = convertSpeechRate(params.speechRate)

        request.voiceType = voiceType
        request.codec = "pcm"
        request.sampleRate = audioConfig.sampleRate
        request.speed = speed
        request.volume = 0f
        request.enableSubtitle = false
        request.emotionCategory = "neutral"
        request.emotionIntensity = 100
        request.sessionId = UUID.randomUUID().toString()

        return request
    }

    private fun convertSpeechRate(speechRate: Float): Float {
        return when {
            speechRate <= 0.6f -> -2f
            speechRate <= 0.8f -> -1f
            speechRate <= 1.0f -> 0f
            speechRate <= 1.2f -> 1f
            speechRate <= 1.5f -> 2f
            else -> 6f
        }
    }

    private fun parseVoiceType(voiceId: String): Int {
        return try {
            val realVoiceId = extractRealVoiceId(voiceId)
            realVoiceId?.toInt() ?: DEFAULT_VOICE_ID
        } catch (_: NumberFormatException) {
            logWarning("Invalid voice ID: $voiceId, using default $DEFAULT_VOICE_ID")
            DEFAULT_VOICE_ID
        }
    }

    private fun extractRealVoiceId(androidVoiceName: String?): String? {
        if (androidVoiceName == null) return null
        return if (androidVoiceName.contains(VOICE_NAME_SEPARATOR)) {
            androidVoiceName.substringBefore(VOICE_NAME_SEPARATOR)
        } else {
            androidVoiceName
        }
    }

    override fun getSupportedLanguages(): Set<String> {
        return SUPPORTED_LANGUAGES.toSet()
    }

    override fun getDefaultLanguages(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.isO3Language, Locale.SIMPLIFIED_CHINESE.isO3Country, "")
    }

    override fun getSupportedVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()

        for (langCode in getSupportedLanguages()) {
            for (voiceId in voiceIds) {
                voices.add(
                    Voice(
                        "$voiceId$VOICE_NAME_SEPARATOR$langCode",
                        Locale.forLanguageTag(if (langCode == "zho") "zh-CN" else "en-US"),
                        Voice.QUALITY_HIGH,
                        Voice.LATENCY_NORMAL,
                        true,
                        emptySet()
                    )
                )
            }
        }
        return voices
    }

    override fun getDefaultVoiceId(lang: String?, country: String?, variant: String?, currentVoiceId: String?): String {
        if (!currentVoiceId.isNullOrBlank()) {
            return "$currentVoiceId$VOICE_NAME_SEPARATOR$lang"
        }
        return "$DEFAULT_VOICE_ID$VOICE_NAME_SEPARATOR$lang"
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        if (voiceId == null) {
            return false
        }
        val realVoiceId = extractRealVoiceId(voiceId) ?: return false
        return voiceIds.contains(realVoiceId)
    }

    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
        try {
            currentSynthesizer?.cancel()
        } catch (e: Exception) {
            logError("Error stopping synthesizer", e)
        }
        currentSynthesizer = null
    }

    override fun release() {
        logInfo("Releasing engine")
        isCancelled = true
        try {
            currentSynthesizer?.cancel()
        } catch (e: Exception) {
            logError("Error cancelling synthesizer", e)
        }
        currentSynthesizer = null
        super.release()
    }

    override fun isConfigured(config: BaseEngineConfig?): Boolean {
        val tencentConfig = config as? TencentTtsConfig
        var result = false
        if (tencentConfig != null) {
            result = tencentConfig.appId.isNotBlank() &&
                    tencentConfig.secretId.isNotBlank() &&
                    tencentConfig.secretKey.isNotBlank()
        }
        TtsLogger.d("$tag: isConfigured = $result")
        return result
    }

    override fun createDefaultConfig(): BaseEngineConfig {
        return TencentTtsConfig()
    }

    override fun getConfigLabel(configKey: String, context: android.content.Context): String? {
        return when (configKey) {
            "app_id" -> context.getString(R.string.tencent_app_id_label)
            "secret_id" -> context.getString(R.string.tencent_secret_id_label)
            "secret_key" -> context.getString(R.string.tencent_secret_key_label)
            "voice_id" -> context.getString(R.string.voice_select_label)
            else -> null
        }
    }
}
