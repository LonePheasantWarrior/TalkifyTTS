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
import com.tencent.cloud.stream.tts.core.ws.Credential
import com.tencent.cloud.stream.tts.core.ws.SpeechClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

class TencentTtsEngine : AbstractTtsEngine() {

    companion object {
        const val ENGINE_ID = "tencent-tts"
        const val ENGINE_NAME = "腾讯语音合成"
        private const val VOICE_NAME_SEPARATOR = "::"

        private const val MAX_TEXT_LENGTH = 300

        private val speechClient = SpeechClient()

        val SUPPORTED_LANGUAGES = arrayOf("zho", "eng")
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isCancelled = false

    @Volatile
    private var hasCompleted = false

    @Volatile
    private var currentSynthesizer: FlowingSpeechSynthesizer? = null

    @Volatile
    private var isFirstChunk = true

    private val voiceSampleRateMap: MutableMap<String, Int> by lazy {
        loadVoiceSampleRatesFromResource()
    }

    private val voiceIds: List<String> by lazy {
        loadVoiceIdsFromResource()
    }

    val audioConfig: AudioConfig
        @JvmName("getAudioConfigProperty") get() = AudioConfig.TENCENT_TTS

    private fun loadVoiceIdsFromResource(): List<String> {
        val context = TalkifyAppHolder.getContext()
        return if (context != null) {
            try {
                val premiumVoices = context.resources.getStringArray(R.array.tencent_premium_tts_voices)
                val llmVoices = context.resources.getStringArray(R.array.tencent_llm_tts_voices)
                val naturalVoices = context.resources.getStringArray(R.array.tencent_natural_tts_voices)
                (premiumVoices + llmVoices + naturalVoices).toList()
            } catch (e: Exception) {
                TtsLogger.e("Failed to load voice IDs from resource", throwable = e)
                emptyList()
            }
        } else {
            TtsLogger.w("Context not available, voice IDs will be empty")
            emptyList()
        }
    }

    private fun loadVoiceSampleRatesFromResource(): MutableMap<String, Int> {
        val context = TalkifyAppHolder.getContext()
        val map = mutableMapOf<String, Int>()
        if (context != null) {
            try {
                loadVoiceRatesFromArray(
                    context.resources.getStringArray(R.array.tencent_premium_tts_voices),
                    context.resources.getStringArray(R.array.tencent_premium_tts_voice_sample_rates),
                    map
                )
                loadVoiceRatesFromArray(
                    context.resources.getStringArray(R.array.tencent_llm_tts_voices),
                    context.resources.getStringArray(R.array.tencent_llm_tts_voice_sample_rates),
                    map
                )
                loadVoiceRatesFromArray(
                    context.resources.getStringArray(R.array.tencent_natural_tts_voices),
                    context.resources.getStringArray(R.array.tencent_natural_tts_voice_sample_rates),
                    map
                )
            } catch (e: Exception) {
                TtsLogger.e("Failed to load voice sample rates from resource", throwable = e)
            }
        }
        return map
    }

    private fun loadVoiceRatesFromArray(
        voiceIds: Array<String>,
        sampleRates: Array<String>,
        map: MutableMap<String, Int>
    ) {
        voiceIds.zip(sampleRates).forEach { (voiceId, sampleRateStr) ->
            map[voiceId] = parseSampleRate(sampleRateStr)
        }
    }

    private fun parseSampleRate(sampleRateStr: String): Int {
        return try {
            val rates = sampleRateStr.split("/")
                .map { it.trim().lowercase() }
                .mapNotNull { rateStr ->
                    when {
                        rateStr.contains("24k") -> 24000
                        rateStr.contains("16k") -> 16000
                        rateStr.contains("8k") -> 8000
                        else -> null
                    }
                }
            rates.maxOrNull() ?: 16000
        } catch (_: Exception) {
            16000
        }
    }

    private fun getSampleRateForVoice(voiceId: String): Int {
        return voiceSampleRateMap[voiceId] ?: 16000
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
            logError("AppID or SecretID or SecretKey is not configured")
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

        val realVoiceId = extractRealVoiceName(tencentConfig.voiceId) ?: voiceIds.firstOrNull() ?: "101001"
        val sampleRate = getSampleRateForVoice(realVoiceId)

        logInfo("Starting synthesis: textLength=${text.length}, voiceId=$realVoiceId, sampleRate=$sampleRate, pitch=${params.pitch}, speechRate=${params.speechRate}")
        logDebug("Audio config: sampleRate=$sampleRate, format=PCM_16BIT, channel=mono")

        isCancelled = false
        hasCompleted = false
        isFirstChunk = true

        val textChunks = splitTextIntoChunks(text)
        if (textChunks.isEmpty()) {
            listener.onError("文本为空")
            return
        }

        logDebug("Text split into ${textChunks.size} chunks")

        engineScope.launch {
            processChunksSequentially(textChunks, tencentConfig, params, realVoiceId, sampleRate, listener)
        }
    }

    private suspend fun processChunksSequentially(
        chunks: List<String>,
        config: TencentTtsConfig,
        params: SynthesisParams,
        voiceId: String,
        sampleRate: Int,
        listener: TtsSynthesisListener
    ) {
        for ((index, chunk) in chunks.withIndex()) {
            if (isCancelled || hasCompleted) {
                logDebug("Synthesis cancelled or completed, stopping chunk processing")
                return
            }

            logDebug("Processing chunk $index/${chunks.size}, length=${chunk.length}")

            val success = processSingleChunk(chunk, config, params, voiceId, sampleRate, listener)
            if (!success) {
                logError("Failed to process chunk $index")
                return
            }
        }

        if (!isCancelled && !hasCompleted) {
            hasCompleted = true
            withContext(Dispatchers.Main) {
                listener.onSynthesisCompleted()
            }
            logInfo("Synthesis completed successfully")
        }
    }

    private suspend fun processSingleChunk(
        text: String,
        config: TencentTtsConfig,
        params: SynthesisParams,
        voiceId: String,
        sampleRate: Int,
        listener: TtsSynthesisListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val credential = Credential(config.appId, config.secretId, config.secretKey, "")
            val request = buildTtsRequest(params, voiceId, sampleRate)

            val chunkStarted = false
            val chunkCompleted = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val hasError = false

            val ttsListener = object : FlowingSpeechSynthesizerListener() {
                override fun onSynthesisStart(response: SpeechSynthesizerResponse?) {
                    logDebug("onSynthesisStart: sessionId=${response?.sessionId}")
                    if (!chunkStarted && isFirstChunk) {
                        isFirstChunk = false
                        listener.onSynthesisStarted()
                    }
                }

                override fun onSynthesisEnd(response: SpeechSynthesizerResponse?) {
                    logDebug("onSynthesisEnd: sessionId=${response?.sessionId}")
                    chunkCompleted.complete(!hasError)
                }

                override fun onAudioResult(buffer: ByteBuffer?) {
                    if (buffer != null && buffer.remaining() > 0) {
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        logDebug("Received audio chunk: ${data.size} bytes")
                        listener.onAudioAvailable(
                            data,
                            sampleRate,
                            audioConfig.audioFormat,
                            audioConfig.channelCount
                        )
                    }
                }

                override fun onTextResult(response: SpeechSynthesizerResponse?) {
                    logDebug("onTextResult: ${response?.result}")
                }

                override fun onSynthesisCancel() {
                    logDebug("onSynthesisCancel")
                    chunkCompleted.complete(false)
                }

                override fun onSynthesisFail(response: SpeechSynthesizerResponse?) {
                    val errorMsg = response?.message ?: "Unknown error"
                    logError("onSynthesisFail: $errorMsg, code=${response?.code}")
                    engineScope.launch(Dispatchers.Main) {
                        listener.onError("语音合成失败: $errorMsg (code=${response?.code})")
                    }
                    chunkCompleted.complete(false)
                }
            }

            currentSynthesizer = FlowingSpeechSynthesizer(speechClient, credential, request, ttsListener)

            if (isCancelled) {
                return@withContext false
            }

            currentSynthesizer?.start()
            currentSynthesizer?.process(text)
            currentSynthesizer?.stop()

            val success = chunkCompleted.await()

            currentSynthesizer = null

            success
        } catch (e: Exception) {
            logError("Unexpected error during synthesis", e)
            withContext(Dispatchers.Main) {
                listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
            }
            false
        } finally {
            currentSynthesizer = null
        }
    }

