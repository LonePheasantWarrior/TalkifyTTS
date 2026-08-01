package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.ConfigItem
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo

/**
 * 配置编辑器组件
 *
 * 展示并编辑供应商配置，包括 API Key 输入和声音选择
 *
 * @param providerName 供应商名称
 * @param configItems 配置项列表
 * @param availableVoices 可选声音列表
 * @param onItemValueChange 配置项值变化的回调
 * @param onSaveClick 保存按钮点击的回调
 * @param onVoiceSelected 声音选择的回调
 * @param modifier 修饰符
 * @param advancedItemKeys 应放入"高级设置"折叠面板的配置项 key 集合
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditor(
    providerName: String,
    configItems: List<ConfigItem>,
    availableVoices: List<VoiceInfo>,
    onItemValueChange: (ConfigItem, String) -> Unit,
    onSaveClick: () -> Unit,
    onVoiceSelected: (VoiceInfo) -> Unit,
    modifier: Modifier = Modifier,
    advancedItemKeys: Set<String> = setOf("api_url", "model_id")
) {
    var localConfigItems by remember(configItems) { mutableStateOf(configItems) }
    var isModified by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    val regularItems = localConfigItems.filter { it.key !in advancedItemKeys }
    val advancedItems = localConfigItems.filter { it.key in advancedItemKeys }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.config_title_format, providerName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 基础配置项
            regularItems.forEach { item ->
                ConfigItemEditor(
                    item = item,
                    availableVoices = availableVoices,
                    onValueChange = { newValue ->
                        localConfigItems = localConfigItems.map {
                            if (it.key == item.key) it.copy(value = newValue) else it
                        }
                        isModified = true
                        onItemValueChange(item, newValue)
                    },
                    onVoiceSelected = onVoiceSelected
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 高级设置折叠面板
            if (advancedItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))

                AdvancedSettingsSection(
                    expanded = advancedExpanded,
                    onToggle = { advancedExpanded = !advancedExpanded },
                    advancedItems = advancedItems,
                    availableVoices = availableVoices,
                    onValueChange = { item, newValue ->
                        localConfigItems = localConfigItems.map {
                            if (it.key == item.key) it.copy(value = newValue) else it
                        }
                        isModified = true
                        onItemValueChange(item, newValue)
                    },
                    onVoiceSelected = onVoiceSelected
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onSaveClick,
                    enabled = isModified
                ) {
                    Text(stringResource(R.string.save_config))
                }
            }
        }
    }
}

/**
 * 高级设置折叠面板
 *
 * 遵循 Material 3 Expressive 设计规范，将 API 地址、模型 ID 等非核心配置项
 * 折叠为一个可展开的"高级设置"区域，保持配置界面的清爽整洁。
 */
@Composable
private fun AdvancedSettingsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    advancedItems: List<ConfigItem>,
    availableVoices: List<VoiceInfo>,
    onValueChange: (ConfigItem, String) -> Unit,
    onVoiceSelected: (VoiceInfo) -> Unit
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "chevron_rotation"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 0.dp
    ) {
        Column {
            // 折叠标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.advanced_settings),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded)
                        stringResource(R.string.collapse)
                    else
                        stringResource(R.string.expand),
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 高级设置内容（带动画展开/折叠）
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(300)) +
                        fadeIn(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(250)) +
                        fadeOut(animationSpec = tween(200))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    // 顶部分割线
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(0.5.dp)
                    ) {}

                    Spacer(modifier = Modifier.height(16.dp))

                    advancedItems.forEach { item ->
                        ConfigItemEditor(
                            item = item,
                            availableVoices = availableVoices,
                            onValueChange = { newValue ->
                                onValueChange(item, newValue)
                            },
                            onVoiceSelected = onVoiceSelected
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigItemEditor(
    item: ConfigItem,
    availableVoices: List<VoiceInfo>,
    onValueChange: (String) -> Unit,
    onVoiceSelected: (VoiceInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    if (item.isVoiceSelector && availableVoices.isNotEmpty()) {
        var expanded by remember { mutableStateOf(false) }
        val selectedVoice = availableVoices.find { it.voiceId == item.value }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier
        ) {
            OutlinedTextField(
                value = selectedVoice?.displayName ?: stringResource(R.string.voice_select_placeholder),
                onValueChange = {},
                readOnly = true,
                label = { Text(item.label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                availableVoices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice.displayName) },
                        onClick = {
                            onValueChange(voice.voiceId)
                            onVoiceSelected(voice)
                            expanded = false
                        }
                    )
                }
            }
        }
    } else if (item.dropdownOptions != null) {
        var expanded by remember { mutableStateOf(false) }
        val selectedOption = item.dropdownOptions.find { it.first == item.value }
        val displayText = selectedOption?.second ?: item.value

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier
        ) {
            OutlinedTextField(
                value = displayText,
                onValueChange = {},
                readOnly = true,
                label = { Text(item.label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                item.dropdownOptions.forEach { (optionValue, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onValueChange(optionValue)
                            expanded = false
                        }
                    )
                }
            }
        }
    } else if (item.isDialogEditor) {
        DialogEditorField(
            item = item,
            onValueChange = onValueChange
        )
    } else {
        OutlinedTextField(
            value = item.value,
            onValueChange = onValueChange,
            label = { Text(item.label) },
            placeholder = item.placeholder?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !item.isVoiceSelector,
            visualTransformation = if (item.isPassword) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            }
        )
    }
}

/**
 * 弹窗式多行文本编辑器
 *
 * 用于需要输入大段自然语言描述的配置项（如小米风格指令）。
 * 展示一个可点击的摘要卡片，点击后弹出遵循 Material 3 规范的对话框，
 * 通过多行文本输入框编辑完整内容，并支持辅助说明文字。
 */
@Composable
private fun DialogEditorField(
    item: ConfigItem,
    onValueChange: (String) -> Unit
) {
    var showEditorDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var draftValue by remember(item.value) { mutableStateOf(item.value) }

    // 摘要卡片：模拟输入框外观，点击唤起编辑对话框
    OutlinedCard(
        onClick = {
            draftValue = item.value
            showEditorDialog = true
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (item.value.isNotBlank()) {
                    Text(
                        text = item.value,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = item.placeholder ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 编辑对话框：多行文本输入，支持大段文字
    if (showEditorDialog) {
        AlertDialog(
            onDismissRequest = { showEditorDialog = false },
            title = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.editorTitle ?: item.label,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.guideContent != null) {
                            IconButton(onClick = { showGuideDialog = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                    contentDescription = stringResource(R.string.usage_guide_help_icon),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // 辅助说明文字紧贴标题下方展示
                    item.supportingText?.let { supportingText ->
                        Text(
                            text = supportingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = draftValue,
                        onValueChange = { draftValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = item.placeholder?.let { { Text(it) } },
                        minLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange(draftValue)
                        showEditorDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.confirm),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditorDialog = false }
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }

    // 使用指南对话框
    if (showGuideDialog) {
        UsageGuideDialog(
            content = item.guideContent.orEmpty(),
            onDismiss = { showGuideDialog = false }
        )
    }
}

/**
 * 使用指南对话框
 *
 * 展示配置项的使用指南（Markdown 格式内容），支持长内容滚动浏览。
 * 遵循 Material 3 对话框规范：标题使用 HeadlineSmall，正文左对齐。
 */
@Composable
private fun UsageGuideDialog(
    content: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.usage_guide_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                MarkdownText(
                    content = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.usage_guide_close),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}
