package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import android.content.SharedPreferences
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelConfig
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository

/**
 * 本地模型供应商 - 配置仓储实现
 *
 * 使用 Android SharedPreferences 持久化存储供应商配置。
 * 本地模型不使用 API Key，仅存储 [modelId]（用户选中的本地模型 ID）
 * 和 [voiceId]（用户选中的音色 ID）。
 *
 * 遵循 [ProviderConfigRepository] 接口，与现有供应商仓储模式一致。
 */
class LocalModelConfigRepository(
    context: Context
) : ProviderConfigRepository {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getConfig(providerId: String): BaseProviderConfig {
        val prefsKey = getPrefsKey(providerId)
        return LocalModelConfig(
            voiceId = sharedPreferences.getString("${prefsKey}_$KEY_VOICE_ID", "") ?: "",
            apiUrl = sharedPreferences.getString("${prefsKey}_$KEY_API_URL", "") ?: "",
            modelId = sharedPreferences.getString("${prefsKey}_$KEY_MODEL_ID", "") ?: ""
        )
    }

    override fun saveConfig(providerId: String, config: BaseProviderConfig) {
        val prefsKey = getPrefsKey(providerId)
        val localConfig = config as? LocalModelConfig ?: return
        sharedPreferences.edit()
            .putString("${prefsKey}_$KEY_MODEL_ID", localConfig.modelId)
            .putString("${prefsKey}_$KEY_VOICE_ID", localConfig.voiceId)
            .apply()
    }

    override fun hasConfig(providerId: String): Boolean {
        val prefsKey = getPrefsKey(providerId)
        return sharedPreferences.contains("${prefsKey}_$KEY_MODEL_ID") ||
                sharedPreferences.contains("${prefsKey}_$KEY_VOICE_ID")
    }

    private fun getPrefsKey(providerId: String): String {
        return "engine_${providerId}"
    }

    companion object {
        private const val PREFS_NAME = "talkify_engine_configs"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_API_URL = "api_url"
    }
}
