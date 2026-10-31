package com.github.lonepheasantwarrior.talkify.infrastructure.app.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.lonepheasantwarrior.talkify.domain.model.DialogueModeConfig
import com.github.lonepheasantwarrior.talkify.domain.model.RoleRule
import com.github.lonepheasantwarrior.talkify.domain.model.VoiceBinding
import com.github.lonepheasantwarrior.talkify.domain.repository.DialogueModeRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话模式配置的 DataStore 实现
 *
 * 存储结构（Preferences DataStore，文件 talkifyc_dialogue_mode.preferences_pb）：
 * - enabled / readQuotes / pauseMs：标量
 * - narrator / dialogue：JSON 字符串（VoiceBinding 序列化）
 * - rules：JSON 数组字符串（RoleRule 列表序列化）
 *
 * 说明：TalkifyC 的 applicationId 与上游不同，应用数据目录天然隔离；
 * 该 DataStore 文件亦只属于 TalkifyC 自己的数据目录。
 */
class DataStoreDialogueModeRepository(
    private val context: Context
) : DialogueModeRepository {

    private val dataStore: DataStore<Preferences> = context.dialogueModeDataStore

    override val config: Flow<DialogueModeConfig> = dataStore.data.map { prefs ->
        prefs.toConfig()
    }

    override suspend fun getConfig(): DialogueModeConfig {
        return config.first()
    }

    override suspend fun save(config: DialogueModeConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_ENABLED] = config.enabled
            prefs[KEY_READ_QUOTES] = config.readQuotes
            prefs[KEY_PAUSE_MS] = config.pauseMs.coerceIn(0, DialogueModeConfig.MAX_PAUSE_MS)
            prefs[KEY_NARRATOR] = bindingToJson(config.narrator).toString()
            prefs[KEY_DIALOGUE] = bindingToJson(config.dialogue).toString()
            prefs[KEY_RULES] = rulesToJson(config.rules).toString()
        }
    }

    private fun Preferences.toConfig(): DialogueModeConfig {
        return DialogueModeConfig(
            enabled = this[KEY_ENABLED] ?: false,
            readQuotes = this[KEY_READ_QUOTES] ?: false,
            pauseMs = (this[KEY_PAUSE_MS] ?: DialogueModeConfig.DEFAULT_PAUSE_MS)
                .coerceIn(0, DialogueModeConfig.MAX_PAUSE_MS),
            narrator = this[KEY_NARRATOR]?.parseBinding() ?: VoiceBinding(),
            dialogue = this[KEY_DIALOGUE]?.parseBinding() ?: VoiceBinding(),
            rules = this[KEY_RULES]?.parseRules().orEmpty()
        )
    }

    // --- JSON 序列化（org.json 在 App 与 JVM 单测环境均可用）---

    private fun bindingToJson(binding: VoiceBinding): JSONObject = JSONObject()
        .put(KEY_PROVIDER_ID, binding.providerId)
        .put(KEY_VOICE_ID, binding.voiceId)
        .put(KEY_DISPLAY_NAME, binding.displayName)
        .put(KEY_STYLE, binding.styleInstruction)

    private fun String.parseBinding(): VoiceBinding? = runCatching {
        val obj = JSONObject(this)
        VoiceBinding(
            providerId = obj.optString(KEY_PROVIDER_ID),
            voiceId = obj.optString(KEY_VOICE_ID),
            displayName = obj.optString(KEY_DISPLAY_NAME),
            styleInstruction = obj.optString(KEY_STYLE)
        )
    }.onFailure {
        TtsLogger.w("DialogueMode: 解析发音人绑定失败，按未配置处理", tag = TAG)
    }.getOrNull()

    private fun rulesToJson(rules: List<RoleRule>): JSONArray {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put(KEY_RULE_ID, rule.id)
                    .put(KEY_RULE_NAME, rule.name)
                    .put(KEY_RULE_PATTERN, rule.pattern)
                    .put(KEY_RULE_IS_REGEX, rule.isRegex)
                    .put(KEY_RULE_BINDING, bindingToJson(rule.binding))
            )
        }
        return array
    }

    private fun String.parseRules(): List<RoleRule> = runCatching {
        val array = JSONArray(this)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.getJSONObject(index)
            val binding = obj.optJSONObject(KEY_RULE_BINDING)?.let { b ->
                VoiceBinding(
                    providerId = b.optString(KEY_PROVIDER_ID),
                    voiceId = b.optString(KEY_VOICE_ID),
                    displayName = b.optString(KEY_DISPLAY_NAME),
                    styleInstruction = b.optString(KEY_STYLE)
                )
            } ?: VoiceBinding()
            RoleRule(
                id = obj.optLong(KEY_RULE_ID),
                name = obj.optString(KEY_RULE_NAME),
                pattern = obj.optString(KEY_RULE_PATTERN),
                isRegex = obj.optBoolean(KEY_RULE_IS_REGEX),
                binding = binding
            )
        }
    }.onFailure {
        TtsLogger.w("DialogueMode: 解析角色规则失败，忽略已有规则", tag = TAG)
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "DialogueModeRepo"

        private val Context.dialogueModeDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "talkifyc_dialogue_mode"
        )

        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_READ_QUOTES = booleanPreferencesKey("read_quotes")
        private val KEY_PAUSE_MS = intPreferencesKey("pause_ms")
        private val KEY_NARRATOR = stringPreferencesKey("narrator")
        private val KEY_DIALOGUE = stringPreferencesKey("dialogue")
        private val KEY_RULES = stringPreferencesKey("rules")

        private const val KEY_PROVIDER_ID = "providerId"
        private const val KEY_VOICE_ID = "voiceId"
        private const val KEY_DISPLAY_NAME = "displayName"
        private const val KEY_STYLE = "styleInstruction"
        private const val KEY_RULE_ID = "id"
        private const val KEY_RULE_NAME = "name"
        private const val KEY_RULE_PATTERN = "pattern"
        private const val KEY_RULE_IS_REGEX = "isRegex"
        private const val KEY_RULE_BINDING = "binding"
    }
}
