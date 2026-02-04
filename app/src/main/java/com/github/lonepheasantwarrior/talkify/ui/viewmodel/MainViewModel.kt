package com.github.lonepheasantwarrior.talkify.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateCheckResult
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.NetworkConnectivityChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.PermissionChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.power.PowerOptimizationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.update.UpdateChecker
import com.github.lonepheasantwarrior.talkify.service.TalkifyTtsDemoService
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动流程状态机
 */
sealed class StartupState {
    data object CheckingNetwork : StartupState()
    data object NetworkBlocked : StartupState()
    data object CheckingNotification : StartupState()
    data object RequestingNotification : StartupState()
    data object CheckingBattery : StartupState()
    data object RequestingBatteryOptimization : StartupState()
    data object CheckingUpdate : StartupState()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : StartupState()
    data object Completed : StartupState()
}

/**
 * 主界面 ViewModel
 *
 * 负责：
 * 1. 应用启动时的检查流程（网络、权限、更新等）
 * 2. 管理主界面的 TTS 试听功能（Demo）
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logTag = "MainViewModel"
    private val context = application

    private val appConfigRepository: AppConfigRepository by lazy {
        SharedPreferencesAppConfigRepository(context)
    }
    private val updateChecker by lazy { UpdateChecker() }

    // --- 启动流程状态 ---
    private val _uiState = MutableStateFlow<StartupState>(StartupState.CheckingNetwork)
    val uiState: StateFlow<StartupState> = _uiState.asStateFlow()

    // --- 配置面板状态 ---
    private val _isConfigSheetOpen = MutableStateFlow(false)
    val isConfigSheetOpen: StateFlow<Boolean> = _isConfigSheetOpen.asStateFlow()

    // --- Demo 试听状态 ---
    private var demoService: TalkifyTtsDemoService? = null
    private var currentDemoEngineId: String? = null

    private val _isDemoPlaying = MutableStateFlow(false)
    val isDemoPlaying: StateFlow<Boolean> = _isDemoPlaying.asStateFlow()

    private val _demoErrorMessage = MutableStateFlow<String?>(null)
    val demoErrorMessage: StateFlow<String?> = _demoErrorMessage.asStateFlow()

    private val _isDefaultEngine = MutableStateFlow(true)
    val isDefaultEngine: StateFlow<Boolean> = _isDefaultEngine.asStateFlow()

    init {
        // ViewModel 初始化时自动开始检查流程
        startStartupSequence()
    }

    /**
     * 开始启动检查序列
     */
    fun startStartupSequence() {
        viewModelScope.launch {
            checkNetworkStep()
        }
    }

    fun openConfigSheet() {
        _isConfigSheetOpen.value = true
    }

    fun closeConfigSheet() {
        _isConfigSheetOpen.value = false
    }

    // --- Demo 功能 ---

    fun playDemo(engineId: String, text: String, config: BaseEngineConfig) {
        if (demoService == null || currentDemoEngineId != engineId) {
            TtsLogger.d(logTag) { "Initializing Demo Service for engine: $engineId" }
            demoService?.release()
            demoService = TalkifyTtsDemoService(engineId).apply {
                setStateListener { state, errorMessage ->
                    _isDemoPlaying.value = state == TalkifyTtsDemoService.STATE_PLAYING
                    if (state == TalkifyTtsDemoService.STATE_ERROR) {
                        _demoErrorMessage.value = errorMessage
                    }
                }
            }
            currentDemoEngineId = engineId
        }

        // 清除之前的错误信息
        _demoErrorMessage.value = null
        demoService?.speak(text, config)
    }

    fun stopDemo() {
        demoService?.stop()
    }

    fun clearDemoError() {
        _demoErrorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        TtsLogger.d(logTag) { "ViewModel cleared, releasing resources" }
        demoService?.release()
        demoService = null
    }

    // --- 启动流程实现 (步骤 1: 网络检查) ---
    private suspend fun checkNetworkStep() {
        _uiState.value = StartupState.CheckingNetwork
        TtsLogger.d(logTag) { "Step 1: Checking Network..." }

        if (!PermissionChecker.hasInternetPermission(context)) {
            TtsLogger.w(logTag) { "No internet permission" }
            _uiState.value = StartupState.NetworkBlocked
            return
        }

        val canAccess = withContext(Dispatchers.IO) {
            NetworkConnectivityChecker.canAccessInternet(context)
        }

        if (canAccess) {
            TtsLogger.i(logTag) { "Network accessible." }
            checkNotificationStep()
        } else {
            TtsLogger.w(logTag) { "Network unavailable." }
            _uiState.value = StartupState.NetworkBlocked
        }
    }

    // --- 步骤 2: 通知权限 ---
    private fun checkNotificationStep() {
        _uiState.value = StartupState.CheckingNotification
        TtsLogger.d(logTag) { "Step 2: Checking Notification Permission..." }

        val hasPermission = PermissionChecker.hasNotificationPermission(context)

        if (!hasPermission) {
            TtsLogger.i(logTag) { "Need to request notification permission." }
            _uiState.value = StartupState.RequestingNotification
        } else {
            TtsLogger.i(logTag) { "Notification permission check passed (Granted)." }
            checkBatteryStep()
        }
    }

    // --- 步骤 3: 电池优化 ---
    private fun checkBatteryStep() {
        _uiState.value = StartupState.CheckingBattery
        TtsLogger.d(logTag) { "Step 3: Checking Battery Optimization..." }

        val isIgnoring = PowerOptimizationHelper.isIgnoringBatteryOptimizations(context)

        if (!isIgnoring) {
            TtsLogger.i(logTag) { "Need to request battery optimization." }
            _uiState.value = StartupState.RequestingBatteryOptimization
        } else {
            TtsLogger.i(logTag) { "Battery optimization check passed." }
            checkUpdateStep()
        }
    }

    // --- 步骤 4: 检查更新 ---
    private fun checkUpdateStep() {
        _uiState.value = StartupState.CheckingUpdate
        TtsLogger.d(logTag) { "Step 4: Checking Updates..." }

        viewModelScope.launch {
            try {
                val currentVersion = getCurrentAppVersion()
                val result = withContext(Dispatchers.IO) {
                    updateChecker.checkForUpdates(currentVersion)
                }

                if (result is UpdateCheckResult.UpdateAvailable) {
                    TtsLogger.i(logTag) { "Update available: ${result.updateInfo.versionName}" }
                    _uiState.value = StartupState.UpdateAvailable(result.updateInfo)
                } else {
                    TtsLogger.i(logTag) { "No update available or check failed: $result" }
                    finishStartup()
                }
            } catch (e: Exception) {
                TtsLogger.e("Error checking updates", e, logTag)
                finishStartup()
            }
        }
    }

    private fun finishStartup() {
        TtsLogger.i(logTag) { "Startup sequence completed." }
        _uiState.value = StartupState.Completed
        checkDefaultEngine()
    }

    fun refreshDefaultEngineStatus() {
        checkDefaultEngine()
    }

    private fun checkDefaultEngine() {
        viewModelScope.launch {
            val isDefault = withContext(Dispatchers.IO) {
                try {
                    val tts = android.speech.tts.TextToSpeech(context, null)
                    val engineName = tts.defaultEngine
                    tts.shutdown()

                    TtsLogger.d(logTag) { "Default TTS engine: $engineName" }

                    val talkifyPackageName = "com.github.lonepheasantwarrior.talkify"
                    engineName == talkifyPackageName || engineName?.contains("talkify") == true
                } catch (e: Exception) {
                    TtsLogger.e("Failed to get default TTS engine", e, logTag)
                    false
                }
            }
            _isDefaultEngine.value = isDefault
            TtsLogger.i(logTag) { "Talkify is default engine: $isDefault" }
        }
    }

    // --- 用户交互回调 ---

    fun onNetworkRetry() {
        startStartupSequence()
    }

    fun hasRequestedNotificationPermission(): Boolean {
        return appConfigRepository.hasRequestedNotificationPermission()
    }

    fun markNotificationPermissionRequested() {
        appConfigRepository.setHasRequestedNotificationPermission(true)
    }

    fun onNotificationPermissionResult() {
        checkBatteryStep()
    }

    fun onSkipNotificationPermission() {
        checkBatteryStep()
    }

    fun onBatteryOptimizationResult() {
        checkUpdateStep()
    }
    
    fun onBatteryOptimizationSkipped() {
        checkUpdateStep()
    }

    fun onUpdateDialogDismissed() {
        finishStartup()
    }

    // --- 辅助方法 ---
    private fun getCurrentAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
    
    fun openSystemSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            TtsLogger.e("Failed to open settings", e, logTag)
        }
    }

    fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            TtsLogger.e("Failed to open TTS settings", e, logTag)
            openSystemSettings()
        }
    }

    fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openSystemSettings()
        }
    }
}