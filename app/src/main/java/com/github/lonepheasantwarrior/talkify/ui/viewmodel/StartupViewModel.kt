package com.github.lonepheasantwarrior.talkify.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
 * 启动流程 ViewModel
 *
 * 负责编排应用启动时的各种检查任务：
 * 1. 网络连通性 (阻塞性)
 * 2. 通知权限 (引导性)
 * 3. 电池优化 (引导性)
 * 4. 应用更新 (非阻塞性)
 */
class StartupViewModel(application: Application) : AndroidViewModel(application) {

    private val logTag = "StartupViewModel"
    private val context = application

    private val appConfigRepository: AppConfigRepository by lazy {
        SharedPreferencesAppConfigRepository(context)
    }
    private val updateChecker by lazy { UpdateChecker() }

    private val _uiState = MutableStateFlow<StartupState>(StartupState.CheckingNetwork)
    val uiState: StateFlow<StartupState> = _uiState.asStateFlow()

    private val _isConfigSheetOpen = MutableStateFlow(false)
    val isConfigSheetOpen: StateFlow<Boolean> = _isConfigSheetOpen.asStateFlow()

    init {
        // ViewModel 初始化时自动开始检查流程
        startStartupSequence()
    }

    /**
     * 开始启动检查序列
     * 可以从外部（例如从设置页返回）重新触发
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

    // --- 步骤 1: 网络检查 ---
    private suspend fun checkNetworkStep() {
        _uiState.value = StartupState.CheckingNetwork
        TtsLogger.d(logTag) { "Step 1: Checking Network..." }

        if (!PermissionChecker.hasInternetPermission(context)) {
            // 理论上 Manifest 声明了就有，除非是特殊系统。这里简单处理为网络阻塞。
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
        // 无论授权与否，只要用户做出了选择，就继续下一步
        // 如果拒绝，只要没勾选"不再询问"（系统层面），下次启动还会检查。
        // 如果想实现"拒绝后不再问"，可以在这里记录 skipped。
        // 当前逻辑是：只有点击弹窗的"以后再说"才算 skipped。
        // 点击系统权限框的拒绝不算 skipped，下次还弹。这符合逻辑。
        checkBatteryStep()
    }

    fun onSkipNotificationPermission() {
        // 用户点击"以后再说"，不保存跳过状态，下次启动继续检查
        checkBatteryStep()
    }

    fun onBatteryOptimizationResult() {
        // 用户从电池设置页返回（或点击了去设置），继续下一步（检查更新）
        // 这里不强求结果，下次启动再检查
        checkUpdateStep()
    }
    
    fun onBatteryOptimizationSkipped() {
        // 电池优化弹窗点击了"以后再说" -> (虽然我们取消了持久化，但这里的动作意味着关闭弹窗继续流程)
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

    fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Fallback to generic settings if notification settings fail
            openSystemSettings()
        }
    }
}
