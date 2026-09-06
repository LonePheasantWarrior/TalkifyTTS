package com.github.lonepheasantwarrior.talkify.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.AliyunBailianConfig
import com.github.lonepheasantwarrior.talkify.domain.model.AzureConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.MiniMaxConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TencentCloudConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.VolcengineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.XiaomiConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelManager
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.power.PowerOptimizationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.AliyunBailianConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.AliyunBailianVoiceRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import com.github.lonepheasantwarrior.talkify.ui.components.BatteryOptimizationDialog
import com.github.lonepheasantwarrior.talkify.ui.components.ConfigBottomSheet
import com.github.lonepheasantwarrior.talkify.ui.components.NetworkBlockedDialog
import com.github.lonepheasantwarrior.talkify.ui.components.NotificationPermissionDialog
import com.github.lonepheasantwarrior.talkify.ui.components.ProviderSelector
import com.github.lonepheasantwarrior.talkify.ui.components.UpdateDialog
import com.github.lonepheasantwarrior.talkify.ui.components.VoicePreview
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.MainViewModel
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.startup.StartupState
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(),
    onAboutClick: () -> Unit = {}
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- 启动流程状态管理 ---
    val startupState by viewModel.uiState.collectAsState()
    val isDefaultProvider by viewModel.isDefaultProvider.collectAsState()

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && startupState == StartupState.Completed) {
                viewModel.refreshDefaultProviderStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 权限请求 Launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.onNotificationPermissionResult()
    }

    // 设置页跳转 Launcher (网络设置)
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onNetworkRetry()
    }

    // --- 现有业务逻辑 ---

    // 根据当前供应商获取对应的声音仓储
    fun getVoiceRepository(providerId: String): VoiceRepository {
        return TtsProviderFactory.createVoiceRepository(providerId, context)
            ?: AliyunBailianVoiceRepository(context)
    }

    // 根据当前供应商获取对应的配置仓储
    fun getConfigRepository(providerId: String): ProviderConfigRepository {
        return TtsProviderFactory.createConfigRepository(providerId, context)
            ?: AliyunBailianConfigRepository(context)
    }

    val appConfigRepository: AppConfigRepository = remember {
        SharedPreferencesAppConfigRepository(context)
    }

    val availableProviders = TtsProviderRegistry.availableProviders
    val defaultProvider = TtsProviderRegistry.defaultProvider

    var currentProvider by remember {
        mutableStateOf(defaultProvider)
    }

    // 配置版本号，用于在配置保存后触发供应商列表展示刷新
    var configVersion by remember { mutableIntStateOf(0) }

    // Demo Service logic moved to ViewModel

    LaunchedEffect(appConfigRepository) {
        val savedProviderId = appConfigRepository.getSelectedProviderId()
        if (savedProviderId != null) {
            TtsProviderRegistry.getProvider(savedProviderId)?.let { provider ->
                currentProvider = provider
            }
        } else {
            appConfigRepository.saveSelectedProviderId(defaultProvider.id)
        }
    }

    // Demo state observation
    val isPlaying by viewModel.isDemoPlaying.collectAsState()
    val demoError by viewModel.demoErrorMessage.collectAsState()
    
    LaunchedEffect(demoError) {
        demoError?.let { msg ->
            scope.launch {
                snackbarHostState.showSnackbar(msg)
            }
            viewModel.clearDemoError()
        }
    }

    // 下载完成状态反馈
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    @Suppress("LocalContextGetResourceValueCall")
    LaunchedEffect(downloadProgress) {
        val progress = downloadProgress
        if (progress != null && progress.isCompleted) {
            val message = context.getString(R.string.model_download_complete, progress.displayName)
            scope.launch {
                snackbarHostState.showSnackbar(message)
            }
            viewModel.clearDownloadProgress()
            // 刷新配置版本以更新下拉菜单状态
            configVersion++
        }
    }

    var availableVoices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf<VoiceInfo?>(null) }

    val sampleTexts = stringArrayResource(R.array.texts)
    val defaultInputText = remember(sampleTexts) {
        sampleTexts.random()
    }
    var inputText by remember { mutableStateOf(defaultInputText) }
    val isConfigSheetOpen by viewModel.isConfigSheetOpen.collectAsState()

    var savedConfig by remember(currentProvider.id) {
        mutableStateOf(getConfigRepository(currentProvider.id).getConfig(currentProvider.id))
    }

    // 本地模型下载确认对话框状态
    var pendingLocalModelConfig by remember { mutableStateOf<LocalModelConfig?>(null) }

    LaunchedEffect(currentProvider) {
        savedConfig = getConfigRepository(currentProvider.id).getConfig(currentProvider.id)
        val voices = getVoiceRepository(currentProvider.id).getVoicesForProvider(currentProvider)
        availableVoices = voices
        selectedVoice = availableVoices.find { it.voiceId == savedConfig.voiceId } ?: voices.firstOrNull()
    }

    // 根据用户自定义模型 ID 计算当前供应商的展示信息（模型名称自适应）
    val displayProvider = remember(savedConfig, currentProvider, configVersion) {
        val effectiveModelId = savedConfig.modelId.ifBlank {
            // 用户未自定义模型 ID 时，使用供应商默认模型 ID（若支持），否则保持原始展示
            val provider = TtsProviderFactory.createProvider(currentProvider.id)
            provider?.getDefaultModelId()?.ifBlank { currentProvider.provider } ?: currentProvider.provider
        }
        currentProvider.copy(provider = effectiveModelId)
    }

    // 供应商列表展示也自适应各供应商的自定义模型 ID
    val displayProviders = remember(availableProviders, configVersion) {
        availableProviders.map { provider ->
            val config = getConfigRepository(provider.id).getConfig(provider.id)
            val effectiveModelId = config.modelId.ifBlank {
                val p = TtsProviderFactory.createProvider(provider.id)
                p?.getDefaultModelId()?.ifBlank { provider.provider } ?: provider.provider
            }
            provider.copy(provider = effectiveModelId)
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.openConfigSheet() },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.cd_settings_button)
                )
            }
        },
        topBar = {
            LargeTopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable(onClick = onAboutClick)
                    ) {
                        Text(
                            text = "Talkify",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {},
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val context = LocalContext.current
            val aboutPageOpenedBefore = remember {
                context.getSharedPreferences("talkify_app_config", Context.MODE_PRIVATE)
                    .getBoolean("has_opened_about_page", false)
            }
            var aboutHintDismissed by remember { mutableStateOf(false) }

            AnimatedVisibility(
                visible = !aboutPageOpenedBefore && !aboutHintDismissed && startupState == StartupState.Completed,
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it })
            ) {
                AboutPageHintBanner(
                    onClick = { aboutHintDismissed = true }
                )
            }

            AnimatedVisibility(
                visible = !isDefaultProvider && startupState == StartupState.Completed,
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it })
            ) {
                 DefaultProviderBanner(
                     onClick = { viewModel.openTtsSettings() }
                 )
            }

            when (startupState) {
                StartupState.CheckingNetwork -> {
                    // 显示加载中
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.checking_network),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                StartupState.NetworkBlocked -> {
                    NetworkBlockedDialog(
                        onOpenSettings = {
                            viewModel.openSystemSettings()
                        },
                        onExit = {
                            activity?.finish()
                        }
                    )
                }
                else -> {
                    // 网络检查通过，显示主界面内容
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))

                        ProviderSelector(
                            currentProvider = displayProvider,
                            availableProviders = displayProviders,
                            onProviderSelected = { provider ->
                                currentProvider = provider
                                appConfigRepository.saveSelectedProviderId(provider.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        VoicePreview(
                            inputText = inputText,
                            onInputTextChange = { inputText = it },
                            availableVoices = availableVoices,
                            selectedVoice = selectedVoice,
                            onVoiceSelected = { voice -> selectedVoice = voice },
                            isPlaying = isPlaying,
                            onPlayClick = {
                                if (inputText.isBlank()) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("请输入要合成的文本")
                                    }
                                    return@VoicePreview
                                }

                                val config = when (savedConfig) {
                                    is AliyunBailianConfig -> {
                                        val qwenConfig = savedConfig as? AliyunBailianConfig ?: AliyunBailianConfig()
                                        qwenConfig.copy(voiceId = selectedVoice?.voiceId ?: qwenConfig.voiceId)
                                    }
                                    is VolcengineConfig -> {
                                        val seedConfig = savedConfig as? VolcengineConfig ?: VolcengineConfig()
                                        seedConfig.copy(voiceId = selectedVoice?.voiceId ?: seedConfig.voiceId)
                                    }
                                    is TencentCloudConfig -> {
                                        val tencentConfig = savedConfig as? TencentCloudConfig ?: TencentCloudConfig()
                                        tencentConfig.copy(voiceId = selectedVoice?.voiceId ?: tencentConfig.voiceId)
                                    }
                                    is AzureConfig -> {
                                        val msConfig = savedConfig as? AzureConfig ?: AzureConfig()
                                        msConfig.copy(voiceId = selectedVoice?.voiceId ?: msConfig.voiceId)
                                    }
                                    is XiaomiConfig -> {
                                        val xmConfig = savedConfig as? XiaomiConfig ?: XiaomiConfig()
                                        xmConfig.copy(voiceId = selectedVoice?.voiceId ?: xmConfig.voiceId)
                                    }
                                    is MiniMaxConfig -> {
                                        val mmConfig = savedConfig as? MiniMaxConfig ?: MiniMaxConfig()
                                        mmConfig.copy(voiceId = selectedVoice?.voiceId ?: mmConfig.voiceId)
                                    }
                                    is LocalModelConfig -> {
                                        val lmConfig = savedConfig as? LocalModelConfig ?: LocalModelConfig()
                                        lmConfig.copy(voiceId = selectedVoice?.voiceId ?: lmConfig.voiceId)
                                    }
                                    else -> savedConfig
                                }

                                val isConfigured = when (config) {
                                    is AliyunBailianConfig -> config.apiKey.isNotBlank()
                                    is VolcengineConfig -> config.apiKey.isNotBlank()
                                    is TencentCloudConfig -> config.appId.isNotBlank() &&
                                            config.secretId.isNotBlank() && 
                                            config.secretKey.isNotBlank()
                                    is AzureConfig -> true
                                    is XiaomiConfig -> config.apiKey.isNotBlank()
                                    is MiniMaxConfig -> config.apiKey.isNotBlank()
                                    is LocalModelConfig -> config.modelId.isNotBlank() && LocalModelManager.isModelDeployed(config.modelId)
                                    else -> false
                                }

                                if (!isConfigured) {
                                    // 本地模型：已选择模型但未下载 → 弹出下载确认对话框
                                    if (config is LocalModelConfig && config.modelId.isNotBlank()) {
                                        pendingLocalModelConfig = config
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("请先完成供应商配置")
                                        }
                                        viewModel.openConfigSheet()
                                    }
                                    return@VoicePreview
                                }

                                // 本地模型下载中时阻止播放
                                if (config is LocalModelConfig) {
                                    val conflict = viewModel.checkLocalModelPlayable(config.modelId)
                                    if (conflict != null) {
                                        scope.launch { snackbarHostState.showSnackbar(conflict) }
                                        return@VoicePreview
                                    }
                                }

                                viewModel.playDemo(currentProvider.id, inputText, config)
                            },
                            onStopClick = {
                                viewModel.stopDemo()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    ConfigBottomSheet(
        onConfigSaved = {
            savedConfig = getConfigRepository(currentProvider.id).getConfig(currentProvider.id)
            configVersion++
        },
        isOpen = isConfigSheetOpen,
        onDismiss = { viewModel.closeConfigSheet() },
        currentProvider = currentProvider,
        configRepository = getConfigRepository(currentProvider.id),
        voiceRepository = getVoiceRepository(currentProvider.id),
        onDownloadRequested = { modelId ->
            val conflict = viewModel.startModelDownload(modelId)
            if (conflict != null) {
                scope.launch { snackbarHostState.showSnackbar(conflict) }
            }
        }
    )

    // --- 本地模型下载确认对话框 ---
    val pendingConfig = pendingLocalModelConfig
    if (pendingConfig != null) {
        val modelInfo = LocalModelRegistry.getModel(pendingConfig.modelId)
        AlertDialog(
            onDismissRequest = { pendingLocalModelConfig = null },
            title = { Text(stringResource(R.string.model_download_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.model_download_confirm_message,
                        modelInfo?.downloadSizeDisplay ?: "?? MB"
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLocalModelConfig = null
                        val conflict = viewModel.startModelDownload(pendingConfig.modelId)
                        if (conflict != null) {
                            scope.launch { snackbarHostState.showSnackbar(conflict) }
                        }
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLocalModelConfig = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // --- 启动流程中的非阻塞弹窗 ---

    when (startupState) {
        StartupState.RequestingNotification -> {
            NotificationPermissionDialog(
                onConfirm = {
                    val permission = Manifest.permission.POST_NOTIFICATIONS
                    if (activity != null) {
                        val shouldShowRationale = activity.shouldShowRequestPermissionRationale(permission)
                        val hasRequestedBefore = viewModel.hasRequestedNotificationPermission()

                        if (!shouldShowRationale && hasRequestedBefore) {
                            viewModel.openNotificationSettings()
                            viewModel.onNotificationPermissionResult()
                        } else {
                            viewModel.markNotificationPermissionRequested()
                            notificationPermissionLauncher.launch(permission)
                        }
                    } else {
                        notificationPermissionLauncher.launch(permission)
                    }
                },
                onDismiss = {
                    viewModel.onSkipNotificationPermission()
                }
            )
        }
        StartupState.RequestingBatteryOptimization -> {
            BatteryOptimizationDialog(
                onConfirm = {
                    try {
                        val intent = PowerOptimizationHelper.createRequestIgnoreBatteryOptimizationsIntent(context)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        TtsLogger.e("Failed to start direct request intent, falling back to settings list", e, "MainScreen")
                        try {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            scope.launch {
                                snackbarHostState.showSnackbar("无法打开电池优化设置页面，请手动去系统设置中开启")
                            }
                        }
                    }
                    viewModel.onBatteryOptimizationResult()
                },
                onDismiss = {
                    viewModel.onBatteryOptimizationSkipped()
                }
            )
        }
        is StartupState.UpdateAvailable -> {
            val updateInfo = (startupState as StartupState.UpdateAvailable).updateInfo
            UpdateDialog(
                updateInfo = updateInfo,
                onDismiss = { viewModel.onUpdateDialogDismissed() },
                onRemindLater = { viewModel.onUpdateDialogDismissed() }
            )
        }
        else -> { /* 其他状态无需弹窗 */ }
    }
}

@Composable
fun DefaultProviderBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.SettingsSuggest,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.default_provider_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.default_provider_banner_content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun AboutPageHintBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.about_page_hint_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.about_page_hint_banner_content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}
