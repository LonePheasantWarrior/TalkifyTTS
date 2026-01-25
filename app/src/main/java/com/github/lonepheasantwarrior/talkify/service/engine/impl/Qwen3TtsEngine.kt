package com.github.lonepheasantwarrior.talkify.service.engine.impl

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Base64
import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult
import com.alibaba.dashscope.exception.ApiException
import com.alibaba.dashscope.exception.NoApiKeyException
import com.alibaba.dashscope.exception.UploadFileException
import com.alibaba.dashscope.utils.Constants
import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.engine.AbstractTtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import io.reactivex.Flowable
import io.reactivex.disposables.Disposable
import io.reactivex.subscribers.DisposableSubscriber
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.util.Locale

/**
 * 阿里云百炼 - 通义千问3语音合成引擎实现
 *
 * 继承 [AbstractTtsEngine]，实现 TTS 引擎接口
 * 支持流式音频合成，将音频数据块实时回调给系统
 *
 * 引擎 ID：qwen3-tts
 * 服务提供商：阿里云百炼
 */
class Qwen3TtsEngine : AbstractTtsEngine() {

    companion object {
        const val ENGINE_ID = "qwen3-tts"
        const val ENGINE_NAME = "通义千问3语音合成"

        const val MODEL_QWEN3_TTS_FLASH = "qwen3-tts-flash"

        private const val DEFAULT_LANGUAGE = "Auto"

        private const val MAX_TEXT_LENGTH = 500
    }

    @Volatile
    private var currentDisposable: Disposable? = null

    @Volatile
    private var isCancelled = false

    private var hasCompleted = false

    val audioConfig: AudioConfig
        @JvmName("getAudioConfigProperty") get() = AudioConfig.QWEN3_TTS

    init {
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1"
    }

    override fun getEngineId(): String = ENGINE_ID

    override fun getEngineName(): String = ENGINE_NAME

    override fun synthesize(
        text: String, params: SynthesisParams, config: EngineConfig, listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        if (config.apiKey.isEmpty()) {
            logError("API key is not configured")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        val textChunks = splitTextIntoChunks(text, MAX_TEXT_LENGTH)
        if (textChunks.isEmpty()) {
            listener.onError("文本为空")
            return
        }

        logInfo("Starting streaming synthesis: textLength=${text.length}, chunks=${textChunks.size}, pitch=${params.pitch}, speechRate=${params.speechRate}")
        logDebug("Audio config: ${audioConfig.getFormatDescription()}")

        isCancelled = false
        hasCompleted = false

        processNextChunk(textChunks, 0, params, config, listener)
    }

    private fun processNextChunk(
        chunks: List<String>,
        index: Int,
        params: SynthesisParams,
        config: EngineConfig,
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
            val conversation = MultiModalConversation()
            val param = buildConversationParam(chunk, params, config)
            val resultFlowable: Flowable<MultiModalConversationResult> =
                conversation.streamCall(param)

            currentDisposable = resultFlowable.subscribeWith(
                createChunkSubscriber(
                    chunks, index, params, config, listener
                )
            )
        } catch (e: Exception) {
            val (errorCode, errorMessage) = mapExceptionToErrorCode(e)
            logError("Synthesis error: $errorMessage", e)
            listener.onError(TtsErrorCode.getErrorMessage(errorCode))
        }
    }

