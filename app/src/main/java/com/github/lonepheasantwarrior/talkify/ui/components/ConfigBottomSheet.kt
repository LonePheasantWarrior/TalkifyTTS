package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.ConfigItem
import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository

/**
 * 配置底部弹窗
 *
 * 展示引擎配置编辑界面，包含 API Key 输入和声音选择
 * 通过右下角悬浮按钮唤出
 *
 * @param isOpen 是否展开弹窗
 * @param onDismiss 关闭弹窗的回调
 * @param currentEngine 当前选中的引擎
 * @param configRepository 配置仓储
 * @param voiceRepository 声音仓储
 * @param modifier 修饰符
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBottomSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    currentEngine: TtsEngine,
    configRepository: EngineConfigRepository,
    voiceRepository: VoiceRepository,
    onConfigSaved: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    LaunchedEffect(isOpen) {
        if (!isOpen && sheetState.isVisible) {
            sheetState.hide()
        }
    }

    val savedConfig = remember(currentEngine, isOpen) {
        configRepository.getConfig(currentEngine.id)
    }

    val apiKeyLabel = stringResource(R.string.api_key_label)
    val voiceLabel = stringResource(R.string.voice_select_label)

    var configItems by remember(currentEngine, savedConfig, isOpen, apiKeyLabel, voiceLabel) {
        mutableStateOf(
            listOf(
                ConfigItem(
                    key = "api_key",
                    label = apiKeyLabel,
                    value = savedConfig.apiKey,
                    isPassword = true
                ),
                ConfigItem(
                    key = "voice_id",
                    label = voiceLabel,
                    value = savedConfig.voiceId,
                    isVoiceSelector = true
                )
            )
        )
    }

    var availableVoices by remember(currentEngine, isOpen) {
        mutableStateOf<List<VoiceInfo>>(emptyList())
    }
    var isVoicesLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentEngine, isOpen) {
        isVoicesLoading = true
        try {
            availableVoices = voiceRepository.getVoicesForEngine(currentEngine)
        } finally {
            isVoicesLoading = false
        }
    }

    var isConfigModified by remember { mutableStateOf(false) }

    if (isOpen) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.settings),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                HorizontalDivider()

                Spacer(modifier = Modifier.height(16.dp))

                if (isVoicesLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.voice_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    ConfigEditor(
                    engineName = currentEngine.name,
                    configItems = configItems,
                    availableVoices = availableVoices,
                    onItemValueChange = { changedItem, newValue ->
                        configItems = configItems.map {
                            if (it.key == changedItem.key) it.copy(value = newValue) else it
                        }
                        isConfigModified = true
                    },
                    onSaveClick = {
                        val newConfig = EngineConfig(
                            apiKey = configItems.find { it.key == "api_key" }?.value ?: "",
                            voiceId = configItems.find { it.key == "voice_id" }?.value ?: ""
                        )
                        configRepository.saveConfig(currentEngine.id, newConfig)
                        isConfigModified = false
                        onConfigSaved?.invoke()
                        onDismiss()
                    },
                    onVoiceSelected = { voice ->
                        val voiceItem = configItems.find { it.key == "voice_id" }
                        if (voiceItem != null) {
                            configItems = configItems.map {
                                if (it.key == "voice_id") it.copy(value = voice.voiceId) else it
                            }
                            isConfigModified = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                }
            }
        }
    }
}
