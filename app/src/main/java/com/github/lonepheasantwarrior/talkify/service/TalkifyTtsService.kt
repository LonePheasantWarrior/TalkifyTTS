package com.github.lonepheasantwarrior.talkify.service

import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngineRegistry
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsConfigRepository
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineApi
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import java.util.Locale

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

    private var appConfigRepository: AppConfigRepository? = null
    private var engineConfigRepository: EngineConfigRepository? = null
    private var currentEngine: TtsEngineApi? = null
    private var currentEngineId: String? = null
    private var currentConfig: EngineConfig? = null

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i("TalkifyTtsService onCreate")
        initializeRepositories()
        initializeEngine()
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

        currentConfig = engineConfigRepository?.getConfig(ttsEngine)
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
                .setRegion(country)
                .setVariant(variant)
                .build()
            lang != null && country != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(country)
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
            "zh", "en" -> {
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
                .setRegion(country)
                .setVariant(variant)
                .build()
            lang != null && country != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(country)
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
            "zh", "en" -> {
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
        return arrayOf("zh", "en")
    }

    override fun onSynthesizeText(
        request: android.speech.tts.SynthesisRequest?,
        callback: android.speech.tts.SynthesisCallback?
    ) {
        if (request == null || callback == null) {
            TtsLogger.e("onSynthesizeText: null request or callback")
            return
        }

        @Suppress("DEPRECATION")
        val text = request.text
        if (text.isNullOrBlank()) {
            TtsLogger.w("onSynthesizeText: empty text")
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }

        TtsLogger.d("onSynthesizeText: text length = ${text.length}")

        val engine = currentEngine
        if (engine == null) {
            TtsLogger.e("onSynthesizeText: no engine available")
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
            return
        }

        val config = currentConfig
        if (config == null) {
            TtsLogger.e("onSynthesizeText: no config available")
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_CONFIG_NOT_FOUND))
            return
        }

        if (!engine.isConfigured(config)) {
            TtsLogger.w("onSynthesizeText: engine not configured")
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        try {
            @Suppress("DEPRECATION")
            callback.start(DEFAULT_SAMPLE_RATE, DEFAULT_AUDIO_FORMAT, DEFAULT_CHANNEL_COUNT)
            TtsLogger.d("Synthesis started")

            engine.synthesize(text, config, object : TtsSynthesisListener {
                override fun onSynthesisStarted() {
                    TtsLogger.d("Synthesis started callback")
                }

                override fun onAudioAvailable(
                    audioData: ByteArray,
                    sampleRate: Int,
                    audioFormat: Int,
                    channelCount: Int
                ) {
                    TtsLogger.d("Audio available: ${audioData.size} bytes")
                    callback.audioAvailable(audioData, 0, audioData.size)
                }

                override fun onSynthesisCompleted() {
                    TtsLogger.d("Synthesis completed")
                    callback.done()
                }

                override fun onError(error: String) {
                    TtsLogger.e("Synthesis error: $error")
                    callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
                }
            })
        } catch (e: Exception) {
            TtsLogger.e("Synthesis failed", e)
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
        }
    }

    override fun onStop() {
        TtsLogger.d("onStop called")
        currentEngine?.stop()
    }

    override fun onDestroy() {
        TtsLogger.i("TalkifyTtsService onDestroy")
        currentEngine?.release()
        currentEngine = null
        currentConfig = null
        currentEngineId = null
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 16000
        private const val DEFAULT_AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
        private const val DEFAULT_CHANNEL_COUNT = 1
    }
}
