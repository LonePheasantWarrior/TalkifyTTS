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

        private const val DEFAULT_LANGUAGE = "Chinese"
    }

    @Volatile
    private var currentDisposable: Disposable? = null

    @Volatile
    private var isCancelled = false

    private var hasCompleted = false

    private val audioConfig: AudioConfig
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

        logInfo("Starting streaming synthesis: textLength=${text.length}, pitch=${params.pitch}, speechRate=${params.speechRate}")
        logDebug("Audio config: ${audioConfig.getFormatDescription()}")

        isCancelled = false
        hasCompleted = false

        try {
            val conversation = MultiModalConversation()
            val param = buildConversationParam(text, params, config)
            val resultFlowable: Flowable<MultiModalConversationResult> = conversation.streamCall(param)

            currentDisposable = resultFlowable.subscribeWith(createSubscriber(listener))
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

        return MultiModalConversationParam.builder()
            .apiKey(config.apiKey)
            .model(MODEL_QWEN3_TTS_FLASH)
            .text(text)
            .voice(voice)
            .languageType(DEFAULT_LANGUAGE)
            .build()
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

    private fun createSubscriber(listener: TtsSynthesisListener): DisposableSubscriber<MultiModalConversationResult> {
        return object : DisposableSubscriber<MultiModalConversationResult>() {
            override fun onStart() {
                super.onStart()
                listener.onSynthesisStarted()
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
                logError("Stream error", throwable)
                val errorMessage = when (throwable) {
                    is ApiException -> "API 错误：${throwable.message}"
                    else -> "合成失败：${throwable.message}"
                }
                listener.onError(errorMessage)
            }

            override fun onComplete() {
                logDebug("Stream completed")
                if (!isCancelled && !hasCompleted) {
                    hasCompleted = true
                    listener.onSynthesisCompleted()
                }
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