    private fun buildTtsRequest(
        params: SynthesisParams,
        voiceId: String,
        sampleRate: Int
    ): FlowingSpeechSynthesizerRequest {
        val request = FlowingSpeechSynthesizerRequest()

        request.setCodec("pcm")
        request.setSampleRate(sampleRate)
        request.setVoiceType(voiceId.toIntOrNull() ?: 101001)
        request.setEnableSubtitle(false)
        request.setEmotionCategory("neutral")
        request.setEmotionIntensity(100)
        request.setSessionId(UUID.randomUUID().toString())

        val speed = convertSpeechRate(params.speechRate)
        request.setSpeed(speed)
        logDebug("ttsSpeechRate: ${params.speechRate}, tencentSpeed: $speed")

        val volume = convertVolume(params.volume)
        request.setVolume(volume)
        logDebug("ttsVolume: ${params.volume}, tencentVolume: $volume")

        return request
    }

    private fun convertSpeechRate(androidRate: Float): Float {
        return when {
            androidRate <= 50f -> -2f
            androidRate <= 80f -> -1f
            androidRate <= 120f -> 0f
            androidRate <= 150f -> 1f
            androidRate <= 200f -> 2f
            else -> 6f
        }
    }

    private fun convertVolume(androidVolume: Float): Float {
        return when {
            androidVolume <= 0f -> -10f
            androidVolume >= 1f -> 10f
            else -> (androidVolume - 0.5f) * 20f
        }
    }

