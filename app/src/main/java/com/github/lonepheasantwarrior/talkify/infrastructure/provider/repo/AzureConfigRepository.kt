package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.AzureConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository

/**
 * 微软语音合成供应商 - 配置仓储实现
 *
 * 微软语音合成无需 API Key，仅存储音色 ID 与 API 地址。
 * 字段读写由 [BasePrefsConfigRepository] 统一提供，此处仅声明字段映射。
 *
 * 注意：全局配置（如"选择的供应商"）由 [SharedPreferencesAppConfigRepository] 管理
 */
class AzureConfigRepository(
    context: Context
) : BasePrefsConfigRepository<AzureConfig>(context, AzureConfig::class.java) {

    override fun serialize(config: AzureConfig): Map<String, String> = mapOf(
        KEY_VOICE_ID to config.voiceId,
        KEY_API_URL to config.apiUrl
    )

    override fun deserialize(values: Map<String, String>): AzureConfig = AzureConfig(
        voiceId = values[KEY_VOICE_ID] ?: "",
        apiUrl = values[KEY_API_URL] ?: ""
    )

    private companion object {
        const val KEY_VOICE_ID = "voice_id"
        const val KEY_API_URL = "api_url"
    }
}
