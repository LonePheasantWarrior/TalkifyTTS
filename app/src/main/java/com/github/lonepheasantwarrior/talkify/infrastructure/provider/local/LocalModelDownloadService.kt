package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.lonepheasantwarrior.talkify.MainActivity
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地模型下载前台服务
 *
 * 在前台 Service 中执行模型文件下载，展示实时进度通知。
 * 支持取消操作，下载完成后进行完整性校验（文件存在性 + MD5）；
 * 默认源失败时自动切换备用下载源。
 *
 * 启动方式：
 * ```kotlin
 * val intent = Intent(context, LocalModelDownloadService::class.java).apply {
 *     putExtra(EXTRA_MODEL_ID, "zipvoice_distill")
 * }
 * context.startForegroundService(intent)
 * ```
 */
class LocalModelDownloadService : Service() {

    // ==================== 常量 ====================

    companion object {
        const val TAG = "LocalModelDownload"

        /** Intent Extra: 要下载的模型 ID */
        const val EXTRA_MODEL_ID = "model_id"

        /** 取消下载的广播 Action */
        const val ACTION_CANCEL = "com.github.lonepheasantwarrior.talkify.CANCEL_DOWNLOAD"

        /** 下载完成广播 Action */
        const val ACTION_DOWNLOAD_COMPLETED = "com.github.lonepheasantwarrior.talkify.DOWNLOAD_COMPLETED"

        /** 下载失败广播 Action */
        const val ACTION_DOWNLOAD_FAILED = "com.github.lonepheasantwarrior.talkify.DOWNLOAD_FAILED"

        /** 通知 ID */
        private const val NOTIFICATION_ID = 2001

        /** 通知通道 ID */
        const val CHANNEL_ID = "talkify_model_download"
        private const val CHANNEL_NAME = "模型下载"
        private const val CHANNEL_DESC = "显示模型下载进度"

        /** OkHttp 超时配置 */
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 120L
        private const val WRITE_TIMEOUT = 30L
    }

    // ==================== 内部状态 ====================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private val isCancelled = AtomicBoolean(false)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ==================== Service 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏"取消"动作经 PendingIntent.getForegroundService 显式直达此处。
        // 不再使用广播：通知上的 PendingIntent 由系统进程代为发送，部分 ROM 会
        // 丢弃发往 RECEIVER_NOT_EXPORTED 接收器的自定义广播，导致取消按钮失效。
        if (intent?.action == ACTION_CANCEL) {
            TtsLogger.i("Download cancelled by user", tag = TAG)
            isCancelled.set(true)
            // 不在此处 stopSelf：让下载循环命中取消分支，统一走
            // onDownloadCancelled（清理文件/移除通知/停止服务）
            return START_NOT_STICKY
        }

        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        if (modelId.isNullOrBlank()) {
            TtsLogger.e("No model ID provided, stopping service", tag = TAG)
            stopSelf()
            return START_NOT_STICKY
        }

        val modelInfo = LocalModelRegistry.getModel(modelId)
        if (modelInfo == null) {
            TtsLogger.e("Unknown model ID: $modelId", tag = TAG)
            stopSelf()
            return START_NOT_STICKY
        }

        // 设为前台服务
        val notification = buildProgressNotification(modelInfo.displayName, 0)
        startForeground(NOTIFICATION_ID, notification)

        // 标记下载状态
        LocalModelManager.setDownloadingModelId(modelId)
        isCancelled.set(false)

        // 启动下载任务
        downloadJob = scope.launch {
            downloadModel(modelInfo)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isCancelled.set(true)
        downloadJob?.cancel()
        scope.cancel()
        // 清除下载状态标记，防止进程被杀后残留僵尸状态
        LocalModelManager.setDownloadingModelId(null)
        super.onDestroy()
    }

    // ==================== 下载逻辑 ====================

    /**
     * 下载模型全部文件
     *
     * 下载策略：
     * 1. 为每个 URL 生成候选源链（见 [buildCandidateUrls]），依次尝试直到成功
     * 2. 全部候选失败才判定该文件下载失败
     */
    private suspend fun downloadModel(modelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo) {
        val modelDir = LocalModelManager.getModelDownloadedDir(modelInfo.id)
        if (modelDir == null) {
            onDownloadFailed(modelInfo, "无法创建模型目录")
            return
        }
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            onDownloadFailed(modelInfo, "创建模型目录失败")
            return
        }

