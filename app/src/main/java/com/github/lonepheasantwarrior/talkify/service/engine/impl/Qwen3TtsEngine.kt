package com.github.lonepheasantwarrior.talkify.service.engine.impl

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
        @JvmName("getAudioConfigProperty")
        get() = AudioConfig.QWEN3_TTS

    init {
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1"
    }

    override fun getEngineId(): String = ENGINE_ID

    override fun getEngineName(): String = ENGINE_NAME

    override fun synthesize(
        text: String,
        params: SynthesisParams,
        config: EngineConfig,
        listener: TtsSynthesisListener
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
            val resultFlowable: Flowable<MultiModalConversationResult> = conversation.streamCall(param)

            currentDisposable = resultFlowable.subscribeWith(createChunkSubscriber(chunks, index, params, config, listener))
        } catch (e: NoApiKeyException) {
            logError("API key error", e)
            listener.onError("API Key 配置错误：${e.message}")
        } catch (e: UploadFileException) {
            logError("Upload file error", e)
            listener.onError("上传文件失败：${e.message}")
        } catch (e: ApiException) {
            logError("API error: ${e.message}", e)
            listener.onError("API 调用失败：${e.message}")
        } catch (e: Exception) {
            logError("Unexpected error", e)
            listener.onError("发生错误：${e.message}")
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
                    listener.onError("处理音频数据失败：${e.message}")
                    dispose()
                }
            }

            override fun onError(throwable: Throwable) {
                logError("Stream error for chunk $index", throwable)
                val errorMessage = when (throwable) {
                    is ApiException -> "API 错误：${throwable.message}"
                    else -> "合成失败：${throwable.message}"
                }
                listener.onError(errorMessage)
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
        text: String,
        params: SynthesisParams,
        config: EngineConfig
    ): MultiModalConversationParam {
        val voice = if (config.voiceId.isNotEmpty()) {
            parseVoice(config.voiceId)
        } else {
            logWarning("Voice ID not configured, using default CHERRY")
            AudioParameters.Voice.CHERRY
        }

        val languageType = convertToQwenLanguageType(params.language)

        return MultiModalConversationParam.builder()
            .apiKey(config.apiKey)
            .model(MODEL_QWEN3_TTS_FLASH)
            .text(text)
            .voice(voice)
            .languageType(languageType)
            .build()
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
