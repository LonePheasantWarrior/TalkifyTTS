package com.github.lonepheasantwarrior.talkify.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.lonepheasantwarrior.talkify.domain.model.DialogueModeConfig
import com.github.lonepheasantwarrior.talkify.domain.model.RoleRule
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProvider
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.VoiceBinding
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.DataStoreDialogueModeRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 对话模式 ViewModel
 *
 * 持有 [DialogueModeConfig]（DataStore 持久化），供 DialogueModeScreen 编辑：
 * - 总开关 / 朗读引号 / 段间停顿
 * - 旁白、对话发音人绑定（VoiceBinding = 供应商 + 音色 + 可选风格指令）
 * - 角色规则（人物名 / 正则 → 指定发音人）
 *
 * 音色列表与「是否支持风格指令」复用既有 Provider 架构
 * （TtsProviderFactory 的 VoiceRepository / getConfigLabel），与单音色路径同源。
 */
class DialogueModeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DataStoreDialogueModeRepository(application)

    private val _config = MutableStateFlow(DialogueModeConfig())
    val config: StateFlow<DialogueModeConfig> = _config.asStateFlow()

    init {
        viewModelScope.launch {
            repository.config.collect { saved ->
                _config.value = saved
            }
        }
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setReadQuotes(readQuotes: Boolean) = update { it.copy(readQuotes = readQuotes) }

    fun setPauseMs(pauseMs: Int) = update {
        it.copy(pauseMs = pauseMs.coerceIn(0, DialogueModeConfig.MAX_PAUSE_MS))
    }

    fun setNarrator(binding: VoiceBinding) = update { it.copy(narrator = binding) }

    fun setDialogue(binding: VoiceBinding) = update { it.copy(dialogue = binding) }

    fun upsertRule(rule: RoleRule) = update { current ->
        val index = current.rules.indexOfFirst { it.id == rule.id }
        val rules = if (index >= 0) {
            current.rules.toMutableList().apply { set(index, rule) }
        } else {
            current.rules + rule
        }
        current.copy(rules = rules)
    }

    fun deleteRule(ruleId: Long) = update { current ->
        current.copy(rules = current.rules.filterNot { it.id == ruleId })
    }

    private fun update(transform: (DialogueModeConfig) -> DialogueModeConfig) {
        val next = transform(_config.value)
        _config.value = next
        viewModelScope.launch {
            runCatching { repository.save(next) }
                .onFailure { TtsLogger.e("DialogueMode: 配置保存失败: ${it.message}") }
        }
    }

    // --- 音色数据（复用既有 Provider 架构）---

    /** 所有可用供应商（供发音人绑定选择） */
    fun availableProviders(): List<TtsProvider> = TtsProviderRegistry.availableProviders

    /** 加载指定供应商的音色列表 */
    suspend fun loadVoices(providerId: String): List<VoiceInfo> {
        val provider = TtsProviderRegistry.getProvider(providerId) ?: return emptyList()
        val repo = com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
            .createVoiceRepository(providerId, getApplication())
            ?: return emptyList()
        return runCatching { repo.getVoicesForProvider(provider) }
            .onFailure { TtsLogger.w("DialogueMode: 加载音色失败 provider=$providerId: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** 该供应商是否支持风格指令（与 ConfigBottomSheet 的判断同源） */
    fun supportsStyleInstruction(providerId: String): Boolean {
        val provider = com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
            .createProvider(providerId) ?: return false
        val context: Context = getApplication()
        return runCatching { provider.getConfigLabel("style_instruction", context) != null }
            .getOrDefault(false)
    }
}
