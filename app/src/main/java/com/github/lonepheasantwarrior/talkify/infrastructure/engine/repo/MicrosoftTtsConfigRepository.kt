package com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo

import android.content.Context
import android.content.SharedPreferences
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.MicrosoftTtsConfig
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository

/**
 * 微软语音合成引擎 - 配置仓储实现
 *
 * 使用 Android SharedPreferences 持久化存储引擎配置
 * 遵循 [EngineConfigRepository] 接口，便于后续扩展其他存储方式
 *
 * 注意：微软语音合成无需 API Key，仅存储音色 ID
 */
class MicrosoftTtsConfigRepository(
    context: Context
) : EngineConfigRepository {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getConfig(engineId: String): BaseEngineConfig {
        val prefsKey = getPrefsKey(engineId)
        return MicrosoftTtsConfig(
            voiceId = sharedPreferences.getString("${prefsKey}_$KEY_VOICE_ID", "") ?: ""
        )
    }

    override fun saveConfig(engineId: String, config: BaseEngineConfig) {
        val prefsKey = getPrefsKey(engineId)
        val msConfig = config as? MicrosoftTtsConfig ?: return
        sharedPreferences.edit()
            .putString("${prefsKey}_$KEY_VOICE_ID", msConfig.voiceId)
            .apply()
    }

    override fun hasConfig(engineId: String): Boolean {
        val prefsKey = getPrefsKey(engineId)
        return sharedPreferences.contains("${prefsKey}_$KEY_VOICE_ID")
    }

    private fun getPrefsKey(engineId: String): String {
        return "engine_${engineId}"
    }

    companion object {
        private const val PREFS_NAME = "talkify_engine_configs"
        private const val KEY_VOICE_ID = "voice_id"
    }
}
