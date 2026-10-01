package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import java.util.Locale

/**
 * TTS 数据检查 Activity
 *
 * 响应系统或其他应用的 CHECK_TTS_DATA 请求，返回当前供应商支持的语言列表。
 * 语言列表经 [TtsProviderFactory] 创建供应商实例后动态获取——手写 when 映射会随
 * 新增供应商漂移（历史上 9 家供应商只有 3 家被覆盖，其余全部误报为阿里云语言表），
 * 兜底默认值亦与服务层 [TtsProviderRegistry.defaultProvider] 对齐（P1-8）。
 *
 * 契约诚实性（N18）：当前供应商未配置时返回 CHECK_VOICE_DATA_FAIL，不再无条件
 * PASS 虚报就绪——避免系统客户端选中 Talkify 后首次合成才报错。
 * ⚠️ 真机验证前提：部分系统客户端对 FAIL 的处理可能把引擎移出可选列表（影响
 * 首次发现），发版前须真机确认；回退方案为恢复无条件 PASS（见重构方案 §5 风险表）。
 */
class TalkifyCheckDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        TtsLogger.d("CHECK_TTS_DATA: TalkifyCheckDataActivity started")
        super.onCreate(savedInstanceState)

        if (!isCurrentProviderConfigured()) {
            TtsLogger.i("CHECK_TTS_DATA: current provider is not configured, returning FAIL")
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL)
            finish()
            return
        }

        val supportedLanguages = getSupportedLanguagesForCurrentProvider()
        TtsLogger.d("CHECK_TTS_DATA: Supported languages = $supportedLanguages")

        // 客户端可携带 EXTRA_CHECK_VOICE_DATA_FOR 只询问特定 locale；
        // 未携带时报告全量支持语言（P3-6）
        val requested = intent?.getStringExtra(TextToSpeech.Engine.EXTRA_CHECK_VOICE_DATA_FOR)
        val (available, unavailable) = partitionByRequestedLanguage(supportedLanguages, requested)

        val returnData = Intent()
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable)

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)
        finish()
    }

    /**
     * 当前（用户选择或默认兜底）供应商是否已完成配置
     *
     * 复用供应商自述的 [TtsProviderApi.isConfigured] 判定，与合成入口的就绪语义
     * 同源——Azure 等免配置供应商恒为 true，不受本收紧影响
     */
    private fun isCurrentProviderConfigured(): Boolean {
        return try {
            val appConfigRepository = SharedPreferencesAppConfigRepository(this)
            val selectedProviderId = appConfigRepository.getSelectedProviderId()
                ?: TtsProviderRegistry.defaultProvider.id
            val provider = TtsProviderFactory.createProvider(selectedProviderId) ?: return false
            val config = TtsProviderFactory.createConfigRepository(selectedProviderId, this)
                ?.getConfig(selectedProviderId)
            provider.isConfigured(config)
        } catch (e: Exception) {
            TtsLogger.e("CHECK_TTS_DATA: provider configuration check failed", e)
            false
        }
    }

    /**
     * 获取当前选择供应商支持的语言列表
     *
     * 经工厂创建供应商实例读取接口语言表，返回格式为供应商原始定义的
     * 语言代码（如 "zho", "eng"）
     */
    private fun getSupportedLanguagesForCurrentProvider(): ArrayList<String> {
        val appConfigRepository = SharedPreferencesAppConfigRepository(this)
        // 兜底与服务层一致：TtsProviderRegistry.defaultProvider（此前误写为阿里云）
        val selectedProviderId = appConfigRepository.getSelectedProviderId()
            ?: TtsProviderRegistry.defaultProvider.id

        TtsLogger.d("CHECK_TTS_DATA: Selected provider = $selectedProviderId")

        val provider = TtsProviderFactory.createProvider(selectedProviderId)
            ?: TtsProviderFactory.createProvider(TtsProviderRegistry.defaultProvider.id)
        return ArrayList(provider?.getSupportedLanguages()?.toList().orEmpty())
    }

    /**
     * 按客户端询问的 locale 拆分可用/不可用语言
     *
     * @param supported 当前供应商支持的语言代码（ISO 639-2/639-3）
     * @param requestedTag EXTRA_CHECK_VOICE_DATA_FOR 携带的 locale 标签，可为空
     */
    private fun partitionByRequestedLanguage(
        supported: ArrayList<String>,
        requestedTag: String?
    ): Pair<ArrayList<String>, ArrayList<String>> {
        if (requestedTag.isNullOrBlank()) {
            return Pair(supported, arrayListOf())
        }

        // getISO3Language 对无 ISO3 映射的语码按契约抛 MissingResourceException；
        // requestedTag 来自任意第三方客户端的 EXTRA_CHECK_VOICE_DATA_FOR，不可信，
        // 失败时降级为标签语言前缀参与匹配，绝不在 CHECK_TTS_DATA 链路抛异常
        val language = runCatching {
            Locale.forLanguageTag(requestedTag).getISO3Language()
        }.getOrDefault("")
            .ifEmpty { requestedTag.substringBefore('-').lowercase(Locale.US) }

        return if (language in supported) {
            Pair(arrayListOf(language), arrayListOf())
        } else {
            Pair(arrayListOf(), arrayListOf(language))
        }
    }
}