        // 清理旧文件
        modelDir.listFiles()?.forEach { it.delete() }

        val urlEntries = modelInfo.downloadFileInfo.entries.toList()
        val totalFiles = urlEntries.size
        var totalDownloaded = 0L
        val totalSize = modelInfo.downloadSizeBytes

        TtsLogger.i("Starting download: ${modelInfo.id}, $totalFiles files, ~${modelInfo.downloadSizeDisplay}", tag = TAG)

        updateProgressNotification(modelInfo.displayName, 0)

        for ((index, entry) in urlEntries.withIndex()) {
            if (isCancelled.get()) {
                onDownloadCancelled(modelInfo, modelDir)
                return
            }

            val url = entry.key
            val fileName = entry.value
            val targetFile = File(modelDir, fileName)

            TtsLogger.d("Downloading file ${index + 1}/$totalFiles: $fileName", tag = TAG)

            val success = tryDownloadWithCandidates(url, targetFile, modelInfo.displayName, totalDownloaded, totalSize)

            if (!success) {
                if (isCancelled.get()) {
                    onDownloadCancelled(modelInfo, modelDir)
                } else {
                    onDownloadFailed(modelInfo, "文件下载失败: $fileName")
                    cleanupPartialFiles(modelDir)
                }
                return
            }

            totalDownloaded += targetFile.length()
        }

        // ========== 阶段切换：下载归档资源（espeak-ng-data 等） ==========
        if (modelInfo.archiveAssets.isNotEmpty()) {
            TtsLogger.i("Starting archive download phase: ${modelInfo.archiveAssets.size} archives", tag = TAG)
            for ((archiveUrl, subDir) in modelInfo.archiveAssets) {
                if (isCancelled.get()) {
                    onDownloadCancelled(modelInfo, modelDir)
                    return
                }

                val archiveFile = File(modelDir, "temp_archive.tar.bz2")
                try {
                    TtsLogger.i("Downloading archive: $archiveUrl", tag = TAG)
                    if (!tryDownloadWithCandidates(archiveUrl, archiveFile, modelInfo.displayName, totalDownloaded, totalSize)) {
                        if (isCancelled.get()) {
                            onDownloadCancelled(modelInfo, modelDir)
                        } else {
                            onDownloadFailed(modelInfo, "归档资源下载失败: $archiveUrl")
                            cleanupPartialFiles(modelDir)
                        }
                        return
                    }

                    totalDownloaded += archiveFile.length()

                    TtsLogger.i("Extracting archive to: $subDir", tag = TAG)
                    val targetDir = if (subDir.isEmpty()) modelDir else File(modelDir, subDir)
                    if (!extractTarBz2(archiveFile, targetDir)) {
                        if (isCancelled.get()) {
                            onDownloadCancelled(modelInfo, modelDir)
                        } else {
                            onDownloadFailed(modelInfo, "归档资源解压失败: $subDir")
                            cleanupPartialFiles(modelDir)
                        }
                        return
                    }
                } finally {
                    // 始终清理临时归档文件
                    archiveFile.delete()
                }
            }
        }

        // ========== 阶段切换：归档布局归一化 ==========
        if (modelInfo.archiveAssets.isNotEmpty()) {
            if (!normalizeArchiveLayout(modelDir, modelInfo)) {
                onDownloadFailed(modelInfo, "归档解压布局异常，缺少必需文件")
                cleanupPartialFiles(modelDir)
                return
            }
        }

        // ========== 阶段切换：下载完成 → 完整性校验 ==========
        TtsLogger.i("All files downloaded for ${modelInfo.id}, starting integrity verification", tag = TAG)
        updateVerifyingNotification(modelInfo.displayName)

        // 验证所有文件完整性（downloadFileInfo + archiveAssets 解压产物）
        if (!verifyDownloadedFiles(modelDir, urlEntries, modelInfo.requiredLocalFiles)) {
            onDownloadFailed(modelInfo, "模型文件校验失败，部分文件不完整")
            cleanupPartialFiles(modelDir)
            return
        }

