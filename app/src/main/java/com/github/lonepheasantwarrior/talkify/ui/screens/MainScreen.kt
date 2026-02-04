package com.github.lonepheasantwarrior.talkify.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.EngineIds
import com.github.lonepheasantwarrior.talkify.domain.model.Qwen3TtsConfig
import com.github.lonepheasantwarrior.talkify.domain.model.SeedTts2Config
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngineRegistry
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.power.PowerOptimizationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsVoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.SeedTts2ConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.SeedTts2VoiceRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.ui.components.BatteryOptimizationDialog
import com.github.lonepheasantwarrior.talkify.ui.components.ConfigBottomSheet
import com.github.lonepheasantwarrior.talkify.ui.components.EngineSelector
import com.github.lonepheasantwarrior.talkify.ui.components.NetworkBlockedDialog
import com.github.lonepheasantwarrior.talkify.ui.components.NotificationPermissionDialog
import com.github.lonepheasantwarrior.talkify.ui.components.UpdateDialog
import com.github.lonepheasantwarrior.talkify.ui.components.VoicePreview
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.MainViewModel
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.StartupState
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- 启动流程状态管理 ---
    val startupState by viewModel.uiState.collectAsState()
    val isDefaultEngine by viewModel.isDefaultEngine.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && startupState == StartupState.Completed) {
                viewModel.refreshDefaultEngineStatus()
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

    // 根据当前引擎获取对应的声音仓储
    fun getVoiceRepository(engineId: String): VoiceRepository {
        return TtsEngineFactory.createVoiceRepository(engineId, context)
            ?: Qwen3TtsVoiceRepository(context)
    }

    // 根据当前引擎获取对应的配置仓储
    fun getConfigRepository(engineId: String): EngineConfigRepository {
        return TtsEngineFactory.createConfigRepository(engineId, context)
            ?: Qwen3TtsConfigRepository(context)
    }

    val appConfigRepository: AppConfigRepository = remember {
        SharedPreferencesAppConfigRepository(context)
    }

    val availableEngines = TtsEngineRegistry.availableEngines
    val defaultEngine = TtsEngineRegistry.defaultEngine

    var currentEngine by remember {
        mutableStateOf(defaultEngine)
    }

    // Demo Service logic moved to ViewModel

    LaunchedEffect(appConfigRepository) {
        val savedEngineId = appConfigRepository.getSelectedEngineId()
        if (savedEngineId != null) {
            TtsEngineRegistry.getEngine(savedEngineId)?.let { engine ->
                currentEngine = engine
            }
        } else {
            appConfigRepository.saveSelectedEngineId(defaultEngine.id)
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

    var availableVoices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf<VoiceInfo?>(null) }

    val sampleTexts = stringArrayResource(R.array.texts)
    val defaultInputText = remember(sampleTexts) {
        sampleTexts.random()
    }
    var inputText by remember { mutableStateOf(defaultInputText) }
    val isConfigSheetOpen by viewModel.isConfigSheetOpen.collectAsState()

    var savedConfig by remember(currentEngine.id) {
        mutableStateOf(getConfigRepository(currentEngine.id).getConfig(currentEngine.id))
    }

    LaunchedEffect(currentEngine) {
        savedConfig = getConfigRepository(currentEngine.id).getConfig(currentEngine.id)
        val voices = getVoiceRepository(currentEngine.id).getVoicesForEngine(currentEngine)
        availableVoices = voices
        selectedVoice = availableVoices.find { it.voiceId == savedConfig.voiceId } ?: voices.firstOrNull()
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
                    Column {
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
            AnimatedVisibility(
                visible = !isDefaultEngine && startupState == StartupState.Completed,
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it })
            ) {
                 DefaultEngineBanner(
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
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))

                        EngineSelector(
                            currentEngine = currentEngine,
                            availableEngines = availableEngines,
                            onEngineSelected = { engine ->
                                currentEngine = engine
                                appConfigRepository.saveSelectedEngineId(engine.id)
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
                                    is Qwen3TtsConfig -> {
                                        val qwenConfig = savedConfig as? Qwen3TtsConfig ?: Qwen3TtsConfig()
                                        qwenConfig.copy(voiceId = selectedVoice?.voiceId ?: qwenConfig.voiceId)
                                    }
                                    is SeedTts2Config -> {
                                        val seedConfig = savedConfig as? SeedTts2Config ?: SeedTts2Config()
                                        seedConfig.copy(voiceId = selectedVoice?.voiceId ?: seedConfig.voiceId)
                                    }
                                    else -> savedConfig
                                }

                                val isConfigured = when (config) {
                                    is Qwen3TtsConfig -> config.apiKey.isNotBlank()
                                    is SeedTts2Config -> config.apiKey.isNotBlank()
                                    else -> false
                                }

                                if (!isConfigured) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("请先完成引擎配置")
                                    }
                                    viewModel.openConfigSheet()
                                    return@VoicePreview
                                }

                                viewModel.playDemo(currentEngine.id, inputText, config)
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
        isOpen = isConfigSheetOpen,
        onDismiss = { viewModel.closeConfigSheet() },
        currentEngine = currentEngine,
        configRepository = getConfigRepository(currentEngine.id),
        voiceRepository = getVoiceRepository(currentEngine.id),
        onConfigSaved = {
            savedConfig = getConfigRepository(currentEngine.id).getConfig(currentEngine.id)
        }
    )

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
fun DefaultEngineBanner(
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.default_engine_banner_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.default_engine_banner_content),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}
