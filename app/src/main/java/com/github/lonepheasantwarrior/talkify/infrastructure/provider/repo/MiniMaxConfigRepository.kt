package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.LanguageBoost
import com.github.lonepheasantwarrior.talkify.domain.model.MiniMaxConfig

/**
 * MiniMax 语音合成供应商 - 配置仓储实现
 *
 * 字段读写由 [BasePrefsConfigRepository] 统一提供，此处仅声明字段映射。
 * [continuousSound] 为三态字段（未设置 / true / false）：未设置时键不写入。
 */
class MiniMaxConfigRepository(
    context: Context
) : BasePrefsConfigRepository<MiniMaxConfig>(context, MiniMaxConfig::class.java) {

    override fun serialize(config: MiniMaxConfig): Map<String, String> {
        val values = mutableMapOf(
            KEY_API_KEY to config.apiKey,
            KEY_VOICE_ID to config.voiceId,
            KEY_API_URL to config.apiUrl,
            KEY_MODEL_ID to config.modelId,
            KEY_LANGUAGE_BOOST to config.languageBoost.name,
            KEY_ENGLISH_NORMALIZATION to config.englishNormalization.toString()
        )
        config.continuousSound?.let { values[KEY_CONTINUOUS_SOUND] = it.toString() }
        return values
    }

    override fun deserialize(values: Map<String, String>): MiniMaxConfig = MiniMaxConfig(
        apiKey = values[KEY_API_KEY] ?: "",
        voiceId = values[KEY_VOICE_ID] ?: "",
        apiUrl = values[KEY_API_URL] ?: "",
        modelId = values[KEY_MODEL_ID] ?: "",
        continuousSound = values[KEY_CONTINUOUS_SOUND]?.toBooleanStrictOrNull(),
        languageBoost = try {
            LanguageBoost.valueOf(values[KEY_LANGUAGE_BOOST] ?: LanguageBoost.OFF.name)
        } catch (_: IllegalArgumentException) {
            LanguageBoost.OFF
        },
        englishNormalization = values[KEY_ENGLISH_NORMALIZATION]?.toBooleanStrictOrNull() ?: false
    )

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_VOICE_ID = "voice_id"
        const val KEY_API_URL = "api_url"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_CONTINUOUS_SOUND = "continuous_sound"
        const val KEY_LANGUAGE_BOOST = "language_boost"
        const val KEY_ENGLISH_NORMALIZATION = "english_normalization"
    }
}
