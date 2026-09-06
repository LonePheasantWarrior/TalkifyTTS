package com.github.lonepheasantwarrior.talkify.ui.viewmodel.startup

import android.app.Application
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateCheckResult
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.NetworkConnectivityChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.PermissionChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.power.PowerOptimizationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.update.UpdateChecker
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
 * 启动检查协调器
 *
 * 负责冷启动串行检查流程（网络 → 通知权限 → 电池优化 → 更新检查）
 * 与默认 TTS 供应商检测，输出 [StartupState] 状态机。
 */
class StartupCoordinator(
    private val application: Application,
    private val scope: kotlinx.coroutines.CoroutineScope
) {

    private val logTag = "StartupCoordinator"

    private val appConfigRepository: AppConfigRepository by lazy {
        SharedPreferencesAppConfigRepository(application)
    }
    private val updateChecker by lazy { UpdateChecker() }

    private val _uiState = MutableStateFlow<StartupState>(StartupState.CheckingNetwork)
    val uiState: StateFlow<StartupState> = _uiState.asStateFlow()

    init {
        startStartupSequence()
    }

    /**
     * 开始启动检查序列
     */
    fun startStartupSequence() {
        scope.launch {
            checkNetworkStep()
        }
    }

    // --- 启动流程实现 (步骤 1: 网络检查) ---
    private suspend fun checkNetworkStep() {
        _uiState.value = StartupState.CheckingNetwork
        TtsLogger.d(logTag) { "Step 1: Checking Network..." }

        if (!PermissionChecker.hasInternetPermission(application)) {
            TtsLogger.w(logTag) { "No internet permission" }
            _uiState.value = StartupState.NetworkBlocked
            return
        }

        val canAccess = withContext(Dispatchers.IO) {
            NetworkConnectivityChecker.canAccessInternet(application)
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

        val hasPermission = PermissionChecker.hasNotificationPermission(application)

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

        val isIgnoring = PowerOptimizationHelper.isIgnoringBatteryOptimizations(application)

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

        scope.launch {
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
        checkDefaultProvider()
    }

    // --- 默认供应商检测 ---

    fun refreshDefaultProviderStatus() {
        checkDefaultProvider()
    }

    private fun checkDefaultProvider() {
        scope.launch {
            val isDefault = withContext(Dispatchers.IO) {
                try {
                    val tts = android.speech.tts.TextToSpeech(application, null)
                    val providerName = tts.defaultEngine
                    tts.shutdown()

                    TtsLogger.d(logTag) { "Default TTS provider: $providerName" }

                    val talkifyPackageName = application.packageName
                    providerName == talkifyPackageName || providerName?.contains("talkify") == true
                } catch (e: Exception) {
                    TtsLogger.e("Failed to get default TTS provider", e, logTag)
                    false
                }
            }
            _isDefaultProvider.value = isDefault
            TtsLogger.i(logTag) { "Talkify is default provider: $isDefault" }
        }
    }

    private val _isDefaultProvider = MutableStateFlow(true)
    val isDefaultProvider: StateFlow<Boolean> = _isDefaultProvider.asStateFlow()

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
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
