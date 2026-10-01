package com.github.lonepheasantwarrior.talkify.service.provider.impl

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
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.AliyunBailianConfig
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.provider.AbstractTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TextChunkSplitter
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.service.provider.VOICE_NAME_SEPARATOR
import com.github.lonepheasantwarrior.talkify.service.provider.WavHeaderSanitizer
import com.github.lonepheasantwarrior.talkify.service.provider.impl.AliyunBailianProvider.Companion.ENDPOINT_MUTEX
import io.reactivex.Flowable
import io.reactivex.disposables.Disposable
import io.reactivex.subscribers.DisposableSubscriber
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * 阿里云百炼 - 通义千问3语音合成供应商实现
 *
 * 继承 [AbstractTtsProvider]，实现 TTS 供应商接口
 * 支持流式音频合成，将音频数据块实时回调给系统
 *
 * 供应商 ID：aliyunBailian
 * 供应商：阿里云百炼
 */
class AliyunBailianProvider : AbstractTtsProvider() {

    companion object {
        const val DEFAULT_API_URL = "https://dashscope.aliyuncs.com/api/v1"

        private const val DEFAULT_LANGUAGE = "Auto"

        private const val MAX_TEXT_LENGTH = 500

        /** 支持的语言列表（ISO 639-2 三字母代码） */
        val SUPPORTED_LANGUAGES = arrayOf("zho", "eng", "deu", "ita", "por", "spa", "jpn", "kor", "fra", "rus")

        /**
         * DashScope SDK 请求端点是进程级全局静态（[Constants.baseHttpApiUrl]）：
         * 服务与预览两个实例并行且自定义地址不同时，互相覆写会把对方的请求
         * 路由到错误端点（P1-16）。默认值写入与"端点覆写 + streamCall 订阅"
         * 全部经 [ENDPOINT_MUTEX] 串行化，保证写入与 SDK 读端点成对生效。
         */
        private val ENDPOINT_MUTEX = Any()

        @Volatile
        private var defaultEndpointInitialized = false
    }

    @Volatile
    private var currentDisposable: Disposable? = null

    @Volatile
    private var isCancelled = false

    @Volatile
    private var hasCompleted = false

    init {
        ensureDefaultEndpoint()
    }

    /** 幂等写入默认端点（进程内仅首次生效） */
    private fun ensureDefaultEndpoint() {
        if (!defaultEndpointInitialized) {
            synchronized(ENDPOINT_MUTEX) {
                if (!defaultEndpointInitialized) {
                    Constants.baseHttpApiUrl = DEFAULT_API_URL
                    defaultEndpointInitialized = true
                }
            }
        }
    }

    override fun getProviderId(): String = ProviderIds.AliyunBailian.providerId

    override fun getProviderName(): String = ProviderIds.AliyunBailian.provider

    override fun getDefaultApiUrl(): String = DEFAULT_API_URL

    override fun getDefaultModelId(): String = ProviderIds.AliyunBailian.defaultModelId

    override fun getAudioConfig(): AudioConfig = AudioConfig.QWEN3_TTS

