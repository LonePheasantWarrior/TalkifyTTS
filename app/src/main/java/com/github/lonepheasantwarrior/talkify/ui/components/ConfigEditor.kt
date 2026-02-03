package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.ConfigItem
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo

/**
 * 配置编辑器组件
 *
 * 展示并编辑引擎配置，包括 API Key 输入和声音选择
 *
 * @param engineName 引擎名称
 * @param configItems 配置项列表
 * @param availableVoices 可选声音列表
 * @param onItemValueChange 配置项值变化的回调
 * @param onSaveClick 保存按钮点击的回调
 * @param onVoiceSelected 声音选择的回调
 * @param modifier 修饰符
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditor(
    engineName: String,
    configItems: List<ConfigItem>,
    availableVoices: List<VoiceInfo>,
    onItemValueChange: (ConfigItem, String) -> Unit,
    onSaveClick: () -> Unit,
    onVoiceSelected: (VoiceInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var localConfigItems by remember(configItems) { mutableStateOf(configItems) }
    var isModified by remember { mutableStateOf(false) }

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
                text = stringResource(R.string.config_title_format, engineName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            localConfigItems.forEach { item ->
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
    } else {
        OutlinedTextField(
            value = item.value,
            onValueChange = onValueChange,
            label = { Text(item.label) },
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
