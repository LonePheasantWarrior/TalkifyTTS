package com.github.lonepheasantwarrior.talkify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.DialogueModeConfig
import com.github.lonepheasantwarrior.talkify.domain.model.RoleRule
import com.github.lonepheasantwarrior.talkify.domain.model.VoiceBinding
import com.github.lonepheasantwarrior.talkify.ui.components.VoiceBindingPickerDialog
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.DialogueModeViewModel

/**
 * 对话模式设置页
 *
 * - 总开关：关闭时 TTS 服务走原有单一发音人路径
 * - 旁白 / 对话发音人：各自选择「供应商 + 音色 + 可选风格指令」
 * - 可选项：是否朗读引号本身、段间停顿
 * - 角色规则：人物名 / 正则 → 指定发音人（优先级：<<人物>> 标记 > 规则 > 默认对话）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogueModeScreen(
    onBackClick: () -> Unit
) {
    val viewModel: DialogueModeViewModel = viewModel()
    val config by viewModel.config.collectAsStateWithLifecycle()

    // 发音人绑定选择器状态：null 关闭，非 null 表示正在编辑哪个绑定
    var pickingTarget by remember { mutableStateOf<PickTarget?>(null) }
    // 角色规则编辑器状态：null 关闭
    var editingRule by remember { mutableStateOf<RoleRule?>(null) }
    var editingRuleNewBinding by remember { mutableStateOf<VoiceBinding?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dialogue_mode_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 总开关
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dialogue_mode_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (config.enabled) {
                                    stringResource(R.string.dialogue_mode_enabled_hint)
                                } else {
                                    stringResource(R.string.dialogue_mode_disabled_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { viewModel.setEnabled(it) }
                        )
                    }
                    if (config.enabled && !config.isReady) {
                        Text(
                            text = stringResource(R.string.dialogue_mode_not_ready),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        )
                    }
                }
            }

            // 旁白发音人
            item {
                BindingCard(
                    title = stringResource(R.string.narrator_voice_label),
                    binding = config.narrator,
                    onClick = { pickingTarget = PickTarget.NARRATOR }
                )
            }

            // 对话发音人
            item {
                BindingCard(
                    title = stringResource(R.string.dialogue_voice_label),
                    binding = config.dialogue,
                    onClick = { pickingTarget = PickTarget.DIALOGUE }
                )
            }

            // 朗读引号本身
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.read_quotes_label),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.read_quotes_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.readQuotes,
                            onCheckedChange = { viewModel.setReadQuotes(it) }
                        )
                    }
                }
            }

            // 段间停顿
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.segment_pause_label),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.segment_pause_value_format, config.pauseMs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = config.pauseMs.toFloat(),
                            onValueChange = { viewModel.setPauseMs(it.toInt()) },
                            valueRange = 0f..DialogueModeConfig.MAX_PAUSE_MS.toFloat(),
                            steps = (DialogueModeConfig.MAX_PAUSE_MS / 20) - 1
                        )
                    }
                }
            }

            // 角色规则
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.role_rules_label),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.role_rules_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (config.rules.isEmpty()) {
                            Text(
                                text = stringResource(R.string.role_rule_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            config.rules.forEach { rule ->
                                ListItem(
                                    headlineContent = { Text(rule.name.ifBlank { rule.pattern }) },
                                    supportingContent = {
                                        Text(
                                            "${if (rule.isRegex) "正则" else "包含"} \"${rule.pattern}\" → " +
                                                    bindingSummary(rule.binding)
                                        )
                                    },
                                    trailingContent = {
                                        Row {
                                            IconButton(onClick = {
                                                editingRule = rule
                                                editingRuleNewBinding = rule.binding
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Filled.Edit,
                                                    contentDescription = stringResource(R.string.role_rule_edit)
                                                )
                                            }
                                            IconButton(onClick = { viewModel.deleteRule(rule.id) }) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = stringResource(R.string.role_rule_delete)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        FilledTonalButton(
                            onClick = {
                                editingRule = RoleRule(
                                    id = System.currentTimeMillis(),
                                    name = "",
                                    pattern = ""
                                )
                                editingRuleNewBinding = null
                            }
                        ) {
                            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.role_rule_add))
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // 发音人绑定选择器
    val target = pickingTarget
    if (target != null) {
        VoiceBindingPickerDialog(
            initial = when (target) {
                PickTarget.NARRATOR -> config.narrator
                PickTarget.DIALOGUE -> config.dialogue
            },
            viewModel = viewModel,
            onConfirm = { binding ->
                when (target) {
                    PickTarget.NARRATOR -> viewModel.setNarrator(binding)
                    PickTarget.DIALOGUE -> viewModel.setDialogue(binding)
                }
                pickingTarget = null
            },
            onDismiss = { pickingTarget = null }
        )
    }

    // 角色规则编辑器
    val rule = editingRule
    if (rule != null) {
        RoleRuleEditorDialog(
            initial = rule,
            initialBinding = editingRuleNewBinding ?: rule.binding,
            viewModel = viewModel,
            onConfirm = { name, pattern, isRegex, binding ->
                viewModel.upsertRule(
                    rule.copy(
                        name = name,
                        pattern = pattern,
                        isRegex = isRegex,
                        binding = binding
                    )
                )
                editingRule = null
                editingRuleNewBinding = null
            },
            onDismiss = {
                editingRule = null
                editingRuleNewBinding = null
            }
        )
    }
}

/** 正在编辑哪个发音人绑定 */
private enum class PickTarget { NARRATOR, DIALOGUE }