    override fun synthesize(
        text: String, params: SynthesisParams, config: BaseProviderConfig, listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val qwenConfig = config as? AliyunBailianConfig
        if (qwenConfig == null) {
            logError("Invalid config type, expected AliyunBailianConfig")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED))
            return
        }

        if (qwenConfig.apiKey.isEmpty()) {
            logError("API key is not configured")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED))
            return
        }

        val textChunks = TextChunkSplitter.split(text, MAX_TEXT_LENGTH)
        if (textChunks.isEmpty()) {
            logWarning("待朗读文本内容为空")
            listener.onSynthesisCompleted()
            return
        }

        logInfo("Starting streaming synthesis: textLength=${text.length}, chunks=${textChunks.size}, pitch=${params.pitch}, speechRate=${params.speechRate}")
        logDebug("Audio config: ${getAudioConfig().getFormatDescription()}")

        // 会话代际：入口取消旧任务并捕获快照，回调出口校验（P1-15）
        val session = beginSynthesisSession()
        isCancelled = false
        hasCompleted = false
        // 入口取消上一会话的在飞订阅：stop() 是异步语义，dispose 确保旧任务立即终止
        currentDisposable?.dispose()

        processNextChunk(textChunks, 0, params, qwenConfig, listener, session)
    }

    private fun processNextChunk(
        chunks: List<String>,
        index: Int,
        params: SynthesisParams,
        config: AliyunBailianConfig,
        listener: TtsSynthesisListener,
        session: Long
    ) {
        if (isCancelled || hasCompleted || !isSynthesisSessionActive(session)) {
            return
        }

        if (index >= chunks.size) {
            logDebug("All chunks processed")
            hasCompleted = true
            if (isSynthesisSessionActive(session)) {
                listener.onSynthesisCompleted()
            }
            return
        }

        val chunk = chunks[index]
        logDebug("Processing chunk $index/${chunks.size}, length=${chunk.length}")

        try {
            val conversation = MultiModalConversation()
            // 端点覆写与订阅（SDK 在订阅的同步阶段构建请求读取全局端点）
            // 成对持锁，多实例并行时不互相串台（P1-16）
            val resultFlowable: Flowable<MultiModalConversationResult> = synchronized(ENDPOINT_MUTEX) {
                val param = buildConversationParam(chunk, params, config)
                conversation.streamCall(param)
            }

            currentDisposable = resultFlowable.subscribeWith(
                createChunkSubscriber(
                    chunks, index, params, config, listener, session
                )
            )
        } catch (e: Exception) {
            val (errorCode, errorMessage) = mapExceptionToErrorCode(e)
            logError("Synthesis error: $errorMessage", e)
            if (isSynthesisSessionActive(session)) {
                listener.onError(TtsErrorCode.getErrorMessage(errorCode, errorMessage))
            }
        }
    }

    private fun mapExceptionToErrorCode(e: Exception): Pair<Int, String> {
        return when (e) {
            is NoApiKeyException -> {
                TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED to "API Key 未配置"
            }

            is UploadFileException -> {
                TtsErrorCode.ERROR_SYNTHESIS_FAILED to (e.message ?: "Upload failed")
            }

            is ApiException -> {
                val message = e.message ?: ""
                val errorCode = when {
                    message.contains("rate limit", ignoreCase = true) || message.contains(
                        "429",
                        ignoreCase = true
                    ) -> {
                        TtsErrorCode.ERROR_API_RATE_LIMITED
                    }

                    message.contains("401", ignoreCase = true) || message.contains(
                        "Unauthorized",
                        ignoreCase = true
                    ) || message.contains("invalid api_key", ignoreCase = true) -> {
                        TtsErrorCode.ERROR_API_AUTH_FAILED
                    }

                    message.contains("500", ignoreCase = true) || message.contains(
                        "502",
                        ignoreCase = true
                    ) || message.contains("503", ignoreCase = true) || message.contains(
                        "504",
                        ignoreCase = true
                    ) -> {
                        TtsErrorCode.ERROR_API_SERVER_ERROR
                    }

                    else -> {
                        TtsErrorCode.ERROR_SYNTHESIS_FAILED
                    }
                }
                errorCode to message
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
        config: AliyunBailianConfig,
        listener: TtsSynthesisListener,
        session: Long
    ): DisposableSubscriber<MultiModalConversationResult> {
        return object : DisposableSubscriber<MultiModalConversationResult>() {
            private var isFirstChunk = index == 0
            // 用于跟踪当前文本块的第一个音频数据包，以便剥离可能存在的 WAV 头
            private var isFirstAudioPacket = true

            override fun onStart() {
                super.onStart()
                if (!isSynthesisSessionActive(session)) {
                    // 过期会话的迟到订阅：立即终止，不触达监听器（P1-15）
                    cancel()
                    return
                }
                if (isFirstChunk) {
                    listener.onSynthesisStarted()
                    isFirstChunk = false
                }
            }

            override fun onNext(result: MultiModalConversationResult) {
                if (isCancelled || hasCompleted || !isSynthesisSessionActive(session)) {
                    return
                }

                try {
                    var audioData = extractAudioData(result)
                    if (audioData != null && audioData.isNotEmpty()) {

                        // 【核心修复】：如果是第一个数据包，检查并剥离 WAV 文件头
                        if (isFirstAudioPacket) {
                            val stripped = WavHeaderSanitizer.stripWavHeader(audioData)
                            if (stripped !== audioData) {
                                logInfo("Detected WAV header in stream, stripping the first 44 bytes to prevent audio cracking.")
                            }
                            audioData = stripped
                            isFirstAudioPacket = false
                        }

                        // 二次校验，防止剥离头文件后数据为空
                        if (audioData.isNotEmpty()) {
                            logDebug("Received audio chunk: ${audioData.size} bytes")
                            listener.onAudioAvailable(
                                audioData,
                                getAudioConfig().sampleRate,
                                getAudioConfig().audioFormat,
                                getAudioConfig().channelCount
                            )
                        }
                    }
                } catch (e: Exception) {
                    logError("Error processing audio chunk", e)
                    val (errorCode, errorMessage) = mapExceptionToErrorCode(e)
                    // G3/N1：出口校验——与下方 onError override 的同名校验对齐，
                    // stop() 作废会话后音频处理异常不得穿透到监听器
                    if (isSynthesisSessionActive(session)) {
                        listener.onError(TtsErrorCode.getErrorMessage(errorCode, errorMessage))
                    }
                    dispose()
                }
            }

            override fun onError(throwable: Throwable) {
                logError("Stream error for chunk $index", throwable)
                // throwable 可能是非 Exception 的 Throwable（如 Error），避免强转抛 ClassCastException
                val (errorCode, errorMessage) = mapExceptionToErrorCode(throwable as? Exception ?: Exception(throwable))
                // 过期会话的错误静默丢弃（P1-15）
                if (isSynthesisSessionActive(session)) {
                    listener.onError(TtsErrorCode.getErrorMessage(errorCode, errorMessage))
                }
            }

            override fun onComplete() {
                logDebug("Chunk $index completed")
                if (!isCancelled && !hasCompleted && isSynthesisSessionActive(session)) {
                    processNextChunk(chunks, index + 1, params, config, listener, session)
                }
            }
        }
    }

    private fun buildConversationParam(
        text: String, params: SynthesisParams, config: AliyunBailianConfig
    ): MultiModalConversationParam {
        val voice = if (config.voiceId.isNotEmpty()) {
            parseVoice(config.voiceId)
        } else {
            logWarning("Voice ID not configured, using default CHERRY")
            AudioParameters.Voice.CHERRY
        }

        val languageType = convertToQwenLanguageType(params.language)

        // 用户自定义 API 地址优先，为空时回退到默认地址。
        // 调用方已在 ENDPOINT_MUTEX 内（processNextChunk）：Constants.baseHttpApiUrl
        // 是 SDK 全局静态，覆写必须与 streamCall 订阅成对持锁（P1-16）
        val effectiveApiUrl = config.apiUrl.ifBlank { DEFAULT_API_URL }
        if (effectiveApiUrl != Constants.baseHttpApiUrl) {
            Constants.baseHttpApiUrl = effectiveApiUrl
        }

        // 用户自定义模型 ID 优先，为空时回退到默认模型
        val effectiveModel = config.modelId.ifBlank { getDefaultModelId() }

        return MultiModalConversationParam.builder().apiKey(config.apiKey)
            .model(effectiveModel).text(text).voice(voice).languageType(languageType)
            // 【保险参数】：主动向云端请求 PCM 裸流（部分版本 SDK/大模型已支持该参数）
            .parameter("format", "pcm")
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

    private fun extractAudioData(result: MultiModalConversationResult): ByteArray? {
        return try {
            val output = result.output ?: return null
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
        return SUPPORTED_LANGUAGES.toSet()
    }

    override fun createDefaultLanguage(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.isO3Language, Locale.SIMPLIFIED_CHINESE.isO3Country, "")
    }

    override fun getSupportedVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()

        for (langCode in getSupportedLanguages()) {
            for (providerVoice in AudioParameters.Voice.entries) {
                voices.add(
                    Voice(
                        "${providerVoice.value}$VOICE_NAME_SEPARATOR$langCode",
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

    override fun getDefaultVoiceId(lang: String?, country: String?, variant: String?, currentVoiceId: String?): String {
        if (!currentVoiceId.isNullOrBlank()) {
            return "$currentVoiceId$VOICE_NAME_SEPARATOR$lang"
        }
        return "${AudioParameters.Voice.CHERRY.value}$VOICE_NAME_SEPARATOR$lang"
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        if (voiceId == null) {
            return false
        }
        return AudioParameters.Voice.entries.any { it.value == extractRealVoiceName(voiceId) }
    }

    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
        // 作废当前会话代际：停止后到达的残留回调被出口校验静默丢弃（P1-15）
        invalidateSynthesisSession()
        currentDisposable?.dispose()
        currentDisposable = null
    }

    override fun release() {
        logInfo("Releasing provider")
        isCancelled = true
        invalidateSynthesisSession()
        currentDisposable?.dispose()
        currentDisposable = null
        super.release()
    }

    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        return isConfiguredAs(config) { c: AliyunBailianConfig -> c.apiKey.isNotBlank() }
    }

    override fun createDefaultConfig(): BaseProviderConfig {
        return AliyunBailianConfig()
    }

    override val configLabels: Map<String, Int>
        get() = mapOf("api_key" to R.string.api_key_label)
}