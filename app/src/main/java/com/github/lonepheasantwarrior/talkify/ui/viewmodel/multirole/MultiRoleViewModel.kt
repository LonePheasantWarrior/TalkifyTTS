package com.github.lonepheasantwarrior.talkify.ui.viewmodel.multirole

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.OpenAIConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.DelimiterPair
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssue
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssueCode
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleLimits
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleState
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ProfileId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.Role
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef
import com.github.lonepheasantwarrior.talkify.domain.multirole.MultiRoleParser
import com.github.lonepheasantwarrior.talkify.domain.multirole.ParsedReading
import com.github.lonepheasantwarrior.talkify.domain.repository.MultiRoleRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.MultiRoleUpdateResult
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.MultiRoleRepositoryProvider
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.TtsPreviewPlayer
import com.github.lonepheasantwarrior.talkify.service.multirole.MultiRolePreviewPlayer
import com.github.lonepheasantwarrior.talkify.service.multirole.MultiRoleRuntimeValidator
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class VoiceLoadState(
    val loading: Boolean = false,
    val voices: List<VoiceInfo> = emptyList(),
    val failed: Boolean = false,
)

data class MultiRoleUiState(
    val loading: Boolean = true,
    val storage: MultiRoleState = MultiRoleState(),
    val defaultProviderId: String = TtsProviderRegistry.defaultProvider.id,
    val voices: Map<String, VoiceLoadState> = emptyMap(),
    val runtimeProfileId: ProfileId? = null,
    val runtimeCheck: MultiRoleRuntimeValidator.Result? = null,
    val validationIssues: List<MultiRoleIssue> = emptyList(),
    val busy: Boolean = false,
    val analysis: ParsedReading? = null,
    /** 试听原文只保存在 ViewModel 内存中，不写入 SavedStateHandle/持久存储。 */
    val previewText: String = "",
    val previewPlaying: Boolean = false,
    val voicePreviewPlayingId: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val settings: MultiRoleSettings get() = storage.settings
    val activeProfile: ReadingProfile? get() = settings.activeProfile()
    val failureAvailable: Boolean get() = storage.failure != null
}

/**
 * 多角色设置页状态与操作。
 *
 * 所有持久化都经 [MultiRoleRepository] 原子事务；请求/试听读取不可变快照或
 * 当前 state flow 中的已生效配置。试听原文只保存在本 ViewModel 内存中，不写入
 * SavedStateHandle 或任何持久存储。
 */
class MultiRoleViewModel(application: Application) : AndroidViewModel(application) {

    private val logTag = "MultiRoleViewModel"
    private val app = application
    private val repository: MultiRoleRepository = MultiRoleRepositoryProvider.get(application)
    private val appConfigRepository = SharedPreferencesAppConfigRepository(application)

    private val _uiState = MutableStateFlow(MultiRoleUiState())
    val uiState: StateFlow<MultiRoleUiState> = _uiState.asStateFlow()

    private val runtimeGeneration = AtomicLong(0)
    private var runtimeJob: Job? = null
    @Volatile
    private var previewPlayer: MultiRolePreviewPlayer? = null

    @Volatile
    private var voicePreviewPlayer: TtsPreviewPlayer? = null

