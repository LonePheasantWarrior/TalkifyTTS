package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.GoogleConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository

/**
 * Google Gemini TTS 语音合成供应商 - 配置仓储实现
 *
 * 字段读写由 [BasePrefsConfigRepository] 统一提供，此处仅声明字段映射。
 *
 * 注意：全局配置（如"选择的供应商"）由 [SharedPreferencesAppConfigRepository] 管理
 */
class GoogleConfigRepository(
    context: Context
) : BasePrefsConfigRepository<GoogleConfig>(context, GoogleConfig::class.java) {

    override fun serialize(config: GoogleConfig): Map<String, String> = mapOf(
        KEY_API_KEY to config.apiKey,
        KEY_VOICE_ID to config.voiceId,
        KEY_API_URL to config.apiUrl,
        KEY_MODEL_ID to config.modelId,
        KEY_STYLE_INSTRUCTION to config.styleInstruction,
        KEY_PROXY_PROTOCOL to config.proxyProtocol,
        KEY_PROXY_HOST to config.proxyHost,
        KEY_PROXY_PORT to config.proxyPort
    )

    override fun deserialize(values: Map<String, String>): GoogleConfig = GoogleConfig(
        apiKey = values[KEY_API_KEY] ?: "",
        voiceId = values[KEY_VOICE_ID] ?: "",
        apiUrl = values[KEY_API_URL] ?: "",
        modelId = values[KEY_MODEL_ID] ?: "",
        styleInstruction = values[KEY_STYLE_INSTRUCTION] ?: "",
        proxyProtocol = values[KEY_PROXY_PROTOCOL]?.ifBlank { GoogleConfig.PROTOCOL_NONE }
            ?: GoogleConfig.PROTOCOL_NONE,
        proxyHost = values[KEY_PROXY_HOST] ?: "",
        proxyPort = values[KEY_PROXY_PORT] ?: ""
    )

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_VOICE_ID = "voice_id"
        const val KEY_API_URL = "api_url"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_STYLE_INSTRUCTION = "style_instruction"
        const val KEY_PROXY_PROTOCOL = "proxy_protocol"
        const val KEY_PROXY_HOST = "proxy_host"
        const val KEY_PROXY_PORT = "proxy_port"
    }
}
