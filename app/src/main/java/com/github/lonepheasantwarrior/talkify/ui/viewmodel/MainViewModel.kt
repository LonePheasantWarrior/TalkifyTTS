package com.github.lonepheasantwarrior.talkify.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.localmodel.DownloadProgress
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.localmodel.LocalModelDownloadController
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.preview.PreviewPlaybackController
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.startup.StartupCoordinator
import com.github.lonepheasantwarrior.talkify.ui.viewmodel.startup.StartupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主界面 ViewModel —— 组合根
 *
 * 本身不承载业务状态，仅将三个独立状态域组合后暴露给 MainScreen：
 * - [StartupCoordinator]：启动检查状态机（网络/权限/电池/更新/默认供应商检测）
 * - [PreviewPlaybackController]：语音预览播放
 * - [LocalModelDownloadController]：本地模型下载进度与冲突管理
 *
 * 供应商配置面板开关与系统设置跳转属于界面编排职责，保留在此处。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logTag = "MainViewModel"
    private val context = application

    private val startup = StartupCoordinator(application, viewModelScope)
    private val previewPlayback = PreviewPlaybackController()
    private val modelDownload = LocalModelDownloadController(application)

    // --- 启动流程状态（委托 StartupCoordinator）---
    val startupState: StateFlow<StartupState> = startup.startupState
    val isDefaultProvider: StateFlow<Boolean> = startup.isDefaultProvider

    // --- 语音预览状态（委托 PreviewPlaybackController）---
    val isPreviewPlaying: StateFlow<Boolean> = previewPlayback.isPreviewPlaying
    val previewErrorMessage: StateFlow<String?> = previewPlayback.previewErrorMessage

    // --- 下载进度状态（委托 LocalModelDownloadController）---
    val downloadProgress: StateFlow<DownloadProgress?> = modelDownload.downloadProgress

    // --- 配置面板状态 ---
    private val _isConfigSheetOpen = MutableStateFlow(false)
    val isConfigSheetOpen: StateFlow<Boolean> = _isConfigSheetOpen.asStateFlow()

    fun openConfigSheet() {
        _isConfigSheetOpen.value = true
    }

    fun closeConfigSheet() {
        _isConfigSheetOpen.value = false
    }

    // --- 委托：启动流程 ---

    fun refreshDefaultProviderStatus() = startup.refreshDefaultProviderStatus()

    fun hasRequestedNotificationPermission(): Boolean =
        startup.hasRequestedNotificationPermission()

    fun markNotificationPermissionRequested() =
        startup.markNotificationPermissionRequested()

    fun onNotificationPermissionResult() = startup.onNotificationPermissionResult()

    fun onSkipNotificationPermission() = startup.onSkipNotificationPermission()

    fun onBatteryOptimizationResult() = startup.onBatteryOptimizationResult()

    fun onBatteryOptimizationSkipped() = startup.onBatteryOptimizationSkipped()

    fun onUpdateDialogDismissed() = startup.onUpdateDialogDismissed()

    // --- 委托：语音预览 ---

    fun playPreview(providerId: String, text: String, config: BaseProviderConfig) =
        previewPlayback.playPreview(providerId, text, config)

    fun stopPreview() = previewPlayback.stopPreview()

    fun clearPreviewError() = previewPlayback.clearPreviewError()

    // --- 委托：本地模型下载 ---

    fun startModelDownload(modelId: String): String? = modelDownload.startModelDownload(modelId)

    fun checkLocalModelPlayable(modelId: String?): String? =
        modelDownload.checkLocalModelPlayable(modelId)

    fun clearDownloadProgress() = modelDownload.clearDownloadProgress()

    // --- 系统设置跳转 ---

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

    override fun onCleared() {
        super.onCleared()
        TtsLogger.d(logTag) { "ViewModel cleared, releasing resources" }
        modelDownload.unregister()
        previewPlayback.release()
    }
}
