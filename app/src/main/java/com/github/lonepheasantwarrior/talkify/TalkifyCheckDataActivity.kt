package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.provider.impl.AliyunBailianProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.GoogleProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.VolcengineProvider

/**
 * TTS 数据检查 Activity
 *
 * 响应系统或其他应用的 CHECK_TTS_DATA 请求，返回当前供应商支持的语言列表
 * 语言列表根据用户选择的供应商动态获取（使用供应商的静态常量）
 */
class TalkifyCheckDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        TtsLogger.d("CHECK_TTS_DATA: TalkifyCheckDataActivity started")
        super.onCreate(savedInstanceState)

        // 获取支持的语言列表（根据当前选择的供应商）
        val supportedLanguages = getSupportedLanguagesForCurrentProvider()

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
     * 获取当前选择供应商支持的语言列表
     *
     * 直接从供应商的静态常量获取，无需创建供应商实例
     * 返回格式为供应商原始定义的语言代码（如 "zho", "eng"）
     *
     * @return 支持的语言代码列表
     */
    private fun getSupportedLanguagesForCurrentProvider(): ArrayList<String> {
        // 获取当前选择的供应商 ID（新版 providerId 格式，如 "aliyunBailian"、"volcengine"）
        val appConfigRepository = SharedPreferencesAppConfigRepository(this)
        val selectedProviderId = appConfigRepository.getSelectedProviderId()
            ?: ProviderIds.AliyunBailian.providerId // 默认使用通义千问3

        TtsLogger.d("CHECK_TTS_DATA: Selected provider = $selectedProviderId")

        // 根据供应商 ID 获取对应的静态语言列表
        return when (selectedProviderId) {
            ProviderIds.Volcengine.providerId -> {
                ArrayList(VolcengineProvider.SUPPORTED_LANGUAGES.toList())
            }
            ProviderIds.Google.providerId -> {
                ArrayList(GoogleProvider.SUPPORTED_LANGUAGES.toList())
            }
            else -> {
                ArrayList(AliyunBailianProvider.SUPPORTED_LANGUAGES.toList())
            }
        }
    }
}