        // MD5 校验（占位，后续补充真实 MD5 值后启用）
        if (modelInfo.md5.isNotBlank()) {
            if (!verifyModelIntegrity(modelDir, modelInfo)) {
                onDownloadFailed(modelInfo, "MD5 校验失败，模型文件可能已损坏")
                cleanupPartialFiles(modelDir)
                return
            }
        }

        // 完整性校验通过
        onDownloadCompleted(modelInfo)
    }

    /**
     * 依次尝试 URL 候选源链，直到某个源下载成功
     *
     * @return true 任一候选源下载成功，false 全部失败
     */
    private fun tryDownloadWithCandidates(
        url: String,
        targetFile: File,
        displayName: String,
        baseDownloaded: Long,
        totalSize: Long
    ): Boolean {
        val candidates = buildCandidateUrls(url)
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) {
                TtsLogger.w("Candidate ${index + 1}/${candidates.size}: $candidate", tag = TAG)
            }
            if (tryDownloadFile(candidate, targetFile, displayName, baseDownloaded, totalSize)) {
                return true
            }
            if (isCancelled.get()) return false
        }
        return false
    }

    /**
     * 为下载 URL 生成候选源链
     *
     * 国内直连 GitHub Releases 大概率连接重置或仅有百 KB 级速率，
     * 因此 GitHub 资源优先走公共加速代理（community 加速服务，可用性会随时间波动），
     * 失败后逐级回退；HF 资源保持「hf-mirror 镜像 → HF 原始源」的原有策略。
     */
    private fun buildCandidateUrls(url: String): List<String> {
        return when {
            url.startsWith("https://github.com/") -> listOf(
                "https://ghfast.top/$url",
                "https://gh-proxy.com/$url",
                url
            )
            url.startsWith(LocalModelRegistry.DEFAULT_HF_MIRROR) -> listOf(
                url,
                url.replace(
                    LocalModelRegistry.DEFAULT_HF_MIRROR,
                    LocalModelRegistry.FALLBACK_HF_ORIGIN
                )
            )
            else -> listOf(url)
        }
    }

    /**
     * 尝试下载单个文件
     *
     * @return true 下载成功，false 失败
     */
    private fun tryDownloadFile(
        url: String,
        targetFile: File,
        displayName: String,
        baseDownloaded: Long,
        totalSize: Long
    ): Boolean {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TalkifyTTS/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                TtsLogger.e("HTTP ${response.code} for: $url", tag = TAG)
                response.close()
                return false
            }

            response.body?.source()?.use { source ->
                targetFile.sink().buffer().use { sink ->
                    var downloaded = 0L
                    val contentLength = response.body?.contentLength() ?: -1L
                    // 通知节流：系统对通知更新有约 5 次/秒的限流，逐块回调会被整批丢弃并刷爆日志
                    var lastNotifyAt = 0L

                    while (!source.exhausted() && !isCancelled.get()) {
                        val bytesRead = source.read(sink.buffer, 8192)
                        if (bytesRead == -1L) break
                        sink.emit()
                        downloaded += bytesRead

                        // 更新进度（最多约 1 秒一次）
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastNotifyAt >= 1000) {
                            lastNotifyAt = now
                            val currentTotal = baseDownloaded + downloaded
                            val progress = if (totalSize > 0) {
                                ((currentTotal * 100) / totalSize).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            updateProgressNotification(displayName, progress)
                        }
                    }

                    sink.flush()
                }
            }

            response.close()
            // 取消时半截文件不能算成功，否则取消会被误判为下载完成
            if (isCancelled.get()) {
                TtsLogger.i("Download interrupted by user cancel", tag = TAG)
                return false
            }
            return targetFile.exists() && targetFile.length() > 0
        } catch (e: IOException) {
            TtsLogger.e("Download error: ${e.message}", tag = TAG)
            return false
        }
    }

    /**
     * 归一化归档解压布局
     *
     * 部分官方 tarball（如 sherpa-onnx-zipvoice-distill-*）内含一层与压缩包同名的
     * 顶层目录，解压后必需文件位于该 wrapper 目录而非模型根目录，直接校验必然失败。
     * 检测逻辑：若必需文件不在根目录，但恰好都位于唯一的顶层目录内，则将该目录
     * 内容整体上移一层。必需文件本就在根目录的归档（如 espeak-ng-data.tar.bz2）不受影响。
     *
     * @return true 归一化后必需文件全部就位，false 布局无法修复
     */
    private fun normalizeArchiveLayout(
        modelDir: File,
        modelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
    ): Boolean {
        val required = modelInfo.requiredLocalFiles
        if (required.isEmpty()) return true

        fun allPresentAt(base: File): Boolean = required.all {
            val f = File(base, it)
            f.exists() && f.length() > 0
        }

        if (allPresentAt(modelDir)) return true

        val topDirs = modelDir.listFiles()?.filter { it.isDirectory } ?: return false
        if (topDirs.size != 1 || !allPresentAt(topDirs[0])) {
            TtsLogger.e(
                "Archive layout unexpected: root missing required files, top dirs=${topDirs.map { it.name }}",
                tag = TAG
            )
            return false
        }

        val wrapper = topDirs[0]
        var allMoved = true
        wrapper.listFiles()?.forEach { child ->
            if (!child.renameTo(File(modelDir, child.name))) {
                TtsLogger.e("Failed to move ${child.name} out of wrapper dir", tag = TAG)
                allMoved = false
            }
        }
        if (allMoved) {
            wrapper.delete()
            TtsLogger.i("Flattened archive wrapper dir: ${wrapper.name}", tag = TAG)
        }
        return allMoved && allPresentAt(modelDir)
    }

    /**
     * 解压 tar.bz2 归档文件到目标目录
     *
     * @param archiveFile tar.bz2 归档文件
     * @param targetDir 解压目标目录
     * @return true 解压成功，false 失败
     */
    private fun extractTarBz2(archiveFile: File, targetDir: File): Boolean {
        try {
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                TtsLogger.e("Failed to create target directory: ${targetDir.absolutePath}", tag = TAG)
                return false
            }

            val bz2Stream = BZip2CompressorInputStream(archiveFile.inputStream().buffered())
            val tarStream = TarArchiveInputStream(bz2Stream)

            bz2Stream.use { _bz2 ->
                tarStream.use { tar ->
                    var currentEntry = tar.nextEntry
                    while (currentEntry != null) {
                        if (isCancelled.get()) return false

                        val entry = currentEntry  // 局部 val 供 smart cast
                        val outputFile = File(targetDir, entry.name)

                        // 防止 Zip Slip 攻击：确保解压路径在目标目录内
                        if (!outputFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator) &&
                            outputFile.canonicalPath != targetDir.canonicalPath
                        ) {
                            TtsLogger.w("Skipping entry with unsafe path: ${entry.name}", tag = TAG)
                            currentEntry = tar.nextEntry
                            continue
                        }

                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            outputFile.outputStream().buffered().use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (tar.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                }
                            }
                        }

                        currentEntry = tar.nextEntry
                    }
                }
            }
            return true
        } catch (e: Exception) {
            TtsLogger.e("Archive extraction error: ${e.message}", throwable = e, tag = TAG)
            return false
        }
    }

    /**
     * 校验模型完整性（MD5）
     */
    private fun verifyModelIntegrity(
        modelDir: File,
        modelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
    ): Boolean {
        try {
            TtsLogger.d("Verifying MD5 for model: ${modelInfo.id}", tag = TAG)
            val digest = MessageDigest.getInstance("MD5")

            modelInfo.downloadFileInfo.values.forEach { fileName ->
                val file = File(modelDir, fileName)
                if (!file.exists()) return false

                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
            }

            val computed = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = modelInfo.md5.lowercase()

            TtsLogger.d("MD5: expected=$expected, computed=$computed", tag = TAG)
            return computed == expected
        } catch (e: Exception) {
            TtsLogger.e("MD5 verification error", throwable = e, tag = TAG)
            return false
        }
    }

    /**
     * 快速校验已下载文件：所有文件必须存在且非空
     *
     * @param entries downloadFileInfo 的 URL→文件名映射项
     * @param requiredLocalFiles 解压产物等不在 downloadFileInfo 中的必需文件（相对路径）
     */
    private fun verifyDownloadedFiles(
        modelDir: File,
        entries: List<Map.Entry<String, String>>,
        requiredLocalFiles: List<String> = emptyList()
    ): Boolean {
        for ((_, fileName) in entries) {
            val file = File(modelDir, fileName)
            if (!file.exists() || file.length() <= 0) {
                TtsLogger.e("File verification failed: $fileName (exists=${file.exists()}, size=${file.length()})", tag = TAG)
                return false
            }
        }
        for (fileName in requiredLocalFiles) {
            val file = File(modelDir, fileName)
            if (!file.exists() || file.length() <= 0) {
                TtsLogger.e("Required file verification failed: $fileName (exists=${file.exists()}, size=${file.length()})", tag = TAG)
                return false
            }
        }
        return true
    }

    // ==================== 通知管理 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = CHANNEL_DESC
            channel.setShowBadge(false)
            // 静音通知通道，避免下载进度更新时反复响铃
            channel.setSound(null, null)
            val manager = getSystemService(android.app.NotificationManager::class.java)
            // 删除旧通道以应用新的 importance 等级（通道创建后不可修改）
            manager.deleteNotificationChannel(CHANNEL_ID)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(displayName: String, progress: Int): android.app.Notification {
        val cancelIntent = PendingIntent.getForegroundService(
            this,
            0,
            Intent(this, LocalModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = getString(R.string.model_downloading_title, displayName)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$progress%")
            .setSmallIcon(R.drawable.ic_tts_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .addAction(0, getString(android.R.string.cancel), cancelIntent)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildVerifyingNotification(displayName: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.model_downloading_verifying_title, displayName))
            .setContentText(getString(R.string.model_downloading_verifying_hint))
            .setSmallIcon(R.drawable.ic_tts_notification)
            .setOngoing(true)
            .setProgress(0, 0, true)  // 不确定进度条（旋转菊花）
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateProgressNotification(displayName: String, progress: Int) {
        val notification = buildProgressNotification(displayName, progress)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun updateVerifyingNotification(displayName: String) {
        val notification = buildVerifyingNotification(displayName)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    // ==================== 结果处理 ====================

    private fun onDownloadCompleted(modelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo) {
        TtsLogger.i("Download completed: ${modelInfo.id}", tag = TAG)
        LocalModelManager.setDownloadingModelId(null)

        // 显示完成通知
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.model_download_complete, modelInfo.displayName))
            .setContentText(getString(R.string.model_download_complete_hint))
            .setSmallIcon(R.drawable.ic_tts_notification)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)

        // 发送完成广播
        sendBroadcast(Intent(ACTION_DOWNLOAD_COMPLETED).apply {
            putExtra(EXTRA_MODEL_ID, modelInfo.id)
            setPackage(packageName)
        })

        stopForeground(STOP_FOREGROUND_DETACH)
        // 延迟停止 Service，确保通知已渲染到系统
        scope.launch {
            kotlinx.coroutines.delay(500)
            stopSelf()
        }
    }

    private fun onDownloadFailed(
        modelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo,
        reason: String
    ) {
        TtsLogger.e("Download failed: ${modelInfo.id}, reason: $reason", tag = TAG)
        LocalModelManager.setDownloadingModelId(null)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.model_download_failed_title))
            .setContentText(reason)
            .setSmallIcon(R.drawable.ic_tts_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)

        sendBroadcast(Intent(ACTION_DOWNLOAD_FAILED).apply {
            putExtra(EXTRA_MODEL_ID, modelInfo.id)
            putExtra("error", reason)
            setPackage(packageName)
        })

        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun onDownloadCancelled(
        modelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo,
        modelDir: File
    ) {
        TtsLogger.i("Download cancelled: ${modelInfo.id}", tag = TAG)
        LocalModelManager.setDownloadingModelId(null)
        cleanupPartialFiles(modelDir)

        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupPartialFiles(dir: File) {
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