    private fun splitTextIntoChunks(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= MAX_TEXT_LENGTH) return listOf(text)

        val chunks = mutableListOf<String>()
        var lastSplitPos = 0

        var i = 0
        while (i < text.length) {
            val remainingLength = text.length - lastSplitPos

            if (remainingLength <= MAX_TEXT_LENGTH) {
                chunks.add(text.substring(lastSplitPos))
                break
            }

            val isSentenceEnd = checkSentenceEnd(text, i)
            val isMidPause = checkMidPause(text, i)

            if (isSentenceEnd || isMidPause) {
                val chunkLength = i - lastSplitPos + 1
                if (chunkLength <= MAX_TEXT_LENGTH) {
                    chunks.add(text.substring(lastSplitPos, i + 1))
                    lastSplitPos = i + 1
                    i++
                    continue
                }
            }

            val splitPos = findBestSplitPos(text, lastSplitPos)
            if (splitPos > lastSplitPos) {
                chunks.add(text.substring(lastSplitPos, splitPos))
                lastSplitPos = splitPos
            } else {
                chunks.add(text.substring(lastSplitPos, lastSplitPos + MAX_TEXT_LENGTH))
                lastSplitPos += MAX_TEXT_LENGTH
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

    private fun findBestSplitPos(text: String, startPos: Int): Int {
        val searchEnd = minOf(startPos + MAX_TEXT_LENGTH, text.length)

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
        val defaultVoice = voiceIds.firstOrNull() ?: "101001"
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
        currentSynthesizer?.cancel()
        currentSynthesizer = null
    }

    override fun release() {
        logInfo("Releasing engine")
        isCancelled = true
        currentSynthesizer?.cancel()
        currentSynthesizer = null
        engineScope.cancel()
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
