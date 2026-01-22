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
import java.util.UUID

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
 */
class TalkifyTtsService : TextToSpeechService() {

    private var appConfigRepository: AppConfigRepository? = null
    private var engineConfigRepository: EngineConfigRepository? = null
    private var currentEngine: TtsEngineApi? = null
    private var currentEngineId: String? = null
    private var currentConfig: EngineConfig? = null

    override fun onCreate() {
        super.onCreate()
        initializeRepositories()
        initializeEngine()
    }

    private fun initializeRepositories() {
        appConfigRepository = SharedPreferencesAppConfigRepository(applicationContext)
        engineConfigRepository = Qwen3TtsConfigRepository(applicationContext)
    }

    private fun initializeEngine() {
        val engineId = appConfigRepository?.getSelectedEngineId()
            ?: TtsEngineRegistry.defaultEngine.id

        if (currentEngineId != engineId) {
            currentEngine?.release()
            currentEngine = TtsEngineFactory.createEngine(engineId)
            currentEngineId = engineId
        }

        val ttsEngine = TtsEngineRegistry.getEngine(engineId)
        if (ttsEngine != null) {
            currentConfig = engineConfigRepository?.getConfig(ttsEngine)
        }
    }

    override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        val locale = when {
            lang != null && country != null && variant != null -> Locale.Builder().setLanguage(lang).setRegion(country).setVariant(variant).build()
            lang != null && country != null -> Locale.Builder().setLanguage(lang).setRegion(country).build()
            lang != null -> Locale.Builder().setLanguage(lang).build()
            else -> return TextToSpeech.LANG_NOT_SUPPORTED
        }
        return when (locale.language) {
            "zh", "en" -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        val locale = when {
            lang != null && country != null && variant != null -> Locale.Builder().setLanguage(lang).setRegion(country).setVariant(variant).build()
            lang != null && country != null -> Locale.Builder().setLanguage(lang).setRegion(country).build()
            lang != null -> Locale.Builder().setLanguage(lang).build()
            else -> return TextToSpeech.LANG_NOT_SUPPORTED
        }
        return when (locale.language) {
            "zh", "en" -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String>? {
        return arrayOf("zh")
    }

    override fun onSynthesizeText(
        request: android.speech.tts.SynthesisRequest?,
        callback: android.speech.tts.SynthesisCallback?
    ) {
        if (request == null || callback == null) return

        @Suppress("DEPRECATION")
        val text = request.text ?: return
        UUID.randomUUID().toString()

        val engine = currentEngine ?: return
        val config = currentConfig ?: return

        if (!engine.isConfigured(config)) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }

        @Suppress("DEPRECATION")
        callback.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        engine.synthesize(text, config, object : TtsSynthesisListener {
            override fun onSynthesisStarted() {
            }

            override fun onAudioAvailable(
                audioData: ByteArray,
                sampleRate: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                callback.audioAvailable(audioData, 0, audioData.size)
            }

            override fun onSynthesisCompleted() {
                callback.done()
            }

            override fun onError(error: String) {
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            }
        })
    }

    override fun onStop() {
        currentEngine?.stop()
    }

    override fun onDestroy() {
        currentEngine?.release()
        currentEngine = null
        currentConfig = null
        super.onDestroy()
    }
}
