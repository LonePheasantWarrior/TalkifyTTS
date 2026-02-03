package com.github.lonepheasantwarrior.talkify.infrastructure.app.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository

/**
 * 基于 SharedPreferences 的应用配置仓储实现
 *
 * 存储应用级全局配置，如用户选择的引擎 ID
 * 与引擎特定配置（apiKey、voiceId）分离
 * 不与任何特定 TTS 引擎绑定
 */
class SharedPreferencesAppConfigRepository(
    context: Context
) : AppConfigRepository {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getSelectedEngineId(): String? {
        return sharedPreferences.getString(KEY_SELECTED_ENGINE, null)
    }

    override fun saveSelectedEngineId(engineId: String) {
        sharedPreferences.edit {
            putString(KEY_SELECTED_ENGINE, engineId)
        }
    }

    override fun hasSelectedEngine(): Boolean {
        return sharedPreferences.contains(KEY_SELECTED_ENGINE)
    }

    override fun hasRequestedNotificationPermission(): Boolean {
        return sharedPreferences.getBoolean(KEY_HAS_REQUESTED_NOTIFICATION, false)
    }

    override fun setHasRequestedNotificationPermission(requested: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_HAS_REQUESTED_NOTIFICATION, requested)
        }
    }

    companion object {
        private const val PREFS_NAME = "talkify_app_config"
        private const val KEY_SELECTED_ENGINE = "selected_engine"
        private const val KEY_HAS_REQUESTED_NOTIFICATION = "has_requested_notification"
    }
}