    private fun mapExceptionToErrorCode(e: Exception): Pair<Int, String> {
        return when (e) {
            is NoApiKeyException -> {
                TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED to "API Key 未配置"
            }

            is UploadFileException -> {
                TtsErrorCode.ERROR_SYNTHESIS_FAILED to "上传文件失败：${e.message}"
            }

            is ApiException -> {
                val message = e.message ?: ""
                when {
                    message.contains("rate limit", ignoreCase = true) || message.contains(
                        "429",
                        ignoreCase = true
                    ) -> {
                        TtsErrorCode.ERROR_API_RATE_LIMITED to "API 调用频率超限，请稍后重试"
                    }

                    message.contains("401", ignoreCase = true) || message.contains(
                        "Unauthorized",
                        ignoreCase = true
                    ) || message.contains("invalid api_key", ignoreCase = true) -> {
                        TtsErrorCode.ERROR_API_AUTH_FAILED to "API Key 无效或已过期"
                    }

                    message.contains("500", ignoreCase = true) || message.contains(
                        "502",
                        ignoreCase = true
                    ) || message.contains("503", ignoreCase = true) || message.contains(
                        "504",
                        ignoreCase = true
                    ) -> {
                        TtsErrorCode.ERROR_API_SERVER_ERROR to "服务器暂时不可用，请稍后重试"
                    }

                    else -> {
                        TtsErrorCode.ERROR_SYNTHESIS_FAILED to "API 调用失败：${e.message}"
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

    private fun createChunkSubscriber(
        chunks: List<String>,
        index: Int,
        params: SynthesisParams,
        config: EngineConfig,
        listener: TtsSynthesisListener
    ): DisposableSubscriber<MultiModalConversationResult> {
        return object : DisposableSubscriber<MultiModalConversationResult>() {
            private var isFirstChunk = index == 0

            override fun onStart() {
                super.onStart()
                if (isFirstChunk) {
                    listener.onSynthesisStarted()
                    isFirstChunk = false
                }
            }

            override fun onNext(result: MultiModalConversationResult) {
                if (isCancelled || hasCompleted) {
                    return
                }

                try {
                    val audioData = extractAudioData(result)
                    if (audioData != null && audioData.isNotEmpty()) {
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
                    listener.onError(TtsErrorCode.getErrorMessage(errorCode))
                    dispose()
                }
            }

            override fun onError(throwable: Throwable) {
                logError("Stream error for chunk $index", throwable)
                val (errorCode, errorMessage) = mapExceptionToErrorCode(throwable as Exception)
                listener.onError(TtsErrorCode.getErrorMessage(errorCode))
            }

            override fun onComplete() {
                logDebug("Chunk $index completed")
                if (!isCancelled && !hasCompleted) {
                    processNextChunk(chunks, index + 1, params, config, listener)
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
            text[i]
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

    private fun buildConversationParam(
        text: String, params: SynthesisParams, config: EngineConfig
    ): MultiModalConversationParam {
        val voice = if (config.voiceId.isNotEmpty()) {
            parseVoice(config.voiceId)
        } else {
            logWarning("Voice ID not configured, using default CHERRY")
            AudioParameters.Voice.CHERRY
        }

        val languageType = convertToQwenLanguageType(params.language)

        return MultiModalConversationParam.builder().apiKey(config.apiKey)
            .model(MODEL_QWEN3_TTS_FLASH).text(text).voice(voice).languageType(languageType).build()
    }

    private fun convertToQwenLanguageType(language: String?): String {
        if (language.isNullOrBlank()) return DEFAULT_LANGUAGE
        return when (language.lowercase()) {
            "zh", "zho", "chi" -> "Chinese"
            "en", "eng" -> "English"
            "de", "ger", "deu" -> "German"
            "it", "ita" -> "Italian"
            "pt", "por" -> "Portuguese"
            "es", "spa" -> "Spanish"
            "ja", "jpn" -> "Japanese"
            "ko", "kor" -> "Korean"
            "fr", "fra", "fre" -> "French"
            "ru", "rus" -> "Russian"
            else -> DEFAULT_LANGUAGE
        }
    }

    private fun parseVoice(voiceId: String): AudioParameters.Voice {
        return try {
            AudioParameters.Voice.valueOf(voiceId)
        } catch (_: IllegalArgumentException) {
            try {
                AudioParameters.Voice.valueOf(voiceId.uppercase())
            } catch (_: IllegalArgumentException) {
                logWarning("Invalid voice ID: $voiceId, using default CHERRY")
                AudioParameters.Voice.CHERRY
            }
        }
    }


    @Suppress("UNUSED_PARAMETER")
    private fun isSynthesIsFinished(result: MultiModalConversationResult): Boolean {
        return false
    }

    private fun extractAudioData(result: MultiModalConversationResult): ByteArray? {
        return try {
            val output = result.getOutput() ?: return null
            val audio = output.audio ?: return null
            val base64Data = audio.data

            if (base64Data.isNullOrBlank()) {
                return null
            }

            Base64.decode(base64Data, Base64.DEFAULT)
        } catch (e: Exception) {
            logError("Failed to extract audio data", e)
            null
        }
    }

    override fun getSupportedLanguages(): Set<String> {
        return setOf("zh", "en", "de", "it", "pt", "es", "ja", "ko", "fr", "ru")
    }

    override fun getDefaultLanguages(): Array<String> {
        // 必须按照 [Language, Country, Variant] 的顺序返回
        return arrayOf("zho", "CHN", "")
    }

    override fun getSupportedVoices(): List<Voice> {
        val local = Locale.forLanguageTag("zh")
        logInfo("zh Local: lang [${local.language}]")

        val voices = mutableListOf<Voice>()

        val features = HashSet<String>()
        features.add(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)

        for (langCode in getSupportedLanguages()) {
            for (engineVoice in AudioParameters.Voice.entries) {
                val locale = when(langCode) {
                    "zh" -> Locale.CHINA
                    "en" -> Locale.US
                    else -> Locale.forLanguageTag(langCode)
                }

                voices.add(
                    Voice(
                        engineVoice.value,
                        locale,
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

    override fun getDefaultVoiceId(lang: String?, country: String?, variant: String?): String {
        return AudioParameters.Voice.CHERRY.value
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        if (voiceId == null) {
            return false
        }
        return AudioParameters.Voice.entries.any { it.value == voiceId }
    }

    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
        currentDisposable?.dispose()
        currentDisposable = null
    }

    override fun release() {
        logInfo("Releasing engine")
        isCancelled = true
        currentDisposable?.dispose()
        currentDisposable = null
        super.release()
    }
}
