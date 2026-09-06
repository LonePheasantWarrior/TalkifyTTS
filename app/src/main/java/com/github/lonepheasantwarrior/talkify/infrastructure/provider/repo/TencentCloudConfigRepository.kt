package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.TencentCloudConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository

/**
 * 腾讯云语音合成供应商 - 配置仓储实现
 *
 * 字段读写由 [BasePrefsConfigRepository] 统一提供，此处仅声明字段映射。
 *
 * 注意：全局配置（如"选择的供应商"）由 [SharedPreferencesAppConfigRepository] 管理
 */
class TencentCloudConfigRepository(
    context: Context
) : BasePrefsConfigRepository<TencentCloudConfig>(context, TencentCloudConfig::class.java) {

    override fun serialize(config: TencentCloudConfig): Map<String, String> = mapOf(
        KEY_APP_ID to config.appId,
        KEY_SECRET_ID to config.secretId,
        KEY_SECRET_KEY to config.secretKey,
        KEY_VOICE_ID to config.voiceId,
        KEY_API_URL to config.apiUrl,
        KEY_MODEL_ID to config.modelId
    )

    override fun deserialize(values: Map<String, String>): TencentCloudConfig = TencentCloudConfig(
        appId = values[KEY_APP_ID] ?: "",
        secretId = values[KEY_SECRET_ID] ?: "",
        secretKey = values[KEY_SECRET_KEY] ?: "",
        voiceId = values[KEY_VOICE_ID] ?: "",
        apiUrl = values[KEY_API_URL] ?: "",
        modelId = values[KEY_MODEL_ID] ?: ""
    )

    private companion object {
        const val KEY_APP_ID = "app_id"
        const val KEY_SECRET_ID = "secret_id"
        const val KEY_SECRET_KEY = "secret_key"
        const val KEY_VOICE_ID = "voice_id"
        const val KEY_API_URL = "api_url"
        const val KEY_MODEL_ID = "model_id"
    }
}
