package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(isOpen) {
        if (!isOpen && sheetState.isVisible) {
            sheetState.hide()
        }
    }

    LaunchedEffect(Unit) {
        sheetState.show()
    }

    val savedConfig = remember(currentEngine, isOpen) {
        configRepository.getConfig(currentEngine)
    }

    var configItems by remember(currentEngine, savedConfig, isOpen) {
        mutableStateOf(
            listOf(
                ConfigItem(
                    key = "api_key",
                    label = "API Key",
                    value = savedConfig.apiKey,
                    isPassword = true
                ),
                ConfigItem(
                    key = "voice_id",
                    label = "声音选择",
                    value = savedConfig.voiceId,
                    isVoiceSelector = true
                )
            )
        )
    }

    var availableVoices by remember(currentEngine, isOpen) {
        mutableStateOf<List<VoiceInfo>>(emptyList())
    }

    LaunchedEffect(currentEngine, isOpen) {
        availableVoices = voiceRepository.getVoicesForEngine(currentEngine)
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
                            text = "设置",
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                HorizontalDivider()

                Spacer(modifier = Modifier.height(16.dp))

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
                        configRepository.saveConfig(currentEngine, newConfig)
                        isConfigModified = false
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
