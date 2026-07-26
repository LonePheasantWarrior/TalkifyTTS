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
 * 1. 模型状态查询（已部署 / 下载中 / 未下载 / 错误）
 * 2. 模型完整性校验（检查所有必需文件是否就位）
 * 3. 模型卸载（递归删除模型目录）
 * 4. 磁盘用量统计
 * 5. 跨进程下载状态同步（通过 SharedPreferences）
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
    private const val DEPLOYED_DIR = "deployed"
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
     * 获取指定模型的部署目录（存放已下载的模型文件）
     */
    fun getModelDeployedDir(modelId: String): File? {
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
     * 2. 然后检查部署目录中所有必需文件是否存在且非空
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
        return if (isModelDeployed(modelId)) {
            ModelDownloadStatus.DEPLOYED
        } else {
            ModelDownloadStatus.NOT_DOWNLOADED
        }
    }

    /**
     * 检查模型是否已部署
     *
     * 校验注册表中定义的所有必需文件是否在部署目录中存在且非空
     *
     * @param modelId 模型 ID
     * @return true 如果所有必需文件都存在且非空
     */
    fun isModelDeployed(modelId: String): Boolean {
        val modelInfo = LocalModelRegistry.getModel(modelId) ?: return false
        val deployedDir = getModelDeployedDir(modelId) ?: return false
        if (!deployedDir.exists() || !deployedDir.isDirectory) return false

        return modelInfo.downloadFileInfo.values.all { fileName ->
            val file = File(deployedDir, fileName)
            file.exists() && file.length() > 0
        }
    }

    /**
     * 卸载模型
     *
     * 递归删除整个模型目录及其下所有文件。
     * 卸载前应确保引擎已释放对该模型的引用。
     *
     * @param modelId 模型 ID
     * @return true 如果卸载成功
     */
    fun uninstallModel(modelId: String): Boolean {
        val modelDir = getModelDir(modelId)
        if (modelDir == null) {
            TtsLogger.w("Cannot access model directory for uninstall: $modelId", tag = TAG)
            return false
        }
        if (!modelDir.exists()) {
            TtsLogger.d("Model directory does not exist, nothing to uninstall: $modelId", tag = TAG)
            return true
        }

        val success = modelDir.deleteRecursively()
        if (success) {
            TtsLogger.i("Model uninstalled successfully: $modelId", tag = TAG)
            // 清理下载状态
            clearDownloadingModelId()
        } else {
            TtsLogger.e("Failed to uninstall model: $modelId", tag = TAG)
        }
        return success
    }

    /**
     * 获取模型占用的磁盘空间（字节）
     *
     * @param modelId 模型 ID
     * @return 模型目录总大小，模型不存在时返回 0
     */
    fun getModelDiskUsage(modelId: String): Long {
        return getModelDir(modelId)?.let { dir ->
            if (dir.exists()) calculateDirSize(dir) else 0L
        } ?: 0L
    }

    /**
     * 计算所有已部署模型的总磁盘占用
     */
    fun getTotalDiskUsage(): Long {
        return LocalModelRegistry.ALL_MODELS.sumOf { getModelDiskUsage(it.id) }
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

    /**
     * 清除正在下载的模型 ID
     */
    private fun clearDownloadingModelId() {
        setDownloadingModelId(null)
    }

    // ---- 内部工具方法 ----

    /**
     * 递归计算目录大小
     */
    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateDirSize(file)
            } else {
                file.length()
            }
        }
        return size
    }

    /**
     * 获取易读的磁盘大小字符串
     */
    fun formatDiskUsage(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }
}
