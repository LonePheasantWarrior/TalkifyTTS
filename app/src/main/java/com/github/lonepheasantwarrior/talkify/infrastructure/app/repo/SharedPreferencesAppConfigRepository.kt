package com.github.lonepheasantwarrior.talkify.infrastructure.app.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository

/**
 * 基于 SharedPreferences 的应用配置仓储实现
 *
 * 存储应用级全局配置，如用户选择的供应商 ID
 * 与供应商特定配置（apiKey、voiceId）分离
 * 不与任何特定 TTS 供应商绑定
 */
class SharedPreferencesAppConfigRepository(
    context: Context
) : AppConfigRepository {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacySelectedProviderKey()
        migrateLegacyProviderIds()
    }

    private fun migrateLegacySelectedProviderKey() {
        if (!sharedPreferences.contains(KEY_SELECTED_PROVIDER) &&
            sharedPreferences.contains(KEY_SELECTED_PROVIDER_LEGACY)
        ) {
            val legacyValue = sharedPreferences.getString(KEY_SELECTED_PROVIDER_LEGACY, null)
            if (legacyValue != null) {
                sharedPreferences.edit {
                    putString(KEY_SELECTED_PROVIDER, legacyValue)
                    remove(KEY_SELECTED_PROVIDER_LEGACY)
                }
            }
        }
    }

    /**
     * 将旧版 ProviderIds.value（模型 ID）映射为新版 ProviderIds.providerId（供应商 ID）。
     *
     * 旧值（v1.x）→ 新值（v2.x）：
     *   qwen3-tts → aliyunBailian
     *   seed-tts-2.0 → volcengine
     *   tencent-tts → tencentCloud
     *   microsoft-tts → azure
     *   xiaomi-mimo-tts → xiaomi
     *   minimax-tts → miniMax
     */
    private fun migrateLegacyProviderIds() {
        val current = sharedPreferences.getString(KEY_SELECTED_PROVIDER, null) ?: return
        val migrated = LEGACY_PROVIDER_ID_MAP[current] ?: return
        sharedPreferences.edit {
            putString(KEY_SELECTED_PROVIDER, migrated)
        }
    }

    override fun getSelectedProviderId(): String? {
        return sharedPreferences.getString(KEY_SELECTED_PROVIDER, null)
    }

    override fun saveSelectedProviderId(providerId: String) {
        sharedPreferences.edit {
            putString(KEY_SELECTED_PROVIDER, providerId)
        }
    }

    override fun hasSelectedProvider(): Boolean {
        return sharedPreferences.contains(KEY_SELECTED_PROVIDER)
    }

    override fun hasRequestedNotificationPermission(): Boolean {
        return sharedPreferences.getBoolean(KEY_HAS_REQUESTED_NOTIFICATION, false)
    }

    override fun setHasRequestedNotificationPermission(requested: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_HAS_REQUESTED_NOTIFICATION, requested)
        }
    }

    override fun hasOpenedAboutPage(): Boolean {
        return sharedPreferences.getBoolean(KEY_HAS_OPENED_ABOUT_PAGE, false)
    }

    override fun setAboutPageOpened(opened: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_HAS_OPENED_ABOUT_PAGE, opened)
        }
    }

    override fun isAudioGainEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUDIO_GAIN_ENABLED, false)
    }

    override fun setAudioGainEnabled(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_AUDIO_GAIN_ENABLED, enabled)
        }
    }

    override fun getAudioGainDb(): Float {
        return sharedPreferences.getFloat(KEY_AUDIO_GAIN_DB, DEFAULT_AUDIO_GAIN_DB)
            .coerceIn(MIN_AUDIO_GAIN_DB, MAX_AUDIO_GAIN_DB)
    }

    override fun setAudioGainDb(gainDb: Float) {
        sharedPreferences.edit {
            putFloat(KEY_AUDIO_GAIN_DB, gainDb.coerceIn(MIN_AUDIO_GAIN_DB, MAX_AUDIO_GAIN_DB))
        }
    }

    companion object {
        private const val PREFS_NAME = "talkify_app_config"
        private const val KEY_SELECTED_PROVIDER = "selected_provider"
        private const val KEY_SELECTED_PROVIDER_LEGACY = "selected_engine"
        private const val KEY_HAS_REQUESTED_NOTIFICATION = "has_requested_notification"
        private const val KEY_HAS_OPENED_ABOUT_PAGE = "has_opened_about_page"
        private const val KEY_AUDIO_GAIN_ENABLED = "audio_gain_enabled"
        private const val KEY_AUDIO_GAIN_DB = "audio_gain_db"

        const val MIN_AUDIO_GAIN_DB = 0.5f
        const val MAX_AUDIO_GAIN_DB = 12.0f
        const val DEFAULT_AUDIO_GAIN_DB = 6.0f

        /** 旧版 ProviderIds.value → 新版 ProviderIds.providerId 映射 */
        private val LEGACY_PROVIDER_ID_MAP = mapOf(
            "qwen3-tts" to "aliyunBailian",
            "seed-tts-2.0" to "volcengine",
            "tencent-tts" to "tencentCloud",
            "microsoft-tts" to "azure",
            "xiaomi-mimo-tts" to "xiaomi",
            "minimax-tts" to "miniMax"
        )
    }
}
