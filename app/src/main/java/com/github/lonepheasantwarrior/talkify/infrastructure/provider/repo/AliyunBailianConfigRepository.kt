package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.AliyunBailianConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository

/**
 * 通义千问3语音合成供应商 - 配置仓储实现
 *
 * 字段读写由 [BasePrefsConfigRepository] 统一提供，此处仅声明字段映射。
 *
 * 注意：全局配置（如"选择的供应商"）由 [SharedPreferencesAppConfigRepository] 管理
 */
class AliyunBailianConfigRepository(
    context: Context
) : BasePrefsConfigRepository<AliyunBailianConfig>(context, AliyunBailianConfig::class.java) {

    override fun serialize(config: AliyunBailianConfig): Map<String, String> = mapOf(
        KEY_API_KEY to config.apiKey,
        KEY_VOICE_ID to config.voiceId,
        KEY_API_URL to config.apiUrl,
        KEY_MODEL_ID to config.modelId
    )

    override fun deserialize(values: Map<String, String>): AliyunBailianConfig = AliyunBailianConfig(
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
