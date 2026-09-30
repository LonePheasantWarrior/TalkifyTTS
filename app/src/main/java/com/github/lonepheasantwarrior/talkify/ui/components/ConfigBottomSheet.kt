package com.github.lonepheasantwarrior.talkify.ui.components

import android.content.Context
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
import com.github.lonepheasantwarrior.talkify.domain.model.GoogleConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LanguageBoost
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.MiniMaxConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ModelDownloadStatus
import com.github.lonepheasantwarrior.talkify.domain.model.OpenAIConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.TencentCloudConfig
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProvider
import com.github.lonepheasantwarrior.talkify.domain.model.VolcengineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.XiaomiConfig
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelManager
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppActionTracker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppPageTracker
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.localmodel.DownloadProgress

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
 * @param downloadProgress 本地模型下载进度（下载中在模型项下方展示进度条；null 或已完成不展示）
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
    onDownloadRequested: ((String) -> Unit)? = null,
    downloadProgress: DownloadProgress? = null
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    val context = LocalContext.current

    LaunchedEffect(isOpen) {
        if (isOpen) {
            // 抽屉打开 = "去了哪里"，按虚拟路由上报 pageview（覆盖 FAB 与未配置引导等所有入口）
            AppPageTracker.open(AppPageTracker.PATH_CONFIG, "Config")
        } else if (sheetState.isVisible) {
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
        // 工厂返回 null 属异常数据（注册表与展示列表失同步），
        // 组合期抛异常会直接崩溃整个界面，降级为可恢复的空配置
        provider?.createDefaultConfig()
            ?: run {
                TtsLogger.e("ConfigBottomSheet: provider not found: ${currentProvider.id}")
                LocalModelConfig()
            }
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
            is GoogleConfig -> {
                val googleSaved = savedConfig as? GoogleConfig
                googleSaved ?: defaultConfig
            }
            is OpenAIConfig -> {
                val openaiSaved = savedConfig as? OpenAIConfig
                openaiSaved ?: defaultConfig
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
    val advancedItemKeys = remember(isLocalModel, configForEdit) {
        when {
            isLocalModel -> setOf("api_url")
            configForEdit is GoogleConfig -> GoogleConfig.ADVANCED_ITEM_KEYS
            configForEdit is OpenAIConfig -> OpenAIConfig.ADVANCED_ITEM_KEYS
            else -> setOf("api_url", "model_id")
        }
    }

    var configItems by remember(currentProvider, configForEdit, isOpen, getLabel) {
        mutableStateOf(
            buildConfigItems(configForEdit, getLabel, defaultApiUrl, defaultModelId, context)
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
                        configItems = configItems.map { item ->
                            when {
                                item.key == changedItem.key -> item.copy(value = newValue)
                                // 自定义声音 ID 输入联动：声音选择即时切换为"自定义"展示
                                changedItem.key == "custom_voice_id" && item.key == "voice_id" ->
                                    item.copy(displayValue = customVoiceDisplayValue(newValue, context))
                                // 接口规范切换联动：API 地址语义随规范变化，占位符即时切换
                                changedItem.key == "api_spec" && item.key == "api_url" ->
                                    item.copy(placeholder = googleApiUrlPlaceholder(newValue))
                                else -> item
                            }
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
                                AppPageTracker.open(
                                    AppPageTracker.PATH_MODEL_DOWNLOAD_CONFIRM,
                                    "ModelDownloadConfirm"
                                )
                                showDownloadDialog = true
                                return@ConfigEditor
                            }
                        }
                        // 已下载或其他供应商，直接保存
                        configRepository.saveConfig(currentProvider.id, newConfig)
                        onConfigSaved?.invoke()
                        onDismiss()
                    },
                    advancedItemKeys = advancedItemKeys,
                    downloadingModelProgress = downloadProgress,
                    onVoiceSelected = { voice ->
                        configItems = configItems.map { item ->
                            when (item.key) {
                                // 选中内置音色即退出"自定义声音"模式，恢复常规展示
                                "voice_id" -> item.copy(value = voice.voiceId, displayValue = null)
                                "custom_voice_id" -> item.copy(value = "")
                                else -> item
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
                AppActionTracker.modelDownloadDialog(
                    pendingModelId,
                    modelInfo?.downloadSizeDisplay.orEmpty(),
                    AppActionTracker.SOURCE_CONFIG_SHEET,
                    confirmed = false
                )
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
                        AppActionTracker.modelDownloadDialog(
                            pendingModelId,
                            modelInfo?.downloadSizeDisplay.orEmpty(),
                            AppActionTracker.SOURCE_CONFIG_SHEET,
                            confirmed = true
                        )
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
                        AppActionTracker.modelDownloadDialog(
                            pendingModelId,
                            modelInfo?.downloadSizeDisplay.orEmpty(),
                            AppActionTracker.SOURCE_CONFIG_SHEET,
                            confirmed = false
                        )
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

/**
 * 自定义声音 ID 生效时"声音选择"项的展示文本。
 * 仅覆盖展示（[ConfigItem.displayValue]），真实选中值保留，清空自定义 ID 后恢复常规展示
 */
private fun customVoiceDisplayValue(customVoiceId: String, context: Context): String? =
    context.getString(R.string.voice_custom_display).takeIf { customVoiceId.isNotBlank() }

/**
 * Google 供应商"API 地址"项的占位符：随接口规范切换语义——
 * interactions 为完整端点，generateContent 为 "/models" 基础前缀
 */
private fun googleApiUrlPlaceholder(apiSpec: String): String =
    if (apiSpec == GoogleConfig.SPEC_GENERATE_CONTENT) {
        GoogleConfig.DEFAULT_GENERATE_CONTENT_API_URL
    } else {
        GoogleConfig.DEFAULT_INTERACTIONS_API_URL
    }

private fun buildConfigItems(
    config: BaseProviderConfig,
    getLabel: (String) -> String?,
    defaultApiUrl: String,
    defaultModelId: String,
    context: Context
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
                        placeholder = context.getString(R.string.style_instruction_placeholder),
                        supportingText = context.getString(R.string.style_instruction_hint),
                        isDialogEditor = true,
                        editorTitle = context.getString(R.string.style_instruction_edit_title),
                        guideContent = context.getString(R.string.style_instruction_guide_content)
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
        is GoogleConfig -> {
            // 接口规范：interactions / generateContent 双实现分派。
            // 置于列表首位，使高级面板中先于 API 地址展示——规范决定其地址语义
            val specLabel = getLabel("api_spec")
            if (specLabel != null) {
                items.add(
                    0,
                    ConfigItem(
                        key = "api_spec",
                        label = specLabel,
                        value = config.apiSpec.ifBlank { GoogleConfig.SPEC_INTERACTIONS },
                        dropdownOptions = listOf(
                            GoogleConfig.SPEC_INTERACTIONS to context.getString(R.string.api_spec_interactions),
                            GoogleConfig.SPEC_GENERATE_CONTENT to context.getString(R.string.api_spec_generate_content)
                        )
                    )
                )
            }
            // API 地址语义随接口规范变化：interactions 为完整端点，generateContent 为 "/models" 基础前缀
            val apiUrlIndex = items.indexOfFirst { it.key == "api_url" }
            if (apiUrlIndex >= 0) {
                items[apiUrlIndex] = items[apiUrlIndex]
                    .copy(placeholder = googleApiUrlPlaceholder(config.apiSpec))
            }
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
                        placeholder = context.getString(R.string.style_instruction_placeholder),
                        supportingText = context.getString(R.string.style_instruction_hint),
                        isDialogEditor = true,
                        editorTitle = context.getString(R.string.gemini_style_instruction_edit_title),
                        guideContent = context.getString(R.string.gemini_style_instruction_guide_content)
                    )
                )
            }
            // 代理设置：中国大陆通常无法直连 Google 服务（高级设置面板展示）。
            // 代理协议为总开关："无"= 直连并隐藏主机/端口输入框，选中 HTTP/SOCKS 时需完整配置
            val protocolLabel = getLabel("proxy_protocol")
            if (protocolLabel != null) {
                items.add(
                    ConfigItem(
                        key = "proxy_protocol",
                        label = protocolLabel,
                        value = config.proxyProtocol.ifBlank { GoogleConfig.PROTOCOL_NONE },
                        dropdownOptions = listOf(
                            GoogleConfig.PROTOCOL_NONE to context.getString(R.string.proxy_protocol_none),
                            GoogleConfig.PROTOCOL_HTTP to context.getString(R.string.proxy_protocol_http),
                            GoogleConfig.PROTOCOL_SOCKS to context.getString(R.string.proxy_protocol_socks)
                        )
                    )
                )
            }
            // 主机/端口仅在选择具体协议时展示；项与值始终保留，切回协议后原配置自动恢复
            val proxyAddressVisible: (List<ConfigItem>) -> Boolean = { items ->
                items.find { it.key == "proxy_protocol" }?.value?.let { it != GoogleConfig.PROTOCOL_NONE } ?: false
            }
            val hostLabel = getLabel("proxy_host")
            if (hostLabel != null) {
                items.add(
                    ConfigItem(
                        key = "proxy_host",
                        label = hostLabel,
                        value = config.proxyHost,
                        placeholder = context.getString(R.string.proxy_host_placeholder),
                        visibleWhen = proxyAddressVisible
                    )
                )
            }
            val portLabel = getLabel("proxy_port")
            if (portLabel != null) {
                items.add(
                    ConfigItem(
                        key = "proxy_port",
                        label = portLabel,
                        value = config.proxyPort,
                        placeholder = context.getString(R.string.proxy_port_placeholder),
                        isNumericKeyboard = true,
                        visibleWhen = proxyAddressVisible
                    )
                )
            }
        }
        is OpenAIConfig -> {
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
            // 自定义声音 ID：面向遵循 OpenAI 规范但音色标识自定的第三方转接平台。
            // 紧随"声音选择"之后排列，便于理解联动关系；非空时优先于预置音色生效
            val customVoiceLabel = getLabel("custom_voice_id")
            if (customVoiceLabel != null) {
                items.add(
                    ConfigItem(
                        key = "custom_voice_id",
                        label = customVoiceLabel,
                        value = config.customVoiceId,
                        placeholder = context.getString(R.string.custom_voice_id_placeholder)
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
                        placeholder = context.getString(R.string.style_instruction_placeholder),
                        supportingText = context.getString(R.string.style_instruction_hint),
                        isDialogEditor = true,
                        editorTitle = context.getString(R.string.style_instruction_edit_title),
                        guideContent = context.getString(R.string.openai_style_instruction_guide_content)
                    )
                )
            }
            // 代理设置：OpenAI 官方服务与第三方转接平台可能存在网络可达性问题（高级设置面板展示）。
            // 代理协议为总开关："无"= 直连并隐藏主机/端口输入框，选中 HTTP/SOCKS 时需完整配置
            val protocolLabel = getLabel("proxy_protocol")
            if (protocolLabel != null) {
                items.add(
                    ConfigItem(
                        key = "proxy_protocol",
                        label = protocolLabel,
                        value = config.proxyProtocol.ifBlank { OpenAIConfig.PROTOCOL_NONE },
                        dropdownOptions = listOf(
                            OpenAIConfig.PROTOCOL_NONE to context.getString(R.string.proxy_protocol_none),
                            OpenAIConfig.PROTOCOL_HTTP to context.getString(R.string.proxy_protocol_http),
                            OpenAIConfig.PROTOCOL_SOCKS to context.getString(R.string.proxy_protocol_socks)
                        )
                    )
                )
            }
            // 主机/端口仅在选择具体协议时展示；项与值始终保留，切回协议后原配置自动恢复
            val proxyAddressVisible: (List<ConfigItem>) -> Boolean = { items ->
                items.find { it.key == "proxy_protocol" }?.value?.let { it != OpenAIConfig.PROTOCOL_NONE } ?: false
            }
            val hostLabel = getLabel("proxy_host")
            if (hostLabel != null) {
                items.add(
                    ConfigItem(
                        key = "proxy_host",
                        label = hostLabel,
                        value = config.proxyHost,
                        placeholder = context.getString(R.string.proxy_host_placeholder),
                        visibleWhen = proxyAddressVisible
                    )
                )
            }
            val portLabel = getLabel("proxy_port")
            if (portLabel != null) {
                items.add(
                    ConfigItem(
                        key = "proxy_port",
                        label = portLabel,
                        value = config.proxyPort,
                        placeholder = context.getString(R.string.proxy_port_placeholder),
                        isNumericKeyboard = true,
                        visibleWhen = proxyAddressVisible
                    )
                )
            }
        }
        is LocalModelConfig -> {
            // 模型选择：从 LocalModelRegistry 构建下拉选项（含下载状态标记）
            val modelLabel = getLabel("model_id") ?: context.getString(R.string.model_select_label)
            val modelOptions = LocalModelRegistry.ALL_MODELS.map { model ->
                val status = LocalModelManager.getModelStatus(model.id)
                val statusSuffix = when (status) {
                    ModelDownloadStatus.DOWNLOADED -> " " + context.getString(R.string.model_status_downloaded)
                    ModelDownloadStatus.DOWNLOADING -> " " + context.getString(R.string.model_status_downloading)
                    ModelDownloadStatus.NOT_DOWNLOADED -> " " + context.getString(R.string.model_not_downloaded)
                    ModelDownloadStatus.ERROR -> " " + context.getString(R.string.model_status_error)
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
        // 自定义声音 ID 生效时，声音选择仅展示"自定义"（真实选中值保留，清空自定义后恢复）
        val customVoiceDisplay = (config as? OpenAIConfig)
            ?.let { customVoiceDisplayValue(it.customVoiceId, context) }
        val voiceItem = ConfigItem(
            key = "voice_id",
            label = voiceLabel,
            value = config.voiceId,
            displayValue = customVoiceDisplay,
            isVoiceSelector = true
        )
        // 排列约定：声音选择紧跟认证项（API Key）之后，风格指令、自定义声音 ID 等
        // 供应商附加输入项统一靠后，紧邻"高级设置"折叠按钮；无认证项的供应商保持追加到末尾
        val anchorIndex = items.indexOfFirst { it.key == "api_key" }
        if (anchorIndex >= 0) items.add(anchorIndex + 1, voiceItem) else items.add(voiceItem)
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
                        "default" to context.getString(R.string.continuous_sound_default),
                        "true" to context.getString(R.string.continuous_sound_natural),
                        "false" to context.getString(R.string.continuous_sound_fast)
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
                        "OFF" to context.getString(R.string.language_boost_off),
                        "AUTO" to context.getString(R.string.language_boost_auto),
                        "CHINESE" to context.getString(R.string.language_boost_chinese),
                        "ENGLISH" to context.getString(R.string.language_boost_english)
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
                        "true" to context.getString(R.string.switch_option_on),
                        "false" to context.getString(R.string.switch_option_off)
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
        is GoogleConfig -> {
            val apiKey = items.find { it.key == "api_key" }?.value ?: ""
            val styleInstruction = items.find { it.key == "style_instruction" }?.value ?: ""
            val apiSpec = items.find { it.key == "api_spec" }?.value
                ?.ifBlank { GoogleConfig.SPEC_INTERACTIONS }
                ?: GoogleConfig.SPEC_INTERACTIONS
            val proxyProtocol = items.find { it.key == "proxy_protocol" }?.value
                ?.ifBlank { GoogleConfig.PROTOCOL_NONE }
                ?: GoogleConfig.PROTOCOL_NONE
            GoogleConfig(
                apiKey = apiKey,
                voiceId = voiceId,
                apiUrl = apiUrl,
                modelId = modelId,
                styleInstruction = styleInstruction,
                apiSpec = apiSpec,
                proxyProtocol = proxyProtocol,
                proxyHost = items.find { it.key == "proxy_host" }?.value ?: "",
                proxyPort = items.find { it.key == "proxy_port" }?.value ?: ""
            )
        }
        is OpenAIConfig -> {
            val apiKey = items.find { it.key == "api_key" }?.value ?: ""
            val styleInstruction = items.find { it.key == "style_instruction" }?.value ?: ""
            val customVoiceId = items.find { it.key == "custom_voice_id" }?.value ?: ""
            val proxyProtocol = items.find { it.key == "proxy_protocol" }?.value
                ?.ifBlank { OpenAIConfig.PROTOCOL_NONE }
                ?: OpenAIConfig.PROTOCOL_NONE
            OpenAIConfig(
                apiKey = apiKey,
                voiceId = voiceId,
                apiUrl = apiUrl,
                modelId = modelId,
                styleInstruction = styleInstruction,
                customVoiceId = customVoiceId,
                proxyProtocol = proxyProtocol,
                proxyHost = items.find { it.key == "proxy_host" }?.value ?: "",
                proxyPort = items.find { it.key == "proxy_port" }?.value ?: ""
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
