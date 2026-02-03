package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.impl.Qwen3TtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.impl.SeedTts2Engine

/**
 * TTS 数据检查 Activity
 *
 * 响应系统或其他应用的 CHECK_TTS_DATA 请求，返回当前引擎支持的语言列表
 * 语言列表根据用户选择的引擎动态获取（使用引擎的静态常量）
 */
class TalkifyCheckDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        TtsLogger.d("CHECK_TTS_DATA: TalkifyCheckDataActivity started")
        super.onCreate(savedInstanceState)

        // 获取支持的语言列表（根据当前选择的引擎）
        val supportedLanguages = getSupportedLanguagesForCurrentEngine()

        TtsLogger.d("CHECK_TTS_DATA: Supported languages = $supportedLanguages")

        val returnData = Intent()

        // 1. 声明支持的语言
        returnData.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
            supportedLanguages
        )

        // 2. 声明不支持的语言（空）
        returnData.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
            arrayListOf()
        )

        // 返回 PASS
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)

        finish()
    }

    /**
     * 获取当前选择引擎支持的语言列表
     *
     * 直接从引擎的静态常量获取，无需创建引擎实例
     * 返回格式为引擎原始定义的语言代码（如 "zho", "eng"）
     *
     * @return 支持的语言代码列表
     */
    private fun getSupportedLanguagesForCurrentEngine(): ArrayList<String> {
        // 获取当前选择的引擎 ID
        val appConfigRepository = SharedPreferencesAppConfigRepository(this)
        val selectedEngineId = appConfigRepository.getSelectedEngineId()
            ?: Qwen3TtsEngine.ENGINE_ID // 默认使用通义千问3

        TtsLogger.d("CHECK_TTS_DATA: Selected engine = $selectedEngineId")

        // 根据引擎 ID 获取对应的静态语言列表
        return when (selectedEngineId) {
            SeedTts2Engine.ENGINE_ID -> {
                ArrayList(SeedTts2Engine.SUPPORTED_LANGUAGES.toList())
            }
            else -> {
                ArrayList(Qwen3TtsEngine.SUPPORTED_LANGUAGES.toList())
            }
        }
    }
}
