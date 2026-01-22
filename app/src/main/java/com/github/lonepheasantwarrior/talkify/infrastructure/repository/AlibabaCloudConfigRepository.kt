package com.github.lonepheasantwarrior.talkify.infrastructure.repository

import android.content.Context
import android.content.SharedPreferences
import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository

class AlibabaCloudConfigRepository(
    context: Context
) : EngineConfigRepository {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getConfig(engine: TtsEngine): EngineConfig {
        val prefsKey = getPrefsKey(engine)
        return EngineConfig(
            apiKey = sharedPreferences.getString("${prefsKey}_$KEY_API_KEY", "") ?: "",
            voiceId = sharedPreferences.getString("${prefsKey}_$KEY_VOICE_ID", "") ?: ""
        )
    }

    override fun saveConfig(engine: TtsEngine, config: EngineConfig) {
        val prefsKey = getPrefsKey(engine)
        sharedPreferences.edit()
            .putString("${prefsKey}_$KEY_API_KEY", config.apiKey)
            .putString("${prefsKey}_$KEY_VOICE_ID", config.voiceId)
            .apply()
    }

    override fun hasConfig(engine: TtsEngine): Boolean {
        val prefsKey = getPrefsKey(engine)
        return sharedPreferences.contains("${prefsKey}_$KEY_API_KEY") ||
                sharedPreferences.contains("${prefsKey}_$KEY_VOICE_ID")
    }

    private fun getPrefsKey(engine: TtsEngine): String {
        return "engine_${engine.id}"
    }

    companion object {
        private const val PREFS_NAME = "talkify_engine_configs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_VOICE_ID = "voice_id"
    }
}
