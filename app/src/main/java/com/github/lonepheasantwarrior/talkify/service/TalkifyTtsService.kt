package com.github.lonepheasantwarrior.talkify.service

import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngineRegistry
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsConfigRepository
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineApi
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Talkify TTS 服务
 *
 * 实现 [TextToSpeechService]，作为系统 TTS 框架与本应用引擎之间的桥梁
 * 负责：
 * 1. 根据用户选择的引擎 ID 获取对应的合成引擎
 * 2. 获取用户配置的引擎设置
 * 3. 委托引擎执行实际的语音合成
 *
 * 采用仓储模式获取配置，支持多引擎切换
 *
 * @property currentEngine 当前引擎实例
 * @property currentEngineId 当前引擎 ID
 * @property currentConfig 当前引擎配置
 */
class TalkifyTtsService : TextToSpeechService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val requestQueue = LinkedBlockingQueue<SynthesisRequestWrapper>()
    private val processingSemaphore = Semaphore(1)
    private var isStopped = AtomicBoolean(false)

    private var appConfigRepository: AppConfigRepository? = null
    private var engineConfigRepository: EngineConfigRepository? = null
    private var currentEngine: TtsEngineApi? = null
    private var currentEngineId: String? = null
    private var currentConfig: EngineConfig? = null
    private var currentPlayer: CompatibilityModePlayer? = null

    private data class SynthesisRequestWrapper(
        val request: android.speech.tts.SynthesisRequest,
        val callback: android.speech.tts.SynthesisCallback
    )

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i("TalkifyTtsService onCreate")
        initializeRepositories()
        initializeEngine()
        startRequestProcessor()
    }

    private fun startRequestProcessor() {
        serviceScope.launch {
            while (!isStopped.get()) {
                try {
                    val wrapper = requestQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (wrapper != null) {
                        processingSemaphore.acquire()
                        processRequestInternal(wrapper)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    TtsLogger.e("Critical error in request processor", e)
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
    }

    private fun processRequestInternal(wrapper: SynthesisRequestWrapper) {
        val (request, callback) = wrapper

        if (isStopped.get()) {
            TtsLogger.d("processRequestInternal: service is stopped, skipping request")
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            processingSemaphore.release()
            return
        }

        val text = request.charSequenceText?.toString()

        if (text.isNullOrBlank()) {
            TtsLogger.w("processRequestInternal: empty text")
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            processingSemaphore.release()
            return
        }

        TtsLogger.d("processRequestInternal: text length = ${text.length}")

        val engine = currentEngine
        if (engine == null) {
            TtsLogger.e("processRequestInternal: no engine available")
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
            processingSemaphore.release()
            return
        }

        val config = currentConfig
        if (config == null) {
            TtsLogger.e("processRequestInternal: no config available")
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_CONFIG_NOT_FOUND))
            processingSemaphore.release()
            return
        }

        if (!engine.isConfigured(config)) {
            TtsLogger.w("processRequestInternal: engine not configured")
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            processingSemaphore.release()
            return
        }

        val params = SynthesisParams(
            pitch = request.pitch.toFloat(),
            speechRate = request.speechRate.toFloat(),
            volume = 1.0f,
            language = request.language
        )

        val isCompatibilityMode = appConfigRepository?.isCompatibilityModeEnabled() == true

        try {
            val audioConfig = engine.getAudioConfig()
            callback.start(audioConfig.sampleRate, audioConfig.audioFormat, audioConfig.channelCount)
            TtsLogger.d("Synthesis started, compatibilityMode=$isCompatibilityMode")

            if (isCompatibilityMode) {
                processWithCompatibilityMode(text, params, config, audioConfig, callback)
            } else {
                processWithoutCompatibilityMode(text, params, config, callback)
            }
        } catch (e: Exception) {
            TtsLogger.e("Synthesis failed", e)
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
            processingSemaphore.release()
        }
    }

    private fun processWithCompatibilityMode(
        text: String,
        params: SynthesisParams,
        config: EngineConfig,
        audioConfig: com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig,
        callback: android.speech.tts.SynthesisCallback
    ) {
        val engine = currentEngine ?: run {
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
            processingSemaphore.release()
            return
        }

        if (currentPlayer == null) {
            currentPlayer = CompatibilityModePlayer(audioConfig)
            if (currentPlayer?.initialize() != true) {
                TtsLogger.e("Failed to initialize compatibility player")
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
                processingSemaphore.release()
                return
            }
        }

        val player = currentPlayer!!

        engine.synthesize(text, params, config, object : TtsSynthesisListener {
            override fun onSynthesisStarted() {
                TtsLogger.d("Compatibility mode: synthesis started")
            }

            override fun onAudioAvailable(
                audioData: ByteArray,
                sampleRate: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                val maxChunkSize = 4096
                var offset = 0
                while (offset < audioData.size) {
                    val chunkSize = minOf(maxChunkSize, audioData.size - offset)
                    val chunk = audioData.copyOfRange(offset, offset + chunkSize)
                    callback.audioAvailable(chunk, 0, chunk.size)
                    offset += chunkSize
                }
                player.playAllAndWait(audioData)
            }

            override fun onSynthesisCompleted() {
                TtsLogger.d("Compatibility mode: synthesis completed, waiting for playback")
            }

            override fun onError(error: String) {
                TtsLogger.e("Compatibility mode: synthesis error: $error")
                val errorCode = inferErrorCodeFromMessage(error)
                callback.error(TtsErrorCode.toAndroidError(errorCode))
                processingSemaphore.release()
            }
        })
    }

    private fun processWithoutCompatibilityMode(
        text: String,
        params: SynthesisParams,
        config: EngineConfig,
        callback: android.speech.tts.SynthesisCallback
    ) {
        val engine = currentEngine ?: run {
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
            processingSemaphore.release()
            return
        }

        engine.synthesize(text, params, config, object : TtsSynthesisListener {
            override fun onSynthesisStarted() {
                TtsLogger.d("Synthesis started callback")
            }

            override fun onAudioAvailable(
                audioData: ByteArray,
                sampleRate: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                val maxChunkSize = 4096
                var offset = 0
                while (offset < audioData.size) {
                    val chunkSize = minOf(maxChunkSize, audioData.size - offset)
                    val chunk = audioData.copyOfRange(offset, offset + chunkSize)
                    callback.audioAvailable(chunk, 0, chunk.size)
                    offset += chunkSize
                }
            }

            override fun onSynthesisCompleted() {
                TtsLogger.d("Synthesis completed")
                callback.done()
                processingSemaphore.release()
            }

            override fun onError(error: String) {
                TtsLogger.e("Synthesis error: $error")
                val errorCode = inferErrorCodeFromMessage(error)
                callback.error(TtsErrorCode.toAndroidError(errorCode))
                processingSemaphore.release()
            }
        })
    }

    private fun initializeRepositories() {
        TtsLogger.d("Initializing repositories")
        try {
            appConfigRepository = SharedPreferencesAppConfigRepository(applicationContext)
            engineConfigRepository = Qwen3TtsConfigRepository(applicationContext)
            TtsLogger.i("Repositories initialized successfully")
        } catch (e: Exception) {
            TtsLogger.e("Failed to initialize repositories", e)
        }
    }

    private fun inferErrorCodeFromMessage(errorMessage: String): Int {
        return when {
            errorMessage.contains("API Key", ignoreCase = true) ||
            errorMessage.contains("认证", ignoreCase = true) ||
            errorMessage.contains("auth", ignoreCase = true) -> {
                TtsErrorCode.ERROR_API_AUTH_FAILED
            }
            errorMessage.contains("超时", ignoreCase = true) ||
            errorMessage.contains("timeout", ignoreCase = true) -> {
                TtsErrorCode.ERROR_NETWORK_TIMEOUT
            }
            errorMessage.contains("网络", ignoreCase = true) ||
            errorMessage.contains("连接", ignoreCase = true) ||
            errorMessage.contains("network", ignoreCase = true) ||
            errorMessage.contains("connect", ignoreCase = true) -> {
                TtsErrorCode.ERROR_NETWORK_UNAVAILABLE
            }
            errorMessage.contains("频率", ignoreCase = true) ||
            errorMessage.contains("rate limit", ignoreCase = true) ||
            errorMessage.contains("429", ignoreCase = true) -> {
                TtsErrorCode.ERROR_API_RATE_LIMITED
            }
            errorMessage.contains("服务器", ignoreCase = true) ||
            errorMessage.contains("server", ignoreCase = true) ||
            errorMessage.contains("500", ignoreCase = true) ||
            errorMessage.contains("502", ignoreCase = true) ||
            errorMessage.contains("503", ignoreCase = true) -> {
                TtsErrorCode.ERROR_API_SERVER_ERROR
            }
            errorMessage.contains("API Key", ignoreCase = true) ||
            errorMessage.contains("配置", ignoreCase = true) -> {
                TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED
            }
            else -> TtsErrorCode.ERROR_SYNTHESIS_FAILED
        }
    }

    private fun initializeEngine() {
        val selectedEngineId = appConfigRepository?.getSelectedEngineId()
        val engineId = selectedEngineId ?: run {
            TtsLogger.w("No selected engine found, using default")
            TtsEngineRegistry.defaultEngine.id
        }

        TtsLogger.d("Initializing engine: $engineId")

        if (currentEngineId != engineId) {
            TtsLogger.i("Engine changed from $currentEngineId to $engineId, reinitializing")
            currentEngine?.release()
            currentEngine = TtsEngineFactory.createEngine(engineId)
            currentEngineId = engineId

            if (currentEngine == null) {
                TtsLogger.e("Failed to create engine: $engineId")
                return
            }
        }

        val ttsEngine = TtsEngineRegistry.getEngine(engineId)
        if (ttsEngine == null) {
            TtsLogger.e("Engine not found in registry: $engineId")
            return
        }

        currentConfig = engineConfigRepository?.getConfig(engineId)
        TtsLogger.d("Engine initialized: ${currentEngine?.getEngineName()}")
    }

    override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        val locale = when {
            lang != null && country != null && variant != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(convertToValidRegionCode(country))
                .setVariant(variant)
                .build()
            lang != null && country != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(convertToValidRegionCode(country))
                .build()
            lang != null -> Locale.Builder()
                .setLanguage(lang)
                .build()
            else -> {
                TtsLogger.w("onIsLanguageAvailable: null language, returning NOT_SUPPORTED")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        val result = when (locale.language) {
            "zh", "en", "de", "it", "pt", "es", "ja", "ko", "fr", "ru" -> {
                TtsLogger.d("Language available: ${locale.language}")
                TextToSpeech.LANG_AVAILABLE
            }
            else -> {
                TtsLogger.w("Language not supported: ${locale.language}")
                TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        return result
    }

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        val locale = when {
            lang != null && country != null && variant != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(convertToValidRegionCode(country))
                .setVariant(variant)
                .build()
            lang != null && country != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(convertToValidRegionCode(country))
                .build()
            lang != null -> Locale.Builder()
                .setLanguage(lang)
                .build()
            else -> {
                TtsLogger.w("onLoadLanguage: null language, returning NOT_SUPPORTED")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        val result = when (locale.language) {
            "zh", "en", "de", "it", "pt", "es", "ja", "ko", "fr", "ru" -> {
                TtsLogger.i("Language loaded: ${locale.language}")
                TextToSpeech.LANG_AVAILABLE
            }
            else -> {
                TtsLogger.w("Language not supported: ${locale.language}")
                TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        return result
    }

    private fun convertToValidRegionCode(country: String): String {
        return when (country.uppercase()) {
            "CHN", "CN" -> "CN"
            "USA", "US" -> "US"
            "GBR", "GB" -> "GB"
            "JPN", "JP" -> "JP"
            "DEU", "DE" -> "DE"
            "FRA", "FR" -> "FR"
            "KOR", "KR" -> "KR"
            else -> if (country.length == 2) country.uppercase() else "US"
        }
    }

    override fun onGetLanguage(): Array<String>? {
        val engine = currentEngine
        if (engine == null) {
            TtsLogger.w("onGetLanguage: no engine available")
            return null
        }

        val config = currentConfig
        val isAvailable = config?.apiKey?.isNotBlank() == true

        if (!isAvailable) {
            TtsLogger.w("onGetLanguage: engine not configured")
            return null
        }

        TtsLogger.d("onGetLanguage: returning supported languages")
        return arrayOf("zh", "en", "de", "it", "pt", "es", "ja", "ko", "fr", "ru")
    }

    override fun onSynthesizeText(
        request: android.speech.tts.SynthesisRequest?,
        callback: android.speech.tts.SynthesisCallback?
    ) {
        if (request == null || callback == null) {
            TtsLogger.e("onSynthesizeText: null request or callback")
            return
        }

        val isCompatibilityMode = appConfigRepository?.isCompatibilityModeEnabled() == true

        if (isCompatibilityMode) {
            processRequestSynchronously(request, callback)
        } else {
            TtsLogger.d("onSynthesizeText: queuing request, queue size = ${requestQueue.size + 1}")
            requestQueue.put(SynthesisRequestWrapper(request, callback))
        }
    }

    private fun processRequestSynchronously(
        request: android.speech.tts.SynthesisRequest,
        callback: android.speech.tts.SynthesisCallback
    ) {
        if (isStopped.get()) {
            TtsLogger.d("processRequestSynchronously: service is stopped, skipping request")
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }

        val text = request.charSequenceText?.toString()

        if (text.isNullOrBlank()) {
            TtsLogger.w("processRequestSynchronously: empty text")
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }

        TtsLogger.d("processRequestSynchronously: compatibility mode, text length = ${text.length}")

        val engine = currentEngine
        if (engine == null) {
            TtsLogger.e("processRequestSynchronously: no engine available")
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
            return
        }

        val config = currentConfig
        if (config == null) {
            TtsLogger.e("processRequestSynchronously: no config available")
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_CONFIG_NOT_FOUND))
            return
        }

        if (!engine.isConfigured(config)) {
            TtsLogger.w("processRequestSynchronously: engine not configured")
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        val params = SynthesisParams(
            pitch = request.pitch.toFloat(),
            speechRate = request.speechRate.toFloat(),
            volume = 1.0f,
            language = request.language
        )

        try {
            val audioConfig = engine.getAudioConfig()
            callback.start(audioConfig.sampleRate, audioConfig.audioFormat, audioConfig.channelCount)

            if (currentPlayer == null) {
                currentPlayer = CompatibilityModePlayer(audioConfig)
                if (currentPlayer?.initialize() != true) {
                    TtsLogger.e("Failed to initialize compatibility player")
                    callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
                    return
                }
            }

            val player = currentPlayer!!

            val synthesisLatch = java.util.concurrent.CountDownLatch(1)
            var synthesisError: String? = null

            engine.synthesize(text, params, config, object : TtsSynthesisListener {
                override fun onSynthesisStarted() {
                    TtsLogger.d("processRequestSynchronously: synthesis started")
                }

                override fun onAudioAvailable(
                    audioData: ByteArray,
                    sampleRate: Int,
                    audioFormat: Int,
                    channelCount: Int
                ) {
                    player.playAllAndWait(audioData)
                }

                override fun onSynthesisCompleted() {
                    TtsLogger.d("processRequestSynchronously: synthesis completed")
                    synthesisLatch.countDown()
                }

                override fun onError(error: String) {
                    TtsLogger.e("processRequestSynchronously: synthesis error: $error")
                    synthesisError = error
                    synthesisLatch.countDown()
                }
            })

            try {
                synthesisLatch.await(120, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
                return
            }

            if (synthesisError != null) {
                val code = inferErrorCodeFromMessage(synthesisError!!)
                callback.error(TtsErrorCode.toAndroidError(code))
            } else {
                callback.done()
            }
        } catch (e: Exception) {
            TtsLogger.e("processRequestSynchronously: Synthesis failed", e)
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
        }
    }

    override fun onDestroy() {
        TtsLogger.i("TalkifyTtsService onDestroy")
        isStopped.set(true)
        currentEngine?.stop()
        currentEngine?.release()
        currentEngine = null
        currentConfig = null
        currentEngineId = null
        currentPlayer?.release()
        currentPlayer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStop() {
        TtsLogger.d("onStop called")
        currentEngine?.stop()

        currentPlayer?.release()
        currentPlayer = null

        requestQueue.clear()
        if (processingSemaphore.availablePermits() == 0) {
            processingSemaphore.release()
        }
    }
}
