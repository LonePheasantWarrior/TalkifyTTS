package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.github.lonepheasantwarrior.talkify.domain.model.AliyunBailianConfig
import com.github.lonepheasantwarrior.talkify.domain.model.AzureConfig
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ConfigItem
import com.github.lonepheasantwarrior.talkify.domain.model.LanguageBoost
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.MiniMaxConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ModelDownloadStatus
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.TencentCloudConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProvider
import com.github.lonepheasantwarrior.talkify.domain.model.VolcengineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.XiaomiConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelManager
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory

/**
 * 配置底部弹窗
 *
 * 展示供应商配置编辑界面，包含 API Key 输入和声音选择
 * 通过右下角悬浮按钮唤出
 *
 * 支持多供应商架构，每个供应商可以定义自己的配置项
 * 使用供应商的 [TtsProviderApi.createDefaultConfig] 方法动态创建正确的配置类型
 * 使用供应商的 [TtsProviderApi.getConfigLabel] 方法获取本地化的配置项标签
 *
 * @param modifier 修饰符
 * @param isOpen 是否展开弹窗
 * @param onDismiss 关闭弹窗的回调
 * @param currentProvider 当前选中的供应商
 * @param configRepository 配置仓储
 * @param voiceRepository 声音仓储
 * @param onConfigSaved 配置保存后的回调
 * @param onDownloadRequested 请求下载本地模型时回调（参数为 modelId）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBottomSheet(
    modifier: Modifier = Modifier,
    isOpen: Boolean,
    onDismiss: () -> Unit,
    currentProvider: TtsProvider,
    configRepository: ProviderConfigRepository,
    voiceRepository: VoiceRepository,
    onConfigSaved: (() -> Unit)? = null,
    onDownloadRequested: ((String) -> Unit)? = null
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

    val savedConfig = remember(currentProvider, isOpen) {
        configRepository.getConfig(currentProvider.id)
    }

    val provider = remember(currentProvider.id) {
        TtsProviderFactory.createProvider(currentProvider.id)
    }

    val defaultConfig = remember(currentProvider.id) {
        provider?.createDefaultConfig() ?: throw IllegalStateException("Provider not found: ${currentProvider.id}")
    }

    val configForEdit: BaseProviderConfig = remember(savedConfig, defaultConfig) {
        when (defaultConfig) {
            is AliyunBailianConfig -> {
                val qwenSaved = savedConfig as? AliyunBailianConfig
                qwenSaved ?: defaultConfig
            }
            is VolcengineConfig -> {
                val seedSaved = savedConfig as? VolcengineConfig
                seedSaved ?: defaultConfig
            }
            is TencentCloudConfig -> {
                val tencentSaved = savedConfig as? TencentCloudConfig
                tencentSaved ?: defaultConfig
            }
            is AzureConfig -> {
                val msSaved = savedConfig as? AzureConfig
                msSaved ?: defaultConfig
            }
            is XiaomiConfig -> {
                val mmSaved = savedConfig as? XiaomiConfig
                mmSaved ?: defaultConfig
            }
            is MiniMaxConfig -> {
                val mmSaved = savedConfig as? MiniMaxConfig
                mmSaved ?: defaultConfig
            }
            is LocalModelConfig -> {
                val localSaved = savedConfig as? LocalModelConfig
                localSaved ?: defaultConfig
            }
            else -> defaultConfig
        }
    }

    val getLabel: (String) -> String? = remember(provider) {
        { key: String ->
            provider?.getConfigLabel(key, context)
        }
    }

    val defaultApiUrl = remember(currentProvider.id) {
        provider?.getDefaultApiUrl() ?: ""
    }
    val defaultModelId = remember(currentProvider.id) {
        provider?.getDefaultModelId() ?: ""
    }

    val isLocalModel = configForEdit is LocalModelConfig
    val advancedItemKeys = remember(isLocalModel) {
        if (isLocalModel) setOf("api_url") else setOf("api_url", "model_id")
    }

    var configItems by remember(currentProvider, configForEdit, isOpen, getLabel) {
        mutableStateOf(
            buildConfigItems(configForEdit, getLabel, defaultApiUrl, defaultModelId)
        )
    }

    var availableVoices by remember(currentProvider, isOpen) {
        mutableStateOf<List<VoiceInfo>>(emptyList())
    }
    var isVoicesLoading by remember { mutableStateOf(false) }

    // 下载确认对话框状态
    var showDownloadDialog by remember { mutableStateOf(false) }
    var pendingModelId by remember { mutableStateOf("") }
    var pendingModelDisplayName by remember { mutableStateOf("") }

    LaunchedEffect(currentProvider, isOpen) {
        isVoicesLoading = true
        try {
            availableVoices = voiceRepository.getVoicesForProvider(currentProvider)
        } finally {
            isVoicesLoading = false
        }
    }

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
                    providerName = currentProvider.name,
                    configItems = configItems,
                    availableVoices = availableVoices,
                    onItemValueChange = { changedItem, newValue ->
                        configItems = configItems.map {
                            if (it.key == changedItem.key) it.copy(value = newValue) else it
                        }
                    },
                    onSaveClick = {
                        val newConfig = buildConfigFromItems(
                            configItems,
                            defaultConfig
                        )
                        if (newConfig is LocalModelConfig) {
                            val modelId = newConfig.modelId
                            val modelInfo = LocalModelRegistry.getModel(modelId)
                            val status = LocalModelManager.getModelStatus(modelId)
                            if (modelInfo != null && status == ModelDownloadStatus.NOT_DOWNLOADED) {
                                // 模型未下载，显示下载确认对话框
                                pendingModelId = modelId
                                pendingModelDisplayName = modelInfo.displayName
                                showDownloadDialog = true
                                return@ConfigEditor
                            }
                        }
                        // 已部署或其他供应商，直接保存
                        configRepository.saveConfig(currentProvider.id, newConfig)
                        onConfigSaved?.invoke()
                        onDismiss()
                    },
                    advancedItemKeys = advancedItemKeys,
                    onVoiceSelected = { voice ->
                        val voiceItem = configItems.find { it.key == "voice_id" }
                        if (voiceItem != null) {
                            configItems = configItems.map {
                                if (it.key == "voice_id") it.copy(value = voice.voiceId) else it
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                }
            }
        }
    }

    // 下载确认对话框
    if (showDownloadDialog) {
        val modelInfo = LocalModelRegistry.getModel(pendingModelId)
        AlertDialog(
            onDismissRequest = {
                showDownloadDialog = false
            },
            title = {
                Text(
                    text = stringResource(R.string.model_download_confirm_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.model_download_confirm_message,
                        modelInfo?.downloadSizeDisplay ?: ""
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDownloadDialog = false
                        // 先保存配置
                        val newConfig = buildConfigFromItems(configItems, defaultConfig)
                        configRepository.saveConfig(currentProvider.id, newConfig)
                        onConfigSaved?.invoke()
                        // 触发下载
                        onDownloadRequested?.invoke(pendingModelId)
                        onDismiss()
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
                    onClick = {
                        showDownloadDialog = false
                        // 仅保存配置，不下载
                        val newConfig = buildConfigFromItems(configItems, defaultConfig)
                        configRepository.saveConfig(currentProvider.id, newConfig)
                        onConfigSaved?.invoke()
                        onDismiss()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }
}

private fun buildConfigItems(
    config: BaseProviderConfig,
    getLabel: (String) -> String?,
    defaultApiUrl: String,
    defaultModelId: String
): List<ConfigItem> {
    val items = mutableListOf<ConfigItem>()

    // API 地址（仅当供应商支持自定义时展示，placeholder 显示默认值）
    if (defaultApiUrl.isNotEmpty()) {
        val apiUrlLabel = getLabel("api_url")
        if (apiUrlLabel != null) {
            items.add(
                ConfigItem(
                    key = "api_url",
                    label = apiUrlLabel,
                    value = config.apiUrl,
                    placeholder = defaultApiUrl
                )
            )
        }
    }

    // 模型 ID（仅当供应商支持自定义且非 LocalModel 时展示，placeholder 显示默认值）
    // LocalModel 的模型选择在 when 分支中以 dropdown 形式处理
    if (config !is LocalModelConfig && defaultModelId.isNotEmpty()) {
        val modelIdLabel = getLabel("model_id")
        if (modelIdLabel != null) {
            items.add(
                ConfigItem(
                    key = "model_id",
                    label = modelIdLabel,
                    value = config.modelId,
                    placeholder = defaultModelId
                )
            )
        }
    }

    when (config) {
        is AliyunBailianConfig -> {
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
        is VolcengineConfig -> {
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
        is TencentCloudConfig -> {
            val appIdLabel = getLabel("app_id")
            if (appIdLabel != null) {
                items.add(
                    ConfigItem(
                        key = "app_id",
                        label = appIdLabel,
                        value = config.appId,
                        isPassword = false
                    )
                )
            }
            val secretIdLabel = getLabel("secret_id")
            if (secretIdLabel != null) {
                items.add(
                    ConfigItem(
                        key = "secret_id",
                        label = secretIdLabel,
                        value = config.secretId,
                        isPassword = true
                    )
                )
            }
            val secretKeyLabel = getLabel("secret_key")
            if (secretKeyLabel != null) {
                items.add(
                    ConfigItem(
                        key = "secret_key",
                        label = secretKeyLabel,
                        value = config.secretKey,
                        isPassword = true
                    )
                )
            }
        }
        is AzureConfig -> {
        }
        is XiaomiConfig -> {
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
            val styleLabel = getLabel("style_instruction")
            if (styleLabel != null) {
                items.add(
                    ConfigItem(
                        key = "style_instruction",
                        label = styleLabel,
                        value = config.styleInstruction,
                        placeholder = "例如：用温柔的语气朗读"
                    )
                )
            }
        }
        is MiniMaxConfig -> {
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
        is LocalModelConfig -> {
            // 模型选择：从 LocalModelRegistry 构建下拉选项（含下载状态标记）
            val modelLabel = getLabel("model_id") ?: "选择模型"
            val modelOptions = LocalModelRegistry.ALL_MODELS.map { model ->
                val status = LocalModelManager.getModelStatus(model.id)
                val statusSuffix = when (status) {
                    ModelDownloadStatus.DEPLOYED -> " \u2713"
                    ModelDownloadStatus.DOWNLOADING -> " [下载中...]"
                    ModelDownloadStatus.NOT_DOWNLOADED -> " [未下载]"
                    ModelDownloadStatus.ERROR -> " [错误]"
                }
                model.id to "${model.displayName}$statusSuffix"
            }
            items.add(
                ConfigItem(
                    key = "model_id",
                    label = modelLabel,
                    value = config.modelId.ifBlank { ProviderIds.LocalModel.defaultModelId },
                    dropdownOptions = modelOptions
                )
            )
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

    if (config is MiniMaxConfig) {
        val synthConfigLabel = getLabel("continuous_sound")
        if (synthConfigLabel != null) {
            val csValue = when (config.continuousSound) {
                true -> "true"
                false -> "false"
                null -> "default"
            }
            items.add(
                ConfigItem(
                    key = "continuous_sound",
                    label = synthConfigLabel,
                    value = csValue,
                    dropdownOptions = listOf(
                        "default" to "默认",
                        "true" to "更自然韵律",
                        "false" to "更快速度"
                    )
                )
            )
        }

        val languageBoostLabel = getLabel("language_boost")
        if (languageBoostLabel != null) {
            items.add(
                ConfigItem(
                    key = "language_boost",
                    label = languageBoostLabel,
                    value = config.languageBoost.name,
                    dropdownOptions = listOf(
                        "OFF" to "关闭",
                        "AUTO" to "自动",
                        "CHINESE" to "中文",
                        "ENGLISH" to "英文"
                    )
                )
            )
        }

        val englishNormLabel = getLabel("english_normalization")
        if (englishNormLabel != null) {
            items.add(
                ConfigItem(
                    key = "english_normalization",
                    label = englishNormLabel,
                    value = config.englishNormalization.toString(),
                    dropdownOptions = listOf(
                        "true" to "开启",
                        "false" to "关闭"
                    )
                )
            )
        }
    }

    return items
}

private fun buildConfigFromItems(
    items: List<ConfigItem>,
    defaultConfig: BaseProviderConfig
): BaseProviderConfig {
    val voiceId = items.find { it.key == "voice_id" }?.value ?: defaultConfig.voiceId
    val apiUrl = items.find { it.key == "api_url" }?.value ?: ""
    val modelId = items.find { it.key == "model_id" }?.value ?: ""

    return when (defaultConfig) {
        is AliyunBailianConfig -> {
            val apiKey = items.find { it.key == "api_key" }?.value ?: ""
            AliyunBailianConfig(
                apiKey = apiKey,
                voiceId = voiceId,
                apiUrl = apiUrl,
                modelId = modelId
            )
        }
        is VolcengineConfig -> {
            val apiKey = items.find { it.key == "api_key" }?.value ?: ""
            VolcengineConfig(
                apiKey = apiKey,
                voiceId = voiceId,
                apiUrl = apiUrl,
                modelId = modelId
            )
        }
        is TencentCloudConfig -> {
            val appId = items.find { it.key == "app_id" }?.value ?: ""
            val secretId = items.find { it.key == "secret_id" }?.value ?: ""
            val secretKey = items.find { it.key == "secret_key" }?.value ?: ""
            TencentCloudConfig(
                appId = appId,
                secretId = secretId,
                secretKey = secretKey,
                voiceId = voiceId,
                apiUrl = apiUrl,
                modelId = modelId
            )
        }
        is AzureConfig -> {
            AzureConfig(
                voiceId = voiceId,
                apiUrl = apiUrl
            )
        }
        is XiaomiConfig -> {
            val apiKey = items.find { it.key == "api_key" }?.value ?: ""
            val styleInstruction = items.find { it.key == "style_instruction" }?.value ?: ""
            XiaomiConfig(
                apiKey = apiKey,
                voiceId = voiceId,
                apiUrl = apiUrl,
                modelId = modelId,
                styleInstruction = styleInstruction
            )
        }
        is MiniMaxConfig -> {
            val apiKey = items.find { it.key == "api_key" }?.value ?: ""
            val continuousSound = when (val csVal = items.find { it.key == "continuous_sound" }?.value) {
                "default", null -> null
                else -> csVal.toBooleanStrictOrNull()
            }
            val languageBoost = try {
                LanguageBoost.valueOf(items.find { it.key == "language_boost" }?.value ?: LanguageBoost.OFF.name)
            } catch (_: IllegalArgumentException) {
                LanguageBoost.OFF
            }
            val englishNormalization = items.find { it.key == "english_normalization" }?.value?.toBooleanStrictOrNull() ?: false
            MiniMaxConfig(
                apiKey = apiKey,
                voiceId = voiceId,
                apiUrl = apiUrl,
                modelId = modelId,
                continuousSound = continuousSound,
                languageBoost = languageBoost,
                englishNormalization = englishNormalization
            )
        }
        is LocalModelConfig -> {
            LocalModelConfig(
                voiceId = voiceId,
                apiUrl = "",
                modelId = modelId
            )
        }
        else -> defaultConfig
    }
}
