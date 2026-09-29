package com.github.lonepheasantwarrior.talkify.ui.viewmodel.startup

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateCheckResult
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.NetworkConnectivityChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.PermissionChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.power.PowerOptimizationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppActionTracker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppPageTracker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.update.UpdateChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelManager
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** 网络不可用；offlineCapable 表示任一本地模型已下载（可离线合成），弹窗文案据此自适应 */
    data class NetworkBlocked(val offlineCapable: Boolean) : StartupState()
    data object CheckingNotificationPermission : StartupState()
    data object RequestingNotificationPermission : StartupState()
    data object CheckingBatteryOptimization : StartupState()
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
    private val scope: CoroutineScope
) {

    companion object {
        private const val LOG_TAG = "StartupCoordinator"
    }

    private val logTag = LOG_TAG

    private val appConfigRepository: AppConfigRepository by lazy {
        SharedPreferencesAppConfigRepository(application)
    }
    private val updateChecker by lazy { UpdateChecker() }

    private val _startupState = MutableStateFlow<StartupState>(StartupState.CheckingNetwork)
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    private val _isDefaultProvider = MutableStateFlow(true)
    val isDefaultProvider: StateFlow<Boolean> = _isDefaultProvider.asStateFlow()

    /** 当前启动序列 Job：重复触发（如从系统设置返回）时先取消旧序列，防止状态机交错 */
    private var sequenceJob: Job? = null

    /** 默认供应商检测 Job：防重复并发 */
    private var defaultProviderJob: Job? = null

    init {
        startStartupSequence()
    }

    /**
     * 开始启动检查序列
     */
    fun startStartupSequence() {
        // 取消在飞序列：反复开关网络面板返回会触发多次重查，
        // 旧序列的滞后状态写入会把状态机拉回过期阶段
        sequenceJob?.cancel()
        sequenceJob = scope.launch {
            checkNetworkStep()
        }
    }

    // --- 启动流程实现 (步骤 1: 网络检查) ---
    private suspend fun checkNetworkStep() {
        _startupState.value = StartupState.CheckingNetwork
        TtsLogger.d(logTag) { "Step 1: Checking Network..." }

        if (!PermissionChecker.hasInternetPermission(application)) {
            TtsLogger.w(logTag) { "No internet permission" }
            enterNetworkBlockedState()
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
            enterNetworkBlockedState()
        }
    }

    /** 进入网络阻断状态，并检测本地模型就绪情况以决定离线可用性（弹窗文案自适应） */
    private suspend fun enterNetworkBlockedState() {
        val offlineCapable = withContext(Dispatchers.IO) {
            LocalModelRegistry.ALL_MODELS.any { LocalModelManager.isModelDownloaded(it.id) }
        }
        TtsLogger.i(logTag) { "Network blocked, offline capable: $offlineCapable" }
        // 弹窗展示 = "去了哪里"，按虚拟路由上报 pageview；offline_capable 由按钮动作事件承载
        AppPageTracker.open(AppPageTracker.PATH_NETWORK_BLOCKED, "NetworkBlocked")
        _startupState.value = StartupState.NetworkBlocked(offlineCapable)
    }

    // --- 步骤 2: 通知权限 ---
    private fun checkNotificationStep() {
        _startupState.value = StartupState.CheckingNotificationPermission
        TtsLogger.d(logTag) { "Step 2: Checking Notification Permission..." }

        val hasPermission = PermissionChecker.hasNotificationPermission(application)

        if (!hasPermission) {
            TtsLogger.i(logTag) { "Need to request notification permission." }
            AppPageTracker.open(AppPageTracker.PATH_NOTIFICATION_PERMISSION, "NotificationPermission")
            _startupState.value = StartupState.RequestingNotificationPermission
        } else {
            TtsLogger.i(logTag) { "Notification permission check passed (Granted)." }
            checkBatteryStep()
        }
    }

    // --- 步骤 3: 电池优化 ---
    private fun checkBatteryStep() {
        _startupState.value = StartupState.CheckingBatteryOptimization
        TtsLogger.d(logTag) { "Step 3: Checking Battery Optimization..." }

        val isIgnoring = PowerOptimizationHelper.isIgnoringBatteryOptimizations(application)

        if (!isIgnoring) {
            TtsLogger.i(logTag) { "Need to request battery optimization." }
            AppPageTracker.open(AppPageTracker.PATH_BATTERY_OPTIMIZATION, "BatteryOptimization")
            _startupState.value = StartupState.RequestingBatteryOptimization
        } else {
            TtsLogger.i(logTag) { "Battery optimization check passed." }
            checkUpdateStep()
        }
    }

    // --- 步骤 4: 检查更新 ---
    private fun checkUpdateStep() {
        _startupState.value = StartupState.CheckingUpdate
        TtsLogger.d(logTag) { "Step 4: Checking Updates..." }

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val currentVersion = getCurrentAppVersion()
                val result = withContext(Dispatchers.IO) {
                    updateChecker.checkForUpdates(currentVersion)
                }
                AppActionTracker.updateCheck(
                    AppActionTracker.TRIGGER_STARTUP,
                    result,
                    (SystemClock.elapsedRealtime() - startedAt).toInt()
                )

                if (result is UpdateCheckResult.UpdateAvailable) {
                    TtsLogger.i(logTag) { "Update available: ${result.updateInfo.versionName}" }
                    AppPageTracker.open(AppPageTracker.PATH_UPDATE, "Update")
                    _startupState.value = StartupState.UpdateAvailable(result.updateInfo)
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
        _startupState.value = StartupState.Completed
        checkDefaultProvider()
    }

    // --- 默认供应商检测 ---

    fun refreshDefaultProviderStatus() {
        checkDefaultProvider()
    }

    private fun checkDefaultProvider() {
        // 防重复并发：快速进出前台可能连续触发
        defaultProviderJob?.cancel()
        defaultProviderJob = scope.launch {
            val isDefault = withContext(Dispatchers.IO) {
                try {
                    // 直接读系统默认 TTS 引擎设置项：与 TextToSpeech.getDefaultEngine()
                    // 同源（后者内部即读该值），但无需绑定并初始化目标 TTS 引擎服务
                    val systemDefaultEngine = Settings.Secure.getString(
                        application.contentResolver,
                        Settings.Secure.TTS_DEFAULT_SYNTH
                    )

                    TtsLogger.d(logTag) { "Default TTS engine: $systemDefaultEngine" }

                    val talkifyPackageName = application.packageName
                    systemDefaultEngine == talkifyPackageName || systemDefaultEngine?.contains("talkify") == true
                } catch (e: Exception) {
                    TtsLogger.e("Failed to get default TTS provider", e, logTag)
                    false
                }
            }
            _isDefaultProvider.value = isDefault
            TtsLogger.i(logTag) { "Talkify is default provider: $isDefault" }
        }
    }

    // --- 用户交互回调 ---

    /** 用户在网络阻断弹窗点击"知道了"：本地模型可离线使用（或用户已知悉后果），继续后续启动检查 */
    fun onNetworkBlockedAcknowledged() {
        TtsLogger.i(logTag) { "Network blocked acknowledged, continuing startup sequence." }
        checkNotificationStep()
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
            // minSdk 30：PackageInfoFlags 重载仅 API 33+ 存在，低版本走旧 int 重载
            @Suppress("DEPRECATION")
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.packageManager.getPackageInfo(
                    application.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                application.packageManager.getPackageInfo(application.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
