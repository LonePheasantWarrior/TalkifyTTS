package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.VolcengineConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository

/**
 * 火山引擎（豆包 Seed TTS 2.0）供应商 - 配置仓储实现
 *
 * 字段读写由 [BasePrefsConfigRepository] 统一提供，此处仅声明字段映射。
 *
 * 注意：全局配置（如"选择的供应商"）由 [SharedPreferencesAppConfigRepository] 管理
 */
class VolcengineConfigRepository(
    context: Context
) : BasePrefsConfigRepository<VolcengineConfig>(context, VolcengineConfig::class.java) {

    override fun serialize(config: VolcengineConfig): Map<String, String> = mapOf(
        KEY_API_KEY to config.apiKey,
        KEY_VOICE_ID to config.voiceId,
        KEY_API_URL to config.apiUrl,
        KEY_MODEL_ID to config.modelId
    )

    override fun deserialize(values: Map<String, String>): VolcengineConfig = VolcengineConfig(
        apiKey = values[KEY_API_KEY] ?: "",
        voiceId = values[KEY_VOICE_ID] ?: "",
        apiUrl = values[KEY_API_URL] ?: "",
        modelId = values[KEY_MODEL_ID] ?: ""
    )

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_VOICE_ID = "voice_id"
        const val KEY_API_URL = "api_url"
        const val KEY_MODEL_ID = "model_id"
    }
}
