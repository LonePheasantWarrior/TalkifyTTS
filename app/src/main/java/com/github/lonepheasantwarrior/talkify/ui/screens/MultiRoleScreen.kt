@file:Suppress("LocalContextGetResourceValueCall", "LocalContextResourcesRead")

package com.github.lonepheasantwarrior.talkify.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.DelimiterPair
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleDelimiterPresets
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssue
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssueCode
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleLimits
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ProfileId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.Role
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef
import com.github.lonepheasantwarrior.talkify.domain.multirole.AttributionReason
import com.github.lonepheasantwarrior.talkify.domain.multirole.SegmentKind
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.ui.components.MarkdownText
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.multirole.MultiRoleUiState
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.multirole.MultiRoleViewModel
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.multirole.VoiceLoadState

/**
 * 多角色朗读独立设置页。
 *
 * 页面只负责编辑与展示：解析/试听/持久化全部经 ViewModel 与领域层完成。
 * 保存失败或目标方案未通过校验时保留已生效配置，并就地列出可修复项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiRoleScreen(
    onBack: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    viewModel: MultiRoleViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val demoSamples = stringArrayResource(R.array.demo_texts)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshRuntimeCheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreate by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ReadingProfile?>(null) }
    var copyTarget by remember { mutableStateOf<ReadingProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<ReadingProfile?>(null) }
    var roleEditor by remember { mutableStateOf<RoleEditorState?>(null) }
    var deleteRoleTarget by remember { mutableStateOf<Pair<ReadingProfile, Role>?>(null) }
    var voiceTarget by remember { mutableStateOf<VoiceTarget?>(null) }
    var showDelimiters by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.activeProfile?.id) {
        viewModel.clearAnalysis()
    }

    LaunchedEffect(state.activeProfile?.id, state.activeProfile?.providerId) {
        state.activeProfile?.providerId?.let(viewModel::loadVoices)
    }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.multirole_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_multirole_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.HelpOutline,
                            contentDescription = stringResource(R.string.multirole_help_title)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.multirole_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.failureAvailable -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StorageFailureCard(
                            state = state,
                            onReset = { showResetConfirm = true },
                            onOpenProviderSettings = onOpenProviderSettings
                        )
                    }
                }
                else -> {
                    val active = state.activeProfile
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = 840.dp)
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            EnableCard(
                                state = state,
                                onEnabledChange = viewModel::setEnabled
                            )
                        }
                        if (active == null && state.settings.profiles.isEmpty()) {
                            item {
                                FirstUseCard(
                                    onCreate = { showCreate = true },
                                    onOpenProviderSettings = onOpenProviderSettings
                                )
                            }
                        } else {
                            item {
                                CurrentProfileCard(
                                    state = state,
                                    onSwitchProfile = { showSwitch = true },
                                    onChangeNarrator = {
                                        voiceTarget = VoiceTarget(
                                            titleRes = R.string.multirole_narrator_voice,
                                            providerId = active!!.providerId,
                                            currentVoiceId = active.narratorVoice?.voiceId,
                                            onSelected = { voiceId ->
                                                viewModel.updateNarratorVoice(active.id, voiceId)
                                            },
                                        )
                                    },
                                    onChangeDefaultRole = {
                                        voiceTarget = VoiceTarget(
                                            titleRes = R.string.multirole_default_role_voice,
                                            providerId = active!!.providerId,
                                            currentVoiceId = active.defaultRoleVoice?.voiceId,
                                            onSelected = { voiceId ->
                                                viewModel.updateDefaultRoleVoice(active.id, voiceId)
                                            },
                                        )
                                    },
                                    onCopy = { copyTarget = active },
                                    onRename = { renameTarget = active },
                                    onDelete = { deleteTarget = active },
                                    onOpenProviderSettings = onOpenProviderSettings,
                                )
                            }
                            if (state.runtimeProfileId == active?.id &&
                                state.runtimeCheck?.ok == false
                            ) {
                                item {
                                    RepairCard(
                                        issues = state.runtimeCheck?.issues.orEmpty(),
                                        onOpenProviderSettings = onOpenProviderSettings
                                    )
                                }
                            }
                            item {
                                RolesSection(
                                    profile = active,
                                    state = state,
                                    onAddRole = {
                                        roleEditor = RoleEditorState(
                                            profileId = active!!.id,
                                            roleId = MultiRoleValidator.newRoleId(),
                                            originalName = "",
                                            name = "",
                                            aliasesText = "",
                                            voiceId = null,
                                        )
                                    },
                                    onEditRole = { role ->
                                        roleEditor = RoleEditorState(
                                            profileId = active!!.id,
                                            roleId = role.id,
                                            originalName = role.name,
                                            name = role.name,
                                            aliasesText = role.aliases.joinToString("\n"),
                                            voiceId = role.voice?.voiceId,
                                        )
                                    },
                                    onDeleteRole = { role ->
                                        if (active != null) deleteRoleTarget = active to role
                                    },
                                )
                            }
                            item {
                                SettingsRow(
                                    icon = Icons.Rounded.Tune,
                                    title = stringResource(R.string.multirole_delimiters),
                                    summary = delimiterSummary(
                                        state.settings.profiles
                                            .firstOrNull { it.id == active?.id }
                                            ?.delimiters.orEmpty()
                                    ),
                                    onClick = { showDelimiters = true },
                                )
                            }
                            item {
                                PreviewSection(
                                    state = state,
                                    onTextChange = viewModel::setPreviewText,
                                    onAnalyze = { viewModel.analyzeText(state.previewText) },
                                    onPlay = { viewModel.playPreview(state.previewText) },
                                    onStop = viewModel::stopPreview,
                                    onUseSample = {
                                        if (demoSamples.isNotEmpty()) {
                                            viewModel.setPreviewText(demoSamples.first())
                                        }
                                    },
                                )
                            }
                            item {
                                SettingsRow(
                                    icon = Icons.AutoMirrored.Rounded.HelpOutline,
                                    title = stringResource(R.string.multirole_help_section),
                                    summary = stringResource(R.string.multirole_help_summary),
                                    onClick = { showHelp = true },
                                )
                            }
                            item {
                                ProfileActionsRow(
                                    onCreate = { showCreate = true },
                                    onCopy = { copyTarget = active },
                                    onRename = { renameTarget = active },
                                    onDelete = { deleteTarget = active },
                                    onOpenProviderSettings = onOpenProviderSettings,
                                )
                            }
                        }
                    }
                }
            }

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (state.validationIssues.isNotEmpty()) {
        IssueDialog(
            issues = state.validationIssues,
            onDismiss = viewModel::clearValidation,
        )
    }

    if (showCreate) {
        CreateProfileDialog(
            state = state,
            onDismiss = { showCreate = false },
            onLoadVoices = viewModel::loadVoices,
            onPickVoice = { target -> voiceTarget = target },
            onCreate = { name, providerId, narrator, defaultVoice ->
                viewModel.createProfile(
                    name = name,
                    providerId = providerId,
                    narratorVoiceId = narrator,
                    defaultVoiceId = defaultVoice,
                    delimiters = MultiRoleSettings.defaultDelimiterPairs(),
                )
                showCreate = false
            },
        )
    }

    if (showSwitch) {
        SwitchProfileDialog(
            state = state,
            onDismiss = { showSwitch = false },
            onSelect = { profileId ->
                viewModel.setActiveProfile(profileId)
                showSwitch = false
            },
        )
    }

    renameTarget?.let { profile ->
        RenameProfileDialog(
            profile = profile,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renameProfile(profile.id, name)
                renameTarget = null
            },
        )
    }

    copyTarget?.let { profile ->
        CopyProfileDialog(
            profile = profile,
            state = state,
            onDismiss = { copyTarget = null },
            onLoadVoices = viewModel::loadVoices,
            onConfirm = { name, providerId ->
                viewModel.copyProfile(profile, name, providerId)
                copyTarget = null
            },
        )
    }

    deleteTarget?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            isActive = state.settings.activeProfileId == profile.id,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.deleteProfile(profile.id)
                deleteTarget = null
            },
        )
    }

    roleEditor?.let { editor ->
        RoleEditorDialog(
            state = state,
            editor = editor,
            onDismiss = { roleEditor = null },
            onUpdateVoice = { voiceId -> roleEditor = editor.copy(voiceId = voiceId) },
            onPickVoice = { target -> voiceTarget = target },
            onSave = { role ->
                viewModel.upsertRole(editor.profileId, role)
                roleEditor = null
            },
        )
    }

    deleteRoleTarget?.let { (profile, role) ->
        AlertDialog(
            onDismissRequest = { deleteRoleTarget = null },
            title = { Text(stringResource(R.string.multirole_delete_role_title, role.name)) },
            text = { Text(stringResource(R.string.multirole_delete_role_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRole(profile.id, role.id)
                    deleteRoleTarget = null
                }) { Text(stringResource(R.string.multirole_dialog_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteRoleTarget = null }) {
                    Text(stringResource(R.string.multirole_dialog_cancel))
                }
            },
        )
    }

    voiceTarget?.let { target ->
        VoicePickerDialog(
            target = target,
            state = state,
            onDismiss = {
                viewModel.stopVoicePreview()
                voiceTarget = null
            },
            onLoadVoices = viewModel::loadVoices,
            onSelect = { voiceId ->
                viewModel.stopVoicePreview()
                target.onSelected(voiceId)
                voiceTarget = null
            },
            onTogglePreview = viewModel::toggleVoicePreview,
        )
    }

    val activeProfile = state.activeProfile
    if (showDelimiters && activeProfile != null) {
        DelimiterEditorDialog(
            profile = activeProfile,
            onDismiss = { showDelimiters = false },
            onSave = { delimiters ->
                viewModel.updateDelimiters(activeProfile.id, delimiters)
                showDelimiters = false
            },
        )
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.multirole_reset_confirm_title)) },
            text = { Text(stringResource(R.string.multirole_reset_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    viewModel.resetCorruptedSettings()
                }) { Text(stringResource(R.string.multirole_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.multirole_dialog_cancel))
                }
            },
        )
    }
}

// ==================== 页面区块 ====================

@Composable
private fun EnableCard(
    state: MultiRoleUiState,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.multirole_switch_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.multirole_switch_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.settings.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !state.busy,
            )
        }
    }
}

@Composable
private fun FirstUseCard(
    onCreate: () -> Unit,
    onOpenProviderSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = stringResource(R.string.multirole_first_use_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.multirole_first_use_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCreate) {
                    Text(stringResource(R.string.multirole_create_first_profile))
                }
                TextButton(onClick = onOpenProviderSettings) {
                    Text(stringResource(R.string.multirole_open_provider_settings))
                }
            }
        }
    }
}

@Composable
private fun CurrentProfileCard(
    state: MultiRoleUiState,
    onSwitchProfile: () -> Unit,
    onChangeNarrator: () -> Unit,
    onChangeDefaultRole: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenProviderSettings: () -> Unit,
) {
    val active = state.activeProfile
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.multirole_current_profile),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (state.settings.profiles.size > 1 || active == null) {
                    TextButton(onClick = onSwitchProfile) {
                        Text(stringResource(R.string.multirole_switch_profile))
                    }
                }
                if (active != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.cd_multirole_more_actions)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.multirole_copy_profile)) },
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                onClick = { menuOpen = false; onCopy() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.multirole_rename_profile)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; onRename() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.multirole_delete_profile)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }
            }
            if (active == null) {
                Text(
                    text = stringResource(R.string.multirole_no_profile),
                    style = MaterialTheme.typography.titleMedium
                )
            } else {
                Text(
                    text = active.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (active.narratorVoice == null || active.defaultRoleVoice == null) {
                        stringResource(
                            R.string.multirole_profile_meta_no_voice,
                            providerName(active.providerId),
                        )
                    } else {
                        stringResource(
                            R.string.multirole_profile_meta,
                            providerName(active.providerId),
                            active.roles.size,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                VoiceRow(
                    label = stringResource(R.string.multirole_narrator_voice),
                    voiceId = active.narratorVoice?.voiceId,
                    providerId = active.providerId,
                    state = state,
                    onClick = onChangeNarrator,
                )
                VoiceRow(
                    label = stringResource(R.string.multirole_default_role_voice),
                    voiceId = active.defaultRoleVoice?.voiceId,
                    providerId = active.providerId,
                    state = state,
                    onClick = onChangeDefaultRole,
                )
                Text(
                    text = stringResource(R.string.multirole_default_role_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.multirole_provider_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onOpenProviderSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.multirole_open_provider_settings))
                }
            }
            if (state.settings.enabled && state.activeProfile != null) {
                Text(
                    text = stringResource(R.string.multirole_enabled_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(
    label: String,
    voiceId: String?,
    providerId: String,
    state: MultiRoleUiState,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = voiceId?.let { voiceDisplayName(state.voices[providerId], it) }
                    ?: stringResource(R.string.multirole_voice_unset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.multirole_change_voice))
        }
    }
}

@Composable
private fun RepairCard(
    issues: List<MultiRoleIssue>,
    onOpenProviderSettings: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.multirole_repair_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.multirole_repair_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            issues.take(6).forEach { issue ->
                Text(
                    text = "• " + issueText(context, issue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            TextButton(onClick = onOpenProviderSettings) {
                Text(stringResource(R.string.multirole_open_provider_settings))
            }
        }
    }
}

@Composable
private fun RolesSection(
    profile: ReadingProfile?,
    state: MultiRoleUiState,
    onAddRole: () -> Unit,
    onEditRole: (Role) -> Unit,
    onDeleteRole: (Role) -> Unit,
) {
    if (profile == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.multirole_roles),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onAddRole,
                    enabled = profile.roles.size < MultiRoleLimits.MAX_ROLES_PER_PROFILE,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.multirole_add_role))
                }
            }
            if (profile.roles.isEmpty()) {
                Text(
                    text = stringResource(R.string.multirole_roles_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            profile.roles.forEach { role ->
                RoleRow(
                    role = role,
                    state = state,
                    providerId = profile.providerId,
                    onClick = { onEditRole(role) },
                    onDelete = { onDeleteRole(role) },
                )
            }
        }
    }
}

@Composable
private fun RoleRow(
    role: Role,
    state: MultiRoleUiState,
    providerId: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (role.name.isEmpty()) {
                        "?"
                    } else {
                        String(Character.toChars(role.name.codePointAt(0)))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = role.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (role.aliases.isEmpty()) {
                    stringResource(R.string.multirole_role_no_alias)
                } else {
                    stringResource(
                        R.string.multirole_role_aliases,
                        role.aliases.joinToString("、")
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = role.voice?.voiceId?.let { voiceDisplayName(state.voices[providerId], it) }
                ?: stringResource(R.string.multirole_voice_unset),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp)
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.cd_multirole_role_more)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.multirole_edit_role_title)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onClick() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.multirole_delete)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun PreviewSection(
    state: MultiRoleUiState,
    onTextChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onUseSample: () -> Unit,
) {
    val samples = stringArrayResource(R.array.demo_texts)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.multirole_preview_section),
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = state.previewText,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
                placeholder = { Text(stringResource(R.string.multirole_test_text_hint)) },
                minLines = 3,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onUseSample,
                    enabled = samples.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.multirole_use_sample))
                }
                OutlinedButton(
                    onClick = onAnalyze,
                    enabled = state.previewText.isNotBlank()
                ) {
                    Text(stringResource(R.string.multirole_analyze))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = if (state.previewPlaying) onStop else onPlay) {
                    Icon(
                        if (state.previewPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(
                            if (state.previewPlaying) R.string.multirole_preview_stop
                            else R.string.multirole_preview_play
                        )
                    )
                }
            }
            Text(
                text = stringResource(R.string.multirole_preview_not_persisted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.multirole_preview_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.analysis?.let { parsed ->
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.multirole_analysis_result),
                    style = MaterialTheme.typography.titleSmall
                )
                if (parsed.plan.warnings.unclosedQuote) {
                    Text(
                        text = stringResource(R.string.multirole_analysis_unclosed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (parsed.plan.warnings.budgetExceeded || parsed.plan.warnings.limitExceeded) {
                    Text(
                        text = stringResource(R.string.multirole_analysis_limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (parsed.plan.analysis.isEmpty()) {
                    Text(
                        text = stringResource(R.string.multirole_analysis_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        parsed.plan.analysis.take(100).forEach { segment ->
                            AnalysisRow(
                                segment = segment,
                                text = parsed.plan.text.substring(
                                    segment.start, segment.endExclusive
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisRow(
    segment: com.github.lonepheasantwarrior.talkify.domain.multirole.AnalyzedSegment,
    text: String,
) {
    val label = when {
        segment.kind == SegmentKind.NARRATION -> stringResource(R.string.multirole_analysis_narration)
        segment.defaultRole -> stringResource(R.string.multirole_analysis_default_role)
        else -> segment.roleName ?: stringResource(R.string.multirole_analysis_default_role)
    }
    val reason = when (segment.reason) {
        AttributionReason.LEFT_LABEL -> stringResource(R.string.multirole_analysis_reason_left)
        AttributionReason.RIGHT_LABEL -> stringResource(R.string.multirole_analysis_reason_right)
        AttributionReason.BOTH_SIDES -> stringResource(R.string.multirole_analysis_reason_both)
        AttributionReason.CONFLICT -> stringResource(R.string.multirole_analysis_reason_conflict)
        AttributionReason.NO_EVIDENCE -> stringResource(R.string.multirole_analysis_reason_none)
        null -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = if (segment.kind == SegmentKind.NARRATION) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (segment.kind == SegmentKind.NARRATION) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProfileActionsRow(
    onCreate: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenProviderSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.multirole_profiles_title),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onCreate) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.multirole_create_profile))
            }
            TextButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.multirole_copy_profile))
            }
            TextButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.multirole_rename_profile))
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.multirole_delete_profile))
            }
            TextButton(onClick = onOpenProviderSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.multirole_open_provider_settings))
            }
        }
    }
}

@Composable
private fun StorageFailureCard(
    state: MultiRoleUiState,
    onReset: () -> Unit,
    onOpenProviderSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.multirole_storage_corrupt_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.multirole_storage_corrupt_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.multirole_reset))
            }
            TextButton(onClick = onOpenProviderSettings) {
                Text(stringResource(R.string.multirole_open_provider_settings))
            }
        }
    }
}

// ==================== 对话框 ====================

private data class RoleEditorState(
    val profileId: ProfileId,
    val roleId: RoleId,
    val originalName: String,
    val name: String,
    val aliasesText: String,
    val voiceId: String?,
)

private data class VoiceTarget(
    val titleRes: Int,
    val providerId: String,
    val currentVoiceId: String?,
    val onSelected: (String) -> Unit,
)

@Composable
private fun CreateProfileDialog(
    state: MultiRoleUiState,
    onDismiss: () -> Unit,
    onLoadVoices: (String) -> Unit,
    onPickVoice: (VoiceTarget) -> Unit,
    onCreate: (String, String, String?, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var providerId by remember(state.defaultProviderId) { mutableStateOf(state.defaultProviderId) }
    var narratorVoice by remember(providerId) { mutableStateOf<String?>(null) }
    var defaultVoice by remember(providerId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val voiceMissingError = stringResource(R.string.multirole_issue_voice_missing)
    val nameEmptyError = stringResource(R.string.multirole_issue_profile_name_empty)

    LaunchedEffect(providerId) { onLoadVoices(providerId) }
    val voiceState = state.voices[providerId]

    fun validateVoice(): Boolean {
        if (narratorVoice.isNullOrBlank() || defaultVoice.isNullOrBlank()) {
            error = voiceMissingError
            return false
        }
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multirole_create_profile)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80); error = null },
                    label = { Text(stringResource(R.string.multirole_profile_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProviderSelectorField(
                    providerId = providerId,
                    onSelect = {
                        providerId = it
                        narratorVoice = null
                        defaultVoice = null
                    },
                )
                if (providerId == ProviderIds.Azure.providerId) {
                    Text(
                        text = stringResource(R.string.multirole_provider_unsupported_azure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                VoiceSelectionButton(
                    label = stringResource(R.string.multirole_narrator_voice),
                    voiceId = narratorVoice,
                    voiceState = voiceState,
                    onClick = {
                        onPickVoice(
                            VoiceTarget(
                                titleRes = R.string.multirole_narrator_voice,
                                providerId = providerId,
                                currentVoiceId = narratorVoice,
                                onSelected = { narratorVoice = it },
                            )
                        )
                    },
                )
                VoiceSelectionButton(
                    label = stringResource(R.string.multirole_default_role_voice),
                    voiceId = defaultVoice,
                    voiceState = voiceState,
                    onClick = {
                        onPickVoice(
                            VoiceTarget(
                                titleRes = R.string.multirole_default_role_voice,
                                providerId = providerId,
                                currentVoiceId = defaultVoice,
                                onSelected = { defaultVoice = it },
                            )
                        )
                    },
                )
                Text(
                    text = stringResource(R.string.multirole_delimiter_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.multirole_create_delimiter_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        error = nameEmptyError
                    } else if (validateVoice()) {
                        onCreate(name.trim(), providerId, narratorVoice, defaultVoice)
                    }
                }
            ) { Text(stringResource(R.string.multirole_dialog_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.multirole_dialog_cancel))
            }
        },
    )
}

@Composable
private fun SwitchProfileDialog(
    state: MultiRoleUiState,
    onDismiss: () -> Unit,
    onSelect: (ProfileId) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multirole_select_profile_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.settings.enabled) {
                    Text(
                        text = stringResource(R.string.multirole_select_profile_disabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.settings.profiles.forEach { profile ->
                    val selected = profile.id == state.settings.activeProfileId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(profile.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { onSelect(profile.id) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(
                                    R.string.multirole_profile_meta,
                                    providerName(profile.providerId),
                                    profile.roles.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (selected) {
                            Text(
                                text = stringResource(R.string.multirole_current_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.multirole_close))
            }
        },
    )
}

@Composable
private fun RenameProfileDialog(
    profile: ReadingProfile,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multirole_rename_profile)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text(stringResource(R.string.multirole_profile_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) {
                Text(stringResource(R.string.multirole_dialog_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.multirole_dialog_cancel))
            }
        },
    )
}

@Composable
private fun CopyProfileDialog(
    profile: ReadingProfile,
    state: MultiRoleUiState,
    onDismiss: () -> Unit,
    onLoadVoices: (String) -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    val defaultName = stringResource(R.string.multirole_copy_default_name, profile.name)
    var name by remember(profile.id) { mutableStateOf(defaultName) }
    var providerId by remember(profile.id) { mutableStateOf(profile.providerId) }
    LaunchedEffect(providerId) { onLoadVoices(providerId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multirole_copy_profile)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text(stringResource(R.string.multirole_profile_name_label)) },
                    singleLine = true,
                )
                ProviderSelectorField(
                    providerId = providerId,
                    onSelect = { providerId = it },
                )
                if (providerId == ProviderIds.Azure.providerId) {
                    Text(
                        text = stringResource(R.string.multirole_provider_unsupported_azure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (providerId != profile.providerId) {
                    Text(
                        text = stringResource(R.string.multirole_copy_provider_changed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name, providerId) }) {
                Text(stringResource(R.string.multirole_dialog_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.multirole_dialog_cancel))
            }
        },
    )
}

@Composable
private fun DeleteProfileDialog(
    profile: ReadingProfile,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multirole_delete_profile_title, profile.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.multirole_delete_profile_message))
                if (isActive) {
                    Text(
                        text = stringResource(R.string.multirole_delete_active_profile_note),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.multirole_dialog_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.multirole_dialog_cancel))
            }
        },
    )
}

@Composable
private fun RoleEditorDialog(
    state: MultiRoleUiState,
    editor: RoleEditorState,
    onDismiss: () -> Unit,
    onUpdateVoice: (String) -> Unit,
    onPickVoice: (VoiceTarget) -> Unit,
    onSave: (Role) -> Unit,
) {
    val context = LocalContext.current
    var name by remember(editor.roleId) { mutableStateOf(editor.name) }
    var aliasesText by remember(editor.roleId) { mutableStateOf(editor.aliasesText) }
    var voiceId by remember(editor.roleId) { mutableStateOf(editor.voiceId) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editor.voiceId) { voiceId = editor.voiceId }
    val genericError = stringResource(R.string.multirole_issue_generic)
    val profile = state.settings.profiles.firstOrNull { it.id == editor.profileId }
    val voiceState = state.voices[profile?.providerId]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editor.originalName.isBlank()) R.string.multirole_add_role_title
                    else R.string.multirole_edit_role_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80); error = null },
                    label = { Text(stringResource(R.string.multirole_role_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aliasesText,
                    onValueChange = { aliasesText = it; error = null },
                    label = { Text(stringResource(R.string.multirole_role_aliases_label)) },
                    supportingText = { Text(stringResource(R.string.multirole_role_aliases_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                VoiceSelectionButton(
                    label = stringResource(R.string.multirole_role_voice_label),
                    voiceId = voiceId,
                    voiceState = voiceState,
                    onClick = {
                        onPickVoice(
                            VoiceTarget(
                                titleRes = R.string.multirole_role_voice_label,
                                providerId = profile?.providerId.orEmpty(),
                                currentVoiceId = voiceId,
                                onSelected = { onUpdateVoice(it) },
                            )
                        )
                    },
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val aliases = aliasesText.split('\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    val role = Role(
                        id = editor.roleId,
                        name = name.trim(),
                        aliases = aliases,
                        voice = voiceId?.takeIf { it.isNotBlank() }?.let { VoiceRef(it) },
                    )
                    if (profile == null) {
                        error = genericError
                        return@TextButton
                    }
                    val exists = profile.roles.any { it.id == role.id }
                    val candidate = profile.copy(
                        roles = if (exists) {
                            profile.roles.map { if (it.id == role.id) role else it }
                        } else {
                            profile.roles + role
                        }
                    )
                    val activeAndEnabled = state.settings.enabled &&
                        state.settings.activeProfileId == profile.id
                    val issues = MultiRoleValidator.validateProfile(
                        candidate, state.settings.profiles
                    ).filter {
                        // 非生效方案允许保存缺少音色的草稿；启用/生效方案必须完整
                        activeAndEnabled || it.code != MultiRoleIssueCode.VOICE_MISSING
                    }
                    if (issues.isNotEmpty()) {
                        error = issueText(context, issues.first())
                    } else {
                        onSave(role)
                    }
                }
            ) { Text(stringResource(R.string.multirole_dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.multirole_dialog_cancel))
            }
        },
    )
}

@Composable
private fun VoiceSelectionButton(
    label: String,
    voiceId: String?,
    voiceState: VoiceLoadState?,
    onClick: () -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = voiceState?.loading != true,
        ) {
            Text(
                text = voiceId?.let { voiceDisplayName(voiceState, it) }
                    ?: if (voiceState?.loading == true) {
                        stringResource(R.string.multirole_voice_loading)
                    } else {
                        stringResource(R.string.multirole_select_voice)
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
    }
}

@Composable
private fun ProviderSelectorField(
    providerId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = stringResource(R.string.multirole_provider_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = providerName(providerId),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                TtsProviderRegistry.availableProviders.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.name) },
                        trailingIcon = {
                            if (provider.id == providerId) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(provider.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoicePickerDialog(
    target: VoiceTarget,
    state: MultiRoleUiState,
    onDismiss: () -> Unit,
    onLoadVoices: (String) -> Unit,
    onSelect: (String) -> Unit,
    onTogglePreview: (String, String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    LaunchedEffect(target.providerId) { onLoadVoices(target.providerId) }
    val voiceState = state.voices[target.providerId]
    val filtered = remember(voiceState?.voices, search) {
        val all = voiceState?.voices.orEmpty()
        if (search.isBlank()) all else all.filter {
            it.displayName.contains(search, ignoreCase = true) ||
                it.voiceId.contains(search, ignoreCase = true)
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(target.titleRes),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = providerName(target.providerId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.multirole_voice_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    voiceState?.loading == true -> {
                        Text(stringResource(R.string.multirole_voice_loading))
                    }
                    voiceState?.failed == true -> {
                        Text(
                            text = stringResource(R.string.multirole_voice_failed),
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = { onLoadVoices(target.providerId) }) {
                            Text(stringResource(R.string.multirole_retry))
                        }
                    }
                    filtered.isEmpty() -> {
                        Text(stringResource(R.string.multirole_voice_empty))
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                            items(filtered, key = { it.voiceId }) { voice ->
                                VoicePickerRow(
                                    voice = voice,
                                    selected = voice.voiceId == target.currentVoiceId,
                                    previewPlaying = state.voicePreviewPlayingId == voice.voiceId,
                                    onClick = { onSelect(voice.voiceId) },
                                    onPreview = {
                                        onTogglePreview(target.providerId, voice.voiceId)
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.multirole_voice_format_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.multirole_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePickerRow(
    voice: VoiceInfo,
    selected: Boolean,
    previewPlaying: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = voice.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            voice.group?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onPreview) {
            Icon(
                if (previewPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (previewPlaying) R.string.multirole_voice_preview_stop
                    else R.string.multirole_voice_preview
                )
            )
        }
    }
}

@Composable
private fun DelimiterEditorDialog(
    profile: ReadingProfile,
    onDismiss: () -> Unit,
    onSave: (List<DelimiterPair>) -> Unit,
) {
    val context = LocalContext.current
    var pairs by remember(profile.id) {
        mutableStateOf(
            (profile.delimiters + MultiRoleDelimiterPresets.ALL.map {
                DelimiterPair(it.open, it.close, enabled = false)
            }).distinctBy { it.open to it.close }
        )
    }
    var customOpen by remember { mutableStateOf("") }
    var customClose by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidDelimiterError = stringResource(R.string.multirole_issue_delimiter_invalid)
    val duplicateDelimiterError = stringResource(R.string.multirole_issue_delimiter_duplicate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multirole_delimiter_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.multirole_delimiter_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.multirole_delimiter_system_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.multirole_delimiter_presets),
                    style = MaterialTheme.typography.titleSmall
                )
                pairs.forEachIndexed { index, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = pair.enabled,
                            onCheckedChange = { enabled ->
                                pairs = pairs.toMutableList().also {
                                    it[index] = pair.copy(enabled = enabled)
                                }
                                error = null
                            }
                        )
                        val preset = MultiRoleDelimiterPresets.matches(pair)
                        Text(
                            text = preset?.label ?: delimiterLabel(pair),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (preset == null) {
                            TextButton(onClick = {
                                pairs = pairs.toMutableList().also { it.removeAt(index) }
                                error = null
                            }) {
                                Text(stringResource(R.string.multirole_delimiter_remove))
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.multirole_delimiter_custom),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customOpen,
                        onValueChange = { customOpen = it.take(4); error = null },
                        label = { Text(stringResource(R.string.multirole_delimiter_custom_open)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = customClose,
                        onValueChange = { customClose = it.take(4); error = null },
                        label = { Text(stringResource(R.string.multirole_delimiter_custom_close)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.multirole_delimiter_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    val open = singleCodePoint(customOpen)
                    val close = singleCodePoint(customClose)
                    if (open == null || close == null ||
                        !MultiRoleValidator.isValidDelimiterCodePoint(open) ||
                        !MultiRoleValidator.isValidDelimiterCodePoint(close)
                    ) {
                        error = invalidDelimiterError
                    } else if (pairs.any { it.open == open && it.close == close }) {
                        error = duplicateDelimiterError
                    } else {
                        pairs = pairs + DelimiterPair(open, close, enabled = true)
                        customOpen = ""
                        customClose = ""
                        error = null
                    }
                }) {
                    Text(stringResource(R.string.multirole_delimiter_add_custom))
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val issues = MultiRoleValidator.validateDelimiters(pairs, profile.id)
                if (issues.isNotEmpty()) {
                    error = issueText(context, issues.first())
                } else {
                    onSave(pairs)
                }
            }) { Text(stringResource(R.string.multirole_dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.multirole_dialog_cancel))
            }
        },
    )
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multirole_help_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                MarkdownText(content = stringResource(R.string.multirole_help_content))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.multirole_help_session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.multirole_close))
            }
        },
    )
}

@Composable
private fun IssueDialog(
    issues: List<MultiRoleIssue>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multirole_repair_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                issues.distinctBy { it.code to it.field }.take(10).forEach { issue ->
                    Text(
                        text = "• " + issueText(context, issue),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.multirole_close))
            }
        },
    )
}

// ==================== 文案/展示辅助 ====================

private fun providerName(providerId: String): String =
    TtsProviderRegistry.getProvider(providerId)?.name ?: providerId

private fun voiceDisplayName(load: VoiceLoadState?, voiceId: String): String {
    return load?.voices?.firstOrNull { it.voiceId == voiceId }?.displayName ?: voiceId
}

private fun delimiterLabel(pair: DelimiterPair): String {
    val open = runCatching { String(Character.toChars(pair.open)) }.getOrDefault("?")
    val close = runCatching { String(Character.toChars(pair.close)) }.getOrDefault("?")
    return "$open$close"
}

private fun delimiterSummary(delimiters: List<DelimiterPair>): String {
    val enabled = delimiters.filter { it.enabled }
    if (enabled.isEmpty()) return ""
    return enabled.joinToString("  ") { delimiterLabel(it) }
}

private fun singleCodePoint(text: String): Int? {
    if (text.isEmpty()) return null
    val first = text.codePointAt(0)
    val width = Character.charCount(first)
    if (width != text.length) return null
    return first
}

private fun issueText(context: Context, issue: MultiRoleIssue): String {
    val resId = when (issue.code) {
        MultiRoleIssueCode.PROFILE_COUNT_LIMIT -> R.string.multirole_issue_profile_count
        MultiRoleIssueCode.PROFILE_NAME_EMPTY -> R.string.multirole_issue_profile_name_empty
        MultiRoleIssueCode.PROFILE_NAME_DUPLICATE -> R.string.multirole_issue_profile_name_duplicate
        MultiRoleIssueCode.PROFILE_NAME_TOO_LONG -> R.string.multirole_issue_profile_name_too_long
        MultiRoleIssueCode.ACTIVE_PROFILE_MISSING -> R.string.multirole_issue_active_profile_missing
        MultiRoleIssueCode.PROFILE_NOT_FOUND -> R.string.multirole_issue_profile_not_found
        MultiRoleIssueCode.ROLE_COUNT_LIMIT -> R.string.multirole_issue_role_count
        MultiRoleIssueCode.ROLE_NAME_EMPTY -> R.string.multirole_issue_role_name_empty
        MultiRoleIssueCode.ROLE_NAME_TOO_LONG -> R.string.multirole_issue_role_name_too_long
        MultiRoleIssueCode.ROLE_ALIAS_COUNT_LIMIT -> R.string.multirole_issue_alias_count
        MultiRoleIssueCode.ROLE_ALIAS_EMPTY -> R.string.multirole_issue_alias_empty
        MultiRoleIssueCode.ROLE_ALIAS_TOO_LONG -> R.string.multirole_issue_alias_too_long
        MultiRoleIssueCode.NAME_DUPLICATE -> R.string.multirole_issue_name_duplicate
        MultiRoleIssueCode.NAME_RESERVED -> R.string.multirole_issue_name_reserved
        MultiRoleIssueCode.VOICE_MISSING -> R.string.multirole_issue_voice_missing
        MultiRoleIssueCode.DELIMITER_NONE_ENABLED -> R.string.multirole_issue_delimiter_none
        MultiRoleIssueCode.DELIMITER_TOO_MANY -> R.string.multirole_issue_delimiter_too_many
        MultiRoleIssueCode.DELIMITER_INVALID_CODE_POINT -> R.string.multirole_issue_delimiter_invalid
        MultiRoleIssueCode.DELIMITER_DUPLICATE -> R.string.multirole_issue_delimiter_duplicate
        MultiRoleIssueCode.DELIMITER_CONFLICT -> R.string.multirole_issue_delimiter_conflict
        MultiRoleIssueCode.PROVIDER_UNKNOWN -> R.string.multirole_issue_provider_unknown
        MultiRoleIssueCode.PROVIDER_NOT_CONFIGURED -> R.string.multirole_issue_provider_not_configured
        MultiRoleIssueCode.VOICE_UNAVAILABLE -> R.string.multirole_issue_voice_unavailable
        MultiRoleIssueCode.OUTPUT_FORMAT_UNKNOWN -> R.string.multirole_issue_output_format_unknown
        MultiRoleIssueCode.OUTPUT_FORMAT_MISMATCH -> R.string.multirole_issue_output_format_mismatch
        MultiRoleIssueCode.MODEL_NOT_READY -> R.string.multirole_issue_model_not_ready
        MultiRoleIssueCode.ENABLED_REQUIRES_VALID_PROFILE -> R.string.multirole_issue_enabled_requires_valid
    }
    return context.getString(resId)
}
