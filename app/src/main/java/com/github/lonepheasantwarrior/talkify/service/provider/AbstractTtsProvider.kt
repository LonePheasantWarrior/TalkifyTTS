package com.github.lonepheasantwarrior.talkify.service.provider

import android.content.Context
import android.speech.tts.Voice
import androidx.annotation.XmlRes
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.xml.VoiceXmlParser
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import java.util.Locale

/** 音色名称分隔符：Android Voice 名称格式为 "<真实音色名>::<显示名/语言标记>" */
internal const val VOICE_NAME_SEPARATOR = "::"

abstract class AbstractTtsProvider : TtsProviderApi {

    protected var isReleased: Boolean = false
        private set

    protected open val tag: String
        get() = javaClass.simpleName

    // ==================== 元数据钩子 ====================

    /**
     * 供应商支持的音色 ID 列表（通常来自 XML 资源）。
     *
     * 基于该列表提供 [getSupportedVoices] / [getDefaultVoiceId] / [isVoiceIdCorrect]
     * 的通用实现；音色模型特殊的供应商（如阿里云 SDK 枚举、本地模型注册表）直接覆写方法。
     */
    protected open val voiceIds: List<String>
        get() = emptyList()

    /** [voiceIds] 为空时的兜底默认音色 */
    protected open val fallbackVoiceId: String
        get() = ""

    /** 支持的语言代码列表（ISO 639-2 三字母代码） */
    protected open val supportedLanguages: Array<String>
        get() = emptyArray()

    /** [getDefaultLanguage] 的默认返回值 */
    protected open fun createDefaultLanguage(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.language, Locale.SIMPLIFIED_CHINESE.country, "")
    }

    /** 供应商特有配置项的标签映射（键 → 字符串资源 ID），通用标签见 [getConfigLabel] */
    protected open val configLabels: Map<String, Int>
        get() = emptyMap()

    override fun getSupportedLanguages(): Set<String> {
        return supportedLanguages.toSet()
    }

    override fun getDefaultLanguage(): Array<String> {
        return createDefaultLanguage()
    }

    override fun getSupportedVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()
        for (langCode in supportedLanguages) {
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
        val defaultVoice = voiceIds.firstOrNull() ?: fallbackVoiceId
        if (!currentVoiceId.isNullOrBlank()) {
            return "$currentVoiceId$VOICE_NAME_SEPARATOR$lang"
        }
        return "$defaultVoice$VOICE_NAME_SEPARATOR$lang"
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        if (voiceId == null) return false
        val realVoiceName = extractRealVoiceName(voiceId)
        return realVoiceName != null && voiceIds.contains(realVoiceName)
    }

    /**
     * 类型安全的 isConfigured 实现：
     * 配置为目标类型且满足 [predicate] 时视为已配置
     */
    protected inline fun <reified C : BaseProviderConfig> isConfiguredAs(
        config: BaseProviderConfig?,
        predicate: (C) -> Boolean
    ): Boolean {
        val result = config is C && predicate(config)
        TtsLogger.d("$tag: isConfigured = $result")
        return result
    }

    override fun getConfigLabel(configKey: String, context: Context): String? {
        configLabels[configKey]?.let { return context.getString(it) }
        return when (configKey) {
            "api_url" -> context.getString(R.string.api_url_label)
            "model_id" -> context.getString(R.string.model_id_label)
            "voice_id" -> context.getString(R.string.voice_select_label)
            else -> null
        }
    }

    override fun stop() {
        TtsLogger.d("$tag: stop called")
    }

    override fun release() {
        TtsLogger.i("$tag: release called")
        isReleased = true
    }

    override fun getAudioConfig(): AudioConfig {
        return AudioConfig()
    }

    /**
     * 默认 API 地址，返回空字符串表示供应商不支持自定义 API 地址。
     * 子类可按需重写。
     */
    override fun getDefaultApiUrl(): String = ""

    /**
     * 默认模型 ID，返回空字符串表示供应商不支持自定义模型 ID。
     * 子类可按需重写。
     */
    override fun getDefaultModelId(): String = ""

    protected fun checkNotReleased() {
        if (isReleased) {
            val message = "Provider has been released"
            TtsLogger.e("$tag: $message")
            throw IllegalStateException(message)
        }
    }

    protected fun logDebug(message: String) {
        TtsLogger.d("$tag: $message")
    }

    protected fun logInfo(message: String) {
        TtsLogger.i("$tag: $message")
    }

    protected fun logWarning(message: String) {
        TtsLogger.w("$tag: $message")
    }

    protected fun logError(message: String, throwable: Throwable? = null) {
        TtsLogger.e("$tag: $message", throwable)
    }

    /**
     * 检查文本是否包含可朗读的文字内容
     * @return true 如果文本中至少包含一个文字字符（任意语言）
     */
    protected fun containsReadableText(text: String): Boolean {
        return text.any { Character.isLetter(it.code) }
    }

    /**
     * 从 Android 本地音色名称中提取真实的音色标识符。
     *
     * 格式："<真实音色名>::<显示名称>"，提取 `::` 之前的部分。
     * 若不包含分隔符，则返回原始名称。
     */
    protected fun extractRealVoiceName(androidVoiceName: String?): String? {
        if (androidVoiceName == null) return null
        return if (androidVoiceName.contains(VOICE_NAME_SEPARATOR)) {
            androidVoiceName.substringBefore(VOICE_NAME_SEPARATOR)
        } else {
            androidVoiceName
        }
    }

    /**
     * 从 XML 资源加载音色 ID 列表。
     *
     * 使用 [VoiceXmlParser] 解析语音定义的 XML 资源文件，
     * 提取其中的音色标识符列表。
     *
     * @param xmlResId XML 资源 ID（如 R.xml.minimax_voices）
     * @return 音色 ID 列表，解析失败或 Context 不可用时返回空列表
     */
    protected fun loadVoiceIdsFromXml(@XmlRes xmlResId: Int): List<String> {
        val context = TalkifyAppHolder.getContext()
        return if (context != null) {
            try {
                VoiceXmlParser.parseVoiceIds(context, xmlResId)
            } catch (e: Exception) {
                TtsLogger.e("$tag: Failed to load voice IDs from resource", throwable = e)
                emptyList()
            }
        } else {
            TtsLogger.w("$tag: Context not available, voice IDs will be empty")
            emptyList()
        }
    }
}
