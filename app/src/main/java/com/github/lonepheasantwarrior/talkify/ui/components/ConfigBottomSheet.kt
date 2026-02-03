package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ConfigItem
import com.github.lonepheasantwarrior.talkify.domain.model.Qwen3TtsConfig
import com.github.lonepheasantwarrior.talkify.domain.model.SeedTts2Config
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineApi
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory

/**
 * 配置底部弹窗
 *
 * 展示引擎配置编辑界面，包含 API Key 输入和声音选择
 * 通过右下角悬浮按钮唤出
 *
 * 支持多引擎架构，每个引擎可以定义自己的配置项
 * 使用引擎的 [TtsEngineApi.createDefaultConfig] 方法动态创建正确的配置类型
 * 使用引擎的 [TtsEngineApi.getConfigLabel] 方法获取本地化的配置项标签
 *
 * @param isOpen 是否展开弹窗
 * @param onDismiss 关闭弹窗的回调
 * @param currentEngine 当前选中的引擎
 * @param configRepository 配置仓储
 * @param voiceRepository 声音仓储
 * @param onConfigSaved 配置保存后的回调
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

    val context = LocalContext.current

    LaunchedEffect(isOpen) {
        if (!isOpen && sheetState.isVisible) {
            sheetState.hide()
        }
    }

    val savedConfig = remember(currentEngine, isOpen) {
        configRepository.getConfig(currentEngine.id)
    }

    val engine = remember(currentEngine.id) {
        TtsEngineFactory.createEngine(currentEngine.id)
    }

    val defaultConfig = remember(currentEngine.id) {
        engine?.createDefaultConfig() ?: throw IllegalStateException("Engine not found: ${currentEngine.id}")
    }

    val configForEdit: BaseEngineConfig = remember(savedConfig, defaultConfig) {
        when (defaultConfig) {
            is Qwen3TtsConfig -> {
                val qwenSaved = savedConfig as? Qwen3TtsConfig
                if (qwenSaved != null) qwenSaved else defaultConfig
            }
            is SeedTts2Config -> {
                val seedSaved = savedConfig as? SeedTts2Config
                if (seedSaved != null) seedSaved else defaultConfig
            }
            else -> defaultConfig
        }
    }

    val getLabel: (String) -> String? = remember(engine) {
        { key: String ->
            engine?.getConfigLabel(key, context)
        }
    }

    var configItems by remember(currentEngine, configForEdit, isOpen, getLabel) {
        mutableStateOf(
            buildConfigItems(configForEdit, getLabel)
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
                        val newConfig = buildConfigFromItems(
                            configItems,
                            defaultConfig
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

private fun buildConfigItems(
    config: BaseEngineConfig,
    getLabel: (String) -> String?
): List<ConfigItem> {
    val items = mutableListOf<ConfigItem>()

    when (config) {
        is Qwen3TtsConfig -> {
            val label = getLabel("api_key")
            if (label != null) {
                items.add(
                    ConfigItem(
                        key = "api_key",
                        label = label,
                        value = config.apiKey,
                        isPassword = true
                    )
                )
            }
        }
        is SeedTts2Config -> {
            val apiKeyLabel = getLabel("api_key")
            if (apiKeyLabel != null) {
                items.add(
                    ConfigItem(
                        key = "api_key",
                        label = apiKeyLabel,
                        value = config.apiKey,
                        isPassword = true
                    )
                )
            }
        }
    }

    val voiceLabel = getLabel("voice_id")
    if (voiceLabel != null) {
        items.add(
            ConfigItem(
                key = "voice_id",
                label = voiceLabel,
                value = config.voiceId,
                isVoiceSelector = true
            )
        )
    }

    return items
}

private fun buildConfigFromItems(
    items: List<ConfigItem>,
    defaultConfig: BaseEngineConfig
): BaseEngineConfig {
    val voiceId = items.find { it.key == "voice_id" }?.value ?: defaultConfig.voiceId

    return when (defaultConfig) {
        is Qwen3TtsConfig -> {
            val apiKey = items.find { it.key == "api_key" }?.value ?: ""
            Qwen3TtsConfig(
                apiKey = apiKey,
                voiceId = voiceId
            )
        }
        is SeedTts2Config -> {
            val apiKey = items.find { it.key == "api_key" }?.value ?: ""
            SeedTts2Config(
                apiKey = apiKey,
                voiceId = voiceId
            )
        }
        else -> defaultConfig
    }
}