/** 绑定摘要文案：未配置 / 供应商 · 音色名 */
private fun bindingSummary(binding: VoiceBinding): String {
    return if (binding.isConfigured) {
        "${providerNameOf(binding.providerId)} · ${binding.displayName.ifBlank { binding.voiceId }}"
    } else {
        "未配置"
    }
}

private fun providerNameOf(providerId: String): String {
    return com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
        .getProvider(providerId)?.name ?: providerId
}

@Composable
private fun BindingCard(
    title: String,
    binding: VoiceBinding,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (binding.isConfigured) {
                Text(
                    text = stringResource(
                        R.string.voice_binding_format,
                        providerNameOf(binding.providerId),
                        binding.displayName.ifBlank { binding.voiceId }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (binding.styleInstruction.isNotBlank()) {
                    Text(
                        text = binding.styleInstruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.voice_binding_unconfigured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 角色规则编辑器：名称 + 人物名/正则 + 正则开关 + 发音人绑定
 */
@Composable
private fun RoleRuleEditorDialog(
    initial: RoleRule,
    initialBinding: VoiceBinding,
    viewModel: DialogueModeViewModel,
    onConfirm: (name: String, pattern: String, isRegex: Boolean, binding: VoiceBinding) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var isRegex by remember { mutableStateOf(initial.isRegex) }
    var binding by remember { mutableStateOf(initialBinding) }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        VoiceBindingPickerDialog(
            initial = binding,
            viewModel = viewModel,
            onConfirm = { selected ->
                binding = selected
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.role_rule_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.role_rule_name_label)) },
                    placeholder = { Text(stringResource(R.string.role_rule_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.role_rule_pattern_label)) },
                    placeholder = { Text(stringResource(R.string.role_rule_pattern_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.role_rule_regex_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = isRegex, onCheckedChange = { isRegex = it })
                }
                Text(
                    text = stringResource(R.string.role_rule_binding_label),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = bindingSummary(binding),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (binding.isConfigured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPicker = true }
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Start
                )
                if (pattern.isBlank() || !binding.isConfigured) {
                    Text(
                        text = stringResource(R.string.role_rule_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (pattern.isBlank() || !binding.isConfigured) return@TextButton
                    onConfirm(name.trim().ifBlank { pattern.trim() }, pattern.trim(), isRegex, binding)
                },
                enabled = pattern.isNotBlank() && binding.isConfigured
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
