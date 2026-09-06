package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelConfig

/**
 * 本地模型供应商 - 配置仓储实现
 *
 * 本地模型不使用 API Key，仅存储 [modelId]（用户选中的本地模型 ID）
 * 和 [voiceId]（用户选中的音色 ID）；[apiUrl] 为保留字段。
 *
 * 字段读写由 [BasePrefsConfigRepository] 统一提供，此处仅声明字段映射。
 */
class LocalModelConfigRepository(
    context: Context
) : BasePrefsConfigRepository<LocalModelConfig>(context, LocalModelConfig::class.java) {

    override fun serialize(config: LocalModelConfig): Map<String, String> = mapOf(
        KEY_MODEL_ID to config.modelId,
        KEY_VOICE_ID to config.voiceId,
        KEY_API_URL to config.apiUrl
    )

    override fun deserialize(values: Map<String, String>): LocalModelConfig = LocalModelConfig(
        voiceId = values[KEY_VOICE_ID] ?: "",
        apiUrl = values[KEY_API_URL] ?: "",
        modelId = values[KEY_MODEL_ID] ?: ""
    )

    private companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_VOICE_ID = "voice_id"
        const val KEY_API_URL = "api_url"
    }
}