    @Volatile
    private var voicePreviewVoiceId: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val main = appConfigRepository.getSelectedProviderId()
                ?: TtsProviderRegistry.defaultProvider.id
            _uiState.update { it.copy(defaultProviderId = main) }
            loadVoicesBlocking(main)
        }
        viewModelScope.launch {
            repository.state.collect { storage ->
                _uiState.update { it.copy(loading = false, storage = storage) }
                scheduleRuntimeCheck(storage)
            }
        }
    }

    // ==================== 读取/加载 ====================

    fun providerName(providerId: String): String =
        TtsProviderRegistry.getProvider(providerId)?.name ?: providerId

    fun defaultProviderId(): String = _uiState.value.defaultProviderId

    fun loadVoices(providerId: String) {
        val current = _uiState.value.voices[providerId]
        if (current?.loading == true || (current != null && !current.failed)) return
        viewModelScope.launch(Dispatchers.IO) { loadVoicesBlocking(providerId) }
    }

    private suspend fun loadVoicesBlocking(providerId: String, force: Boolean = false) {
        if (!force) {
            val current = _uiState.value.voices[providerId]
            if (current?.loading == true || (current != null && current.voices.isNotEmpty())) return
        }
        _uiState.update {
            it.copy(voices = it.voices + (providerId to VoiceLoadState(loading = true)))
        }
        val provider = TtsProviderRegistry.getProvider(providerId)
        val repository = TtsProviderFactory.createVoiceRepository(providerId, app)
        val loaded = try {
            if (provider == null || repository == null) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) { repository.getVoicesForProvider(provider) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TtsLogger.e("Failed to load voices for $providerId", e, logTag)
            emptyList()
        }
        // OpenAI 兼容配置中的自定义声音 ID 是当前真实可能生效的声音，显式列出，
        // 不把它伪装成预置音色；可用性/格式由供应商能力校验。
        val voices = loaded.toMutableList()
        val providerConfig = TtsProviderFactory.createConfigRepository(providerId, app)
            ?.getConfig(providerId)
        if (providerConfig is OpenAIConfig) {
            val custom = providerConfig.customVoiceId.trim()
            if (custom.isNotBlank() && voices.none { it.voiceId == custom }) {
                voices.add(
                    0,
                    VoiceInfo(
                        voiceId = custom,
                        displayName = app.getString(R.string.multirole_voice_custom_current),
                        group = provider?.name,
                    )
                )
            }
        }
        _uiState.update {
            it.copy(
                voices = it.voices + (providerId to VoiceLoadState(
                    loading = false,
                    voices = voices,
                    failed = voices.isEmpty(),
                ))
            )
        }
    }

    suspend fun validateProfile(profile: ReadingProfile): MultiRoleRuntimeValidator.Result =
        withContext(Dispatchers.IO) { MultiRoleRuntimeValidator.validateProfile(app, profile) }

    /** 从系统设置/其他页面返回时重新校验外部可能变化的供应商配置与音色。 */
    fun refreshRuntimeCheck() {
        scheduleRuntimeCheck(_uiState.value.storage)
        _uiState.value.activeProfile?.let { profile ->
            viewModelScope.launch(Dispatchers.IO) { loadVoicesBlocking(profile.providerId, force = true) }
        }
    }

    fun clearValidation() {
        _uiState.update { it.copy(validationIssues = emptyList()) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun clearAnalysis() {
        _uiState.update { it.copy(analysis = null) }
    }

    fun setPreviewText(text: String) {
        _uiState.update {
            it.copy(
                previewText = text,
                // 文本变化后旧分析结果失效，避免把上一段文本的角色标签误当成本段结果
                analysis = if (text == it.previewText) it.analysis else null,
            )
        }
    }

    // ==================== 方案操作 ====================

    fun createProfile(
        name: String,
        providerId: String,
        narratorVoiceId: String?,
        defaultVoiceId: String?,
        delimiters: List<DelimiterPair>,
    ) {
        launchMutation {
            val current = _uiState.value.settings
            if (current.profiles.size >= MultiRoleLimits.MAX_PROFILES) {
                setValidation(listOf(MultiRoleIssue(MultiRoleIssueCode.PROFILE_COUNT_LIMIT)))
                return@launchMutation
            }
            if (MultiRoleValidator.normalizeForMatch(name).isBlank()) {
                setValidation(listOf(MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_EMPTY)))
                return@launchMutation
            }
            if (current.profiles.any {
                    MultiRoleValidator.normalizeForMatch(it.name) ==
                        MultiRoleValidator.normalizeForMatch(name)
                }
            ) {
                setValidation(listOf(MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_DUPLICATE)))
                return@launchMutation
            }
            val profile = ReadingProfile(
                id = MultiRoleValidator.newProfileId(),
                name = name.trim(),
                providerId = providerId,
                narratorVoice = narratorVoiceId?.takeIf { it.isNotBlank() }?.let { VoiceRef(it) },
                defaultRoleVoice = defaultVoiceId?.takeIf { it.isNotBlank() }?.let { VoiceRef(it) },
                roles = emptyList(),
                delimiters = delimiters,
            )
            val static = MultiRoleValidator.validateProfile(
                profile, current.profiles + profile
            )
            if (static.isNotEmpty()) {
                setValidation(static)
                return@launchMutation
            }
            val result = repository.update { settings ->
                // 旧 active ID 指向不存在的方案时，新方案成为当前方案
                val hasValidActive = settings.profiles.any { it.id == settings.activeProfileId }
                settings.copy(
                    activeProfileId = if (hasValidActive) settings.activeProfileId else profile.id,
                    profiles = settings.profiles + profile,
                )
            }
            handleUpdateResult(result)
            if (result is MultiRoleUpdateResult.Success) {
                loadVoicesBlocking(providerId)
                showMessage(app.getString(R.string.multirole_profile_created), error = false)
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        launchMutation {
            if (!enabled) {
                val result = repository.update { it.copy(enabled = false) }
                handleUpdateResult(result)
                if (result is MultiRoleUpdateResult.Success) stopPreview()
                return@launchMutation
            }
            val current = _uiState.value.settings
            val active = current.activeProfile()
            if (active == null) {
                setValidation(listOf(MultiRoleIssue(MultiRoleIssueCode.ENABLED_REQUIRES_VALID_PROFILE)))
                return@launchMutation
            }
            val static = MultiRoleValidator.validateProfile(
                active, current.profiles
            )
            if (static.isNotEmpty()) {
                setValidation(static)
                return@launchMutation
            }
            val runtime = validateProfile(active)
            if (!runtime.ok) {
                setValidation(runtime.issues)
                return@launchMutation
            }
            val result = repository.update { it.copy(enabled = true) }
            handleUpdateResult(result)
            if (result is MultiRoleUpdateResult.Success) {
                showMessage(app.getString(R.string.multirole_enabled_message), error = false)
            }
        }
    }

    fun setActiveProfile(profileId: ProfileId) {
        launchMutation {
            val current = _uiState.value.settings
            val target = current.profiles.firstOrNull { it.id == profileId }
                ?: return@launchMutation
            if (current.enabled) {
                val static = MultiRoleValidator.validateProfile(target, current.profiles)
                if (static.isNotEmpty()) {
                    setValidation(static)
                    return@launchMutation
                }
                val runtime = validateProfile(target)
                if (!runtime.ok) {
                    setValidation(runtime.issues)
                    return@launchMutation
                }
            }
            val result = repository.update { it.copy(activeProfileId = profileId) }
            handleUpdateResult(result)
        }
    }

    fun saveProfile(profile: ReadingProfile) {
        launchMutation {
            val current = _uiState.value.settings
            val existing = current.profiles.firstOrNull { it.id == profile.id }
            if (existing != null && existing.providerId != profile.providerId) {
                showMessage(app.getString(R.string.multirole_provider_immutable), error = true)
                return@launchMutation
            }
            if (current.enabled && current.activeProfileId == profile.id) {
                val static = MultiRoleValidator.validateProfile(profile, current.profiles)
                if (static.isNotEmpty()) {
                    setValidation(static)
                    return@launchMutation
                }
                val runtime = validateProfile(profile)
                if (!runtime.ok) {
                    setValidation(runtime.issues)
                    return@launchMutation
                }
            }
            val result = repository.update { settings ->
                settings.copy(
                    profiles = settings.profiles.map { if (it.id == profile.id) profile else it }
                )
            }
            handleUpdateResult(result)
        }
    }

    fun renameProfile(profileId: ProfileId, newName: String) {
        launchMutation {
            val current = _uiState.value.settings
            val normalized = MultiRoleValidator.normalizeForMatch(newName)
            if (normalized.isBlank()) {
                setValidation(listOf(MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_EMPTY, profileId)))
                return@launchMutation
            }
            if (MultiRoleValidator.codepointCount(newName.trim()) > MultiRoleLimits.MAX_NAME_CODE_POINTS) {
                setValidation(listOf(MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_TOO_LONG, profileId)))
                return@launchMutation
            }
            if (current.profiles.any {
                    it.id != profileId &&
                        MultiRoleValidator.normalizeForMatch(it.name) == normalized
                }
            ) {
                setValidation(listOf(MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_DUPLICATE, profileId)))
                return@launchMutation
            }
            val result = repository.update { settings ->
                settings.copy(profiles = settings.profiles.map {
                    if (it.id == profileId) it.copy(name = newName.trim()) else it
                })
            }
            handleUpdateResult(result)
        }
    }

    fun copyProfile(source: ReadingProfile, newName: String, newProviderId: String) {
        launchMutation {
            val current = _uiState.value.settings
            if (current.profiles.size >= MultiRoleLimits.MAX_PROFILES) {
                setValidation(listOf(MultiRoleIssue(MultiRoleIssueCode.PROFILE_COUNT_LIMIT)))
                return@launchMutation
            }
            val providerChanged = source.providerId != newProviderId
            val name = uniqueProfileName(
                newName.ifBlank { app.getString(R.string.multirole_copy_default_name, source.name) },
                current.profiles,
            )
            val copy = source.copy(
                id = MultiRoleValidator.newProfileId(),
                name = name,
                providerId = newProviderId,
                narratorVoice = if (providerChanged) null else source.narratorVoice,
                defaultRoleVoice = if (providerChanged) null else source.defaultRoleVoice,
                roles = source.roles.map {
                    it.copy(
                        id = MultiRoleValidator.newRoleId(),
                        voice = if (providerChanged) null else it.voice,
                    )
                },
            )
            val result = repository.update {
                it.copy(profiles = it.profiles + copy)
            }
            handleUpdateResult(result)
            if (result is MultiRoleUpdateResult.Success) {
                loadVoicesBlocking(newProviderId)
                showMessage(app.getString(R.string.multirole_profile_copied), error = false)
            }
        }
    }

    fun deleteProfile(profileId: ProfileId) {
        launchMutation {
            val result = repository.update { settings ->
                val isActive = settings.activeProfileId == profileId
                settings.copy(
                    profiles = settings.profiles.filterNot { it.id == profileId },
                    activeProfileId = if (isActive) null else settings.activeProfileId,
                    enabled = if (isActive) false else settings.enabled,
                )
            }
            handleUpdateResult(result)
            if (result is MultiRoleUpdateResult.Success) {
                stopPreview()
                showMessage(app.getString(R.string.multirole_profile_deleted), error = false)
            }
        }
    }

    fun updateNarratorVoice(profileId: ProfileId, voiceId: String) {
        updateProfilePart(profileId) { it.copy(narratorVoice = VoiceRef(voiceId)) }
    }

    fun updateDefaultRoleVoice(profileId: ProfileId, voiceId: String) {
        updateProfilePart(profileId) { it.copy(defaultRoleVoice = VoiceRef(voiceId)) }
    }

    fun upsertRole(profileId: ProfileId, role: Role) {
        updateProfilePart(profileId) { profile ->
            val exists = profile.roles.any { it.id == role.id }
            profile.copy(
                roles = if (exists) {
                    profile.roles.map { if (it.id == role.id) role else it }
                } else {
                    profile.roles + role
                }
            )
        }
    }

    fun deleteRole(profileId: ProfileId, roleId: RoleId) {
        updateProfilePart(profileId) { profile ->
            profile.copy(roles = profile.roles.filterNot { it.id == roleId })
        }
    }

    fun updateDelimiters(profileId: ProfileId, delimiters: List<DelimiterPair>) {
        updateProfilePart(profileId) { it.copy(delimiters = delimiters) }
    }

    private fun updateProfilePart(
        profileId: ProfileId,
        transform: (ReadingProfile) -> ReadingProfile,
    ) {
        launchMutation {
            val current = _uiState.value.settings
            val existing = current.profiles.firstOrNull { it.id == profileId }
                ?: return@launchMutation
            val candidate = transform(existing)
            if (current.enabled && current.activeProfileId == profileId) {
                val static = MultiRoleValidator.validateProfile(candidate, current.profiles)
                if (static.isNotEmpty()) {
                    setValidation(static)
                    return@launchMutation
                }
                val runtime = validateProfile(candidate)
                if (!runtime.ok) {
                    setValidation(runtime.issues)
                    return@launchMutation
                }
            }
            val result = repository.update { settings ->
                settings.copy(profiles = settings.profiles.map {
                    if (it.id == profileId) candidate else it
                })
            }
            handleUpdateResult(result)
        }
    }

    fun resetCorruptedSettings() {
        launchMutation {
            when (val result = repository.reset()) {
                is MultiRoleUpdateResult.Success -> {
                    stopPreview()
                    showMessage(app.getString(R.string.multirole_reset_done), error = false)
                }
                is MultiRoleUpdateResult.Failure ->
                    showMessage(app.getString(R.string.multirole_save_failed), error = true)
            }
        }
    }

    // ==================== 分析/试听 ====================

    fun analyzeText(text: String) {
        val profile = _uiState.value.activeProfile
        if (profile == null) {
            showMessage(app.getString(R.string.multirole_preview_need_profile), error = true)
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val parsed = MultiRoleParser.parse(text, profile)
                _uiState.update { it.copy(analysis = parsed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TtsLogger.e("MultiRole analysis failed: ${e.javaClass.simpleName}", e, logTag)
                showMessage(app.getString(R.string.multirole_save_failed), error = true)
            }
        }
    }

    fun playPreview(text: String) {
        stopVoicePreview()
        val state = _uiState.value
        val profile = state.activeProfile
        if (profile == null) {
            showMessage(app.getString(R.string.multirole_preview_need_profile), error = true)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val config = TtsProviderFactory
                .createConfigRepository(profile.providerId, app)
                ?.getConfig(profile.providerId)
            if (config == null) {
                showMessage(app.getString(R.string.multirole_preview_provider_unavailable), error = true)
                return@launch
            }
            val runtime = MultiRoleRuntimeValidator.validateProfile(app, profile)
            if (!runtime.ok) {
                setValidation(runtime.issues)
                return@launch
            }
            withContext(Dispatchers.Main) {
                val player = previewPlayer ?: MultiRolePreviewPlayer().also { created ->
                    created.setStateListener { stateCode, message ->
                        _uiState.update {
                            it.copy(
                                previewPlaying = stateCode == MultiRolePreviewPlayer.STATE_PLAYING,
                                message = message ?: it.message,
                                messageIsError = message != null,
                            )
                        }
                    }
                    previewPlayer = created
                }
                player.speak(profile, config, text)
            }
        }
    }

    fun stopPreview() {
        previewPlayer?.stop()
        _uiState.update { it.copy(previewPlaying = false) }
    }

    fun stopVoicePreview() {
        voicePreviewPlayer?.stop()
        voicePreviewPlayer?.release()
        voicePreviewPlayer = null
        voicePreviewVoiceId = null
        _uiState.update { it.copy(voicePreviewPlayingId = null) }
    }

    /**
     * 单个角色音色的试听；使用与系统朗读相同的供应商配置与请求级覆盖路径。
     * 试听样本只来自字符串资源，不写入日志/遥测/持久存储。
     */
    fun toggleVoicePreview(providerId: String, voiceId: String) {
        // 多角色试听与单音色试听互斥，避免两条音频同时播放
        if (_uiState.value.previewPlaying) stopPreview()
        val current = voicePreviewPlayer
        if (current != null && voicePreviewVoiceId == voiceId) {
            current.stop()
            current.release()
            voicePreviewPlayer = null
            voicePreviewVoiceId = null
            _uiState.update { it.copy(voicePreviewPlayingId = null) }
            return
        }
        current?.stop()
        current?.release()
        voicePreviewPlayer = null
        voicePreviewVoiceId = null
        viewModelScope.launch(Dispatchers.IO) {
            val config = TtsProviderFactory.createConfigRepository(providerId, app)
                ?.getConfig(providerId)
            if (config == null) {
                showMessage(app.getString(R.string.multirole_preview_provider_unavailable), error = true)
                return@launch
            }
            val sample = app.resources.getStringArray(R.array.demo_texts).firstOrNull()
                ?: return@launch
            val player = TtsPreviewPlayer(providerId)
            player.setStateListener { state, message ->
                val playing = state == TtsPreviewPlayer.STATE_PLAYING
                _uiState.update {
                    it.copy(
                        voicePreviewPlayingId = if (playing) voiceId else it.voicePreviewPlayingId,
                        // 不把上游错误正文/供应商响应放进 UI 提示
                        message = if (message != null) {
                            app.getString(R.string.multirole_voice_preview_failed)
                        } else {
                            it.message
                        },
                        messageIsError = message != null,
                    )
                }
                if (state == TtsPreviewPlayer.STATE_IDLE || state == TtsPreviewPlayer.STATE_ERROR) {
                    if (voicePreviewVoiceId == voiceId) voicePreviewVoiceId = null
                    _uiState.update { it.copy(voicePreviewPlayingId = null) }
                }
            }
            voicePreviewPlayer = player
            voicePreviewVoiceId = voiceId
            _uiState.update { it.copy(voicePreviewPlayingId = voiceId) }
            // 显式请求级覆盖：OpenAI 的 customVoiceId 优先规则不得盖住用户试听的音色
            player.speak(sample, config, SynthesisParams(language = "Auto", voiceOverride = voiceId))
        }
    }

    // ==================== 内部 ====================

    private fun launchMutation(block: suspend () -> Unit) {
        viewModelScope.launch {
            if (_uiState.value.busy) return@launch
            _uiState.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TtsLogger.e("MultiRole mutation failed: ${e.javaClass.simpleName}", e, logTag)
                showMessage(app.getString(R.string.multirole_save_failed), error = true)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun scheduleRuntimeCheck(storage: MultiRoleState) {
        val active = storage.settings.activeProfile()
        val generation = runtimeGeneration.incrementAndGet()
        runtimeJob?.cancel()
        if (active == null || storage.failure != null) {
            _uiState.update { it.copy(runtimeProfileId = null, runtimeCheck = null) }
            return
        }
        runtimeJob = viewModelScope.launch(Dispatchers.IO) {
            val result = MultiRoleRuntimeValidator.validateProfile(app, active)
            if (generation == runtimeGeneration.get()) {
                _uiState.update {
                    it.copy(runtimeProfileId = active.id, runtimeCheck = result)
                }
            }
        }
    }

    private fun handleUpdateResult(result: MultiRoleUpdateResult) {
        when (result) {
            is MultiRoleUpdateResult.Success -> {
                _uiState.update { it.copy(validationIssues = emptyList()) }
                runtimeGeneration.incrementAndGet()
                scheduleRuntimeCheck(MultiRoleState(settings = result.settings))
            }
            is MultiRoleUpdateResult.Failure -> {
                showMessage(app.getString(R.string.multirole_save_failed), error = true)
            }
        }
    }

    private fun setValidation(issues: List<MultiRoleIssue>) {
        _uiState.update { it.copy(validationIssues = issues) }
    }

    private fun showMessage(message: String, error: Boolean) {
        _uiState.update { it.copy(message = message, messageIsError = error) }
    }

    private fun uniqueProfileName(base: String, profiles: List<ReadingProfile>): String {
        val used = profiles.map { MultiRoleValidator.normalizeForMatch(it.name) }.toSet()
        if (MultiRoleValidator.normalizeForMatch(base) !in used) return base
        var index = 2
        while (true) {
            val candidate = "$base ($index)"
            if (MultiRoleValidator.normalizeForMatch(candidate) !in used) return candidate
            index++
        }
    }

    override fun onCleared() {
        runtimeJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
        voicePreviewPlayer?.release()
        voicePreviewPlayer = null
    }
}
