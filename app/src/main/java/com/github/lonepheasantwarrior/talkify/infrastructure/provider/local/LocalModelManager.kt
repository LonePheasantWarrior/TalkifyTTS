package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

import android.app.ActivityManager
import android.content.Context
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.ModelDownloadStatus
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import java.io.File

/**
 * 本地模型生命周期管理器（单例）
 *
 * 核心职责：
 * 1. 模型状态查询（已下载 / 下载中 / 未下载）
 * 2. 模型完整性校验（检查所有必需文件是否就位）
 * 3. 跨进程下载状态同步（通过 SharedPreferences）
 *
 * 存储路径结构：
 * ```
 * {externalFilesDir}/tts_models/
 * ├── vits-zh-aishell3/
 * │   └── deployed/          ← 下载完成的文件直接存放于此
 * │       ├── model.onnx
 * │       ├── tokens.txt
 * │       └── lexicon.txt
 * ├── vits-cantonese-hf-xiaomai/
 * │   └── deployed/
 * │       ├── model.onnx
 * │       ├── tokens.txt
 * │       └── lexicon.txt
 * └── kokoro-82m/
 *     └── deployed/
 *         ├── model.onnx
 *         ├── tokens.txt
 *         └── voices.bin
 * ```
 */
object LocalModelManager {

    private const val TAG = "LocalModelManager"
    private const val MODELS_ROOT = "tts_models"

    /** 历史磁盘子目录名，勿改（已下载用户的模型文件都在该目录下） */
    private const val DEPLOYED_DIR = "deployed"

    /** 历史文件名/键名，勿改（兼容跨进程下载状态数据） */
    private const val PREFS_NAME = "talkify_local_model_state"
    private const val KEY_DOWNLOADING_MODEL = "downloading_model_id"

    /**
     * 获取模型存储根目录
     *
     * @return 根目录 File 对象，Context 不可用时返回 null
     */
    fun getModelsRootDir(): File? {
        val context = TalkifyAppHolder.getContext() ?: run {
            TtsLogger.w("Context not available, cannot get models root dir", tag = TAG)
            return null
        }
        return File(context.getExternalFilesDir(null), MODELS_ROOT)
    }

    /**
     * 获取指定模型的下载目录（存放已下载的模型文件）
     *
     * 磁盘子目录名仍为历史路径 `deployed/`，勿改（兼容已下载用户）。
     */
    fun getModelDownloadedDir(modelId: String): File? {
        return getModelsRootDir()?.let { File(it, "$modelId/$DEPLOYED_DIR") }
    }

    /**
     * 获取指定模型的根目录
     */
    private fun getModelDir(modelId: String): File? {
        return getModelsRootDir()?.let { File(it, modelId) }
    }

    /**
     * 获取模型当前下载状态
     *
     * 查询逻辑：
     * 1. 优先检查是否有正在进行的下载任务
     * 2. 然后检查下载目录中所有必需文件是否存在且非空
     *
     * @param modelId 模型 ID
     * @return 模型下载状态
     */
    fun getModelStatus(modelId: String): ModelDownloadStatus {
        // 优先检查下载中状态（跨进程共享）
        if (getDownloadingModelId() == modelId) {
            return ModelDownloadStatus.DOWNLOADING
        }
        // 检查文件是否完整
        return if (isModelDownloaded(modelId)) {
            ModelDownloadStatus.DOWNLOADED
        } else {
            ModelDownloadStatus.NOT_DOWNLOADED
        }
    }

    /**
     * 检查模型是否已下载（文件完整）
     *
     * 校验注册表中定义的所有必需文件是否在下载目录中存在且非空
     *
     * @param modelId 模型 ID
     * @return true 如果所有必需文件都存在且非空
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val modelInfo = LocalModelRegistry.getModel(modelId) ?: return false
        val downloadedDir = getModelDownloadedDir(modelId) ?: return false
        if (!downloadedDir.exists() || !downloadedDir.isDirectory) return false

        return modelInfo.downloadFileInfo.values.all { fileName ->
            val file = File(downloadedDir, fileName)
            file.exists() && file.length() > 0
        }
    }

    // ---- 下载状态跨进程同步 ----

    /**
     * 设置当前正在下载的模型 ID
     * 通过 SharedPreferences 实现跨进程共享（Service / Activity 均可访问）
     */
    fun setDownloadingModelId(modelId: String?) {
        val context = TalkifyAppHolder.getContext() ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (modelId != null) {
            prefs.edit().putString(KEY_DOWNLOADING_MODEL, modelId).apply()
        } else {
            prefs.edit().remove(KEY_DOWNLOADING_MODEL).apply()
        }
    }

    /**
     * 获取当前正在下载的模型 ID
     *
     * 包含活性校验：如果标记存在但下载 Service 已不在运行
     * （应用被杀死或进程回收），自动清除僵尸状态并返回 null。
     */
    fun getDownloadingModelId(): String? {
        val context = TalkifyAppHolder.getContext() ?: return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_DOWNLOADING_MODEL, null)
        if (value.isNullOrBlank()) return null

        // 活性校验：确认下载 Service 仍在运行，防止僵尸状态
        if (!isDownloadServiceRunning(context)) {
            TtsLogger.w("Download service not running, clearing stale state: $value", tag = TAG)
            prefs.edit().remove(KEY_DOWNLOADING_MODEL).apply()
            return null
        }

        return value
    }

    /**
     * 检查下载 Service 是否正在运行
     */
    private fun isDownloadServiceRunning(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val serviceClass = LocalModelDownloadService::class.java.name
            manager.getRunningServices(Int.MAX_VALUE)
                ?.any { it.service.className == serviceClass }
                ?: false
        } catch (e: Exception) {
            TtsLogger.w("Failed to check service status: ${e.message}", tag = TAG)
            // 无法判断时保守处理：假定正在下载，避免重复启动
            true
        }
    }

    /**
     * 检查是否有模型正在下载
     */
    fun isAnyModelDownloading(): Boolean = getDownloadingModelId() != null

    /**
     * 检查指定模型是否正在下载
     */
    fun isModelDownloading(modelId: String): Boolean = getDownloadingModelId() == modelId
}
