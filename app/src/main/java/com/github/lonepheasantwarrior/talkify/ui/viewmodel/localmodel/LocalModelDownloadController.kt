package com.github.lonepheasantwarrior.talkify.ui.viewmodel.localmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelDownloadService
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * 本地模型下载控制器
 *
 * 负责下载进度状态、下载完成/失败广播的接收，
 * 以及下载冲突检测（同一时刻仅允许一个下载任务）。
 */
class LocalModelDownloadController(
    private val application: Application
) {

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
        val filter = IntentFilter().apply {
            addAction(LocalModelDownloadService.ACTION_DOWNLOAD_COMPLETED)
            addAction(LocalModelDownloadService.ACTION_DOWNLOAD_FAILED)
        }
        // 应用内私有广播，统一声明 NOT_EXPORTED（ContextCompat 兼容 API 33 以下）
        androidx.core.content.ContextCompat.registerReceiver(
            application, downloadReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
                application.getString(R.string.model_downloading_busy, LocalModelRegistry.getModel(modelId)?.displayName ?: modelId)
            } else {
                // 不同模型正在下载
                application.getString(R.string.model_another_downloading)
            }
        }

        val modelInfo = LocalModelRegistry.getModel(modelId) ?: return null
        _downloadProgress.value = DownloadProgress(
            modelId = modelId,
            displayName = modelInfo.displayName,
            progress = 0,
            isCompleted = false
        )
        val intent = Intent(application, LocalModelDownloadService::class.java).apply {
            putExtra(LocalModelDownloadService.EXTRA_MODEL_ID, modelId)
        }
        application.startForegroundService(intent)
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
            return application.getString(R.string.model_downloading_busy, LocalModelRegistry.getModel(modelId)?.displayName ?: modelId)
        }
        return null
    }

    /**
     * 清除下载进度
     */
    fun clearDownloadProgress() {
        _downloadProgress.value = null
    }

    fun unregister() {
        try {
            application.unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {
        }
    }
}
