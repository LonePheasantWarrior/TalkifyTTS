package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProvider
import com.github.lonepheasantwarrior.talkify.domain.model.VoiceBinding
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.DialogueModeViewModel

/**
 * 发音人绑定选择器（对话模式）
 *
 * 两步式选择，与主界面供应商/音色选择的体验保持一致：
 * 1. 选择供应商（本地 ZipVoice、MiMo、Azure、通义、豆包、OpenAI 等一视同仁）
 * 2. 选择该供应商的音色；支持风格指令的供应商额外提供风格输入框
 *
 * @param initial 当前绑定（用于回显已选供应商/音色/风格）
 * @param viewModel 提供音色列表与「是否支持风格指令」
 * @param onConfirm 返回新的 [VoiceBinding]
 * @param onDismiss 取消
 */
@Composable
fun VoiceBindingPickerDialog(
    initial: VoiceBinding,
    viewModel: DialogueModeViewModel,
    onConfirm: (VoiceBinding) -> Unit,
    onDismiss: () -> Unit
) {
    val providers = remember { viewModel.availableProviders() }

    var selectedProvider by remember {
        mutableStateOf(providers.firstOrNull { it.id == initial.providerId })
    }
    var voices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var voicesLoading by remember { mutableStateOf(false) }
    var selectedVoice by remember {
        mutableStateOf<VoiceInfo?>(null)
    }
    var styleText by remember { mutableStateOf(initial.styleInstruction) }

    LaunchedEffect(selectedProvider) {
        val provider = selectedProvider ?: return@LaunchedEffect
        voicesLoading = true
        voices = viewModel.loadVoices(provider.id)
        selectedVoice = voices.firstOrNull {
            it.voiceId == initial.voiceId && provider.id == initial.providerId
        } ?: voices.firstOrNull()
        voicesLoading = false
    }

    val supportsStyle = remember(selectedProvider?.id) {
        selectedProvider?.let { viewModel.supportsStyleInstruction(it.id) } == true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.binding_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val provider = selectedProvider
                if (provider == null) {
                    // 第一步：选择供应商
                    Text(
                        text = stringResource(R.string.binding_picker_provider_step),
                        style = MaterialTheme.typography.titleSmall
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(providers, key = { it.id }) { candidate: TtsProvider ->
                            ListItem(
                                headlineContent = { Text(candidate.name) },
                                supportingContent = { Text(candidate.defaultModelId) },
                                trailingContent = {
                                    RadioButton(
                                        selected = false,
                                        onClick = { selectedProvider = candidate }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedProvider = candidate }
                            )
                        }
                    }
                } else {
                    // 第二步：选择音色 + 风格指令
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.binding_picker_voice_step),
                            style = MaterialTheme.typography.titleSmall
                        )
                        TextButton(onClick = { selectedProvider = null }) {
                            Text(stringResource(R.string.binding_picker_back))
                        }
                    }
                    Text(
                        text = stringResource(
                            R.string.voice_binding_format,
                            provider.name,
                            provider.defaultModelId
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when {
                        voicesLoading -> Text(stringResource(R.string.binding_picker_loading))
                        voices.isEmpty() -> Text(stringResource(R.string.binding_picker_no_voices))
                        else -> LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                        ) {
                            items(voices, key = { it.voiceId }) { voice ->
                                ListItem(
                                    headlineContent = { Text(voice.displayName) },
                                    supportingContent = { Text(voice.voiceId) },
                                    leadingContent = {
                                        RadioButton(
                                            selected = voice.voiceId == selectedVoice?.voiceId,
                                            onClick = { selectedVoice = voice }
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedVoice = voice }
                                )
                            }
                        }
                    }
                    if (supportsStyle) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider()
                        OutlinedTextField(
                            value = styleText,
                            onValueChange = { styleText = it },
                            label = { Text(stringResource(R.string.binding_picker_style_label)) },
                            placeholder = { Text(stringResource(R.string.dialogue_mode_style_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 3
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val provider = selectedProvider ?: return@TextButton
                    val voice = selectedVoice ?: return@TextButton
                    onConfirm(
                        VoiceBinding(
                            providerId = provider.id,
                            voiceId = voice.voiceId,
                            displayName = voice.displayName,
                            styleInstruction = styleText.trim()
                        )
                    )
                },
                enabled = selectedProvider != null && selectedVoice != null
            ) {
                Text(stringResource(R.string.binding_picker_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
