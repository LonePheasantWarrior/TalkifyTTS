package com.github.lonepheasantwarrior.talkify.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateCheckResult
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.NetworkConnectivityChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.PermissionChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.power.PowerOptimizationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.update.UpdateChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelDownloadService
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelManager
import com.github.lonepheasantwarrior.talkify.service.TalkifyTtsDemoService
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载进度状态
 */
data class DownloadProgress(
    val modelId: String,
    val displayName: String,
    val progress: Int,       // 0-100
    val isCompleted: Boolean
)

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
    private var currentDemoProviderId: String? = null

    private val _isDemoPlaying = MutableStateFlow(false)
    val isDemoPlaying: StateFlow<Boolean> = _isDemoPlaying.asStateFlow()

    private val _demoErrorMessage = MutableStateFlow<String?>(null)
    val demoErrorMessage: StateFlow<String?> = _demoErrorMessage.asStateFlow()

    private val _isDefaultProvider = MutableStateFlow(true)
    val isDefaultProvider: StateFlow<Boolean> = _isDefaultProvider.asStateFlow()

    // --- 下载进度状态 ---
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val modelId = intent.getStringExtra(LocalModelDownloadService.EXTRA_MODEL_ID) ?: return
            val modelInfo = LocalModelRegistry.getModel(modelId) ?: return
            when (intent.action) {
                LocalModelDownloadService.ACTION_DOWNLOAD_COMPLETED -> {
                    _downloadProgress.value = DownloadProgress(
                        modelId = modelId,
                        displayName = modelInfo.displayName,
                        progress = 100,
                        isCompleted = true
                    )
                }
                LocalModelDownloadService.ACTION_DOWNLOAD_FAILED -> {
                    _downloadProgress.value = null
                }
            }
        }
    }

    init {
        // 注册下载广播接收器
        val filter = IntentFilter().apply {
            addAction(LocalModelDownloadService.ACTION_DOWNLOAD_COMPLETED)
            addAction(LocalModelDownloadService.ACTION_DOWNLOAD_FAILED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, filter)
        }

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

    fun playDemo(providerId: String, text: String, config: BaseProviderConfig) {
        if (demoService == null || currentDemoProviderId != providerId) {
            TtsLogger.d(logTag) { "Initializing Demo Service for provider: $providerId" }
            demoService?.release()
            demoService = TalkifyTtsDemoService(providerId).apply {
                setStateListener { state, errorMessage ->
                    _isDemoPlaying.value = state == TalkifyTtsDemoService.STATE_PLAYING
                    if (state == TalkifyTtsDemoService.STATE_ERROR) {
                        _demoErrorMessage.value = errorMessage
                    }
                }
            }
            currentDemoProviderId = providerId
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

    /**
     * 启动本地模型下载
     *
     * @param modelId 要下载的模型 ID
     * @return null 表示启动成功，否则返回冲突提示信息
     */
    fun startModelDownload(modelId: String): String? {
        val downloadingId = LocalModelManager.getDownloadingModelId()
        if (downloadingId != null) {
            return if (downloadingId == modelId) {
                // 相同模型已在下载中
                context.getString(R.string.model_downloading_busy, LocalModelRegistry.getModel(modelId)?.displayName ?: modelId)
            } else {
                // 不同模型正在下载
                context.getString(R.string.model_another_downloading)
            }
        }

        val modelInfo = LocalModelRegistry.getModel(modelId) ?: return null
        _downloadProgress.value = DownloadProgress(
            modelId = modelId,
            displayName = modelInfo.displayName,
            progress = 0,
            isCompleted = false
        )
        val intent = Intent(context, LocalModelDownloadService::class.java).apply {
            putExtra(LocalModelDownloadService.EXTRA_MODEL_ID, modelId)
        }
        context.startForegroundService(intent)
        return null
    }

    /**
     * 检查本地模型是否可播放（未被下载占用）
     * @return null 表示可以播放，否则返回冲突提示信息
     */
    fun checkLocalModelPlayable(modelId: String?): String? {
        if (modelId == null) return null
        val downloadingId = LocalModelManager.getDownloadingModelId()
        if (downloadingId != null && downloadingId == modelId) {
            return context.getString(R.string.model_downloading_busy, LocalModelRegistry.getModel(modelId)?.displayName ?: modelId)
        }
        return null
    }

    /**
     * 清除下载进度
     */
    fun clearDownloadProgress() {
        _downloadProgress.value = null
    }

    override fun onCleared() {
        super.onCleared()
        TtsLogger.d(logTag) { "ViewModel cleared, releasing resources" }
        try {
            context.unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {}
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
        checkDefaultProvider()
    }

    fun refreshDefaultProviderStatus() {
        checkDefaultProvider()
    }

    private fun checkDefaultProvider() {
        viewModelScope.launch {
            val isDefault = withContext(Dispatchers.IO) {
                try {
                    val tts = android.speech.tts.TextToSpeech(context, null)
                    val providerName = tts.defaultEngine
                    tts.shutdown()

                    TtsLogger.d(logTag) { "Default TTS provider: $providerName" }

                    val talkifyPackageName = "com.github.lonepheasantwarrior.talkify"
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