package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.os.SystemClock
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateCheckResult

/**
 * 应用动作事件埋点（语义层）
 *
 * 事件名、属性 schema 与枚举词表收敛在本组件内；新增动作埋点先在此登记，
 * 再到业务侧调用。打开界面容器不是事件，走 [AppPageTracker.surfaceOpened]；
 * 整页导航走 [ScreenTracker]。
 *
 * **隐私红线**：
 * - 只携带枚举值、ID 类别、计数与时长；不携带用户输入、API Key、URL、代理地址
 * - 自定义模型/音色一律归为 `custom`，不传原始 ID（见 [TelemetryIds]）
 * - 崩溃只带异常类名链与应用代码栈帧签名，不带异常消息、原始线程名或请求内容
 */
object AppActionTracker {

    // ==================== 事件名 ====================

    const val EVENT_APP_FOREGROUNDED = "app_foregrounded"
    const val EVENT_PREVIEW_PLAY_CLICK = "preview_play_click"
    const val EVENT_PREVIEW_PLAYBACK = "preview_playback"
    const val EVENT_PREVIEW_VOICE_SELECTED = "preview_voice_selected"
    const val EVENT_PROVIDER_SWITCHED = "provider_switched"
    const val EVENT_CONFIG_SAVED = "config_saved"
    const val EVENT_MODEL_DOWNLOAD_DIALOG = "model_download_dialog"
    const val EVENT_MODEL_DOWNLOAD_START = "model_download_start"
    const val EVENT_MODEL_DOWNLOAD_RESULT = "model_download_result"
    const val EVENT_UPDATE_CHECK = "update_check"
    const val EVENT_UPDATE_DIALOG_ACTION = "update_dialog_action"
    const val EVENT_DONATE_CHANNEL_CLICK = "donate_channel_click"
    const val EVENT_DONATE_QR_SAVED = "donate_qr_saved"
    const val EVENT_EXTERNAL_LINK_OPEN = "external_link_open"
    const val EVENT_QQ_GROUP_COPIED = "qq_group_copied"
    const val EVENT_STARTUP_NETWORK_ACTION = "startup_network_action"
    const val EVENT_NOTIFICATION_PERMISSION = "notification_permission"
    const val EVENT_BATTERY_OPTIMIZATION = "battery_optimization"
    const val EVENT_SETTINGS_JUMP = "settings_jump"
    const val EVENT_APP_CRASH = "app_crash"
    const val EVENT_RX_SUPPRESSED = "rx_suppressed"

    // ==================== 词表 ====================

    const val OUTCOME_STARTED = "started"
    const val OUTCOME_EMPTY_TEXT = "empty_text"
    const val OUTCOME_NOT_CONFIGURED = "not_configured"
    const val OUTCOME_MODEL_NEED_DOWNLOAD = "model_need_download"
    const val OUTCOME_MODEL_DOWNLOADING = "model_downloading"

    const val SOURCE_CONFIG_SHEET = "config_sheet"
    const val SOURCE_PREVIEW = "preview"
    const val TRIGGER_STARTUP = "startup"
    const val TRIGGER_MANUAL = "manual"

    const val FOREGROUND_COLD = "cold"
    const val FOREGROUND_RESUME = "resume"

    const val STATUS_SUCCESS = "success"
    const val STATUS_STOPPED = "stopped"
    const val STATUS_ERROR = "error"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"

    const val ACTION_CONFIRM = "confirm"
    const val ACTION_DISMISS = "dismiss"
    const val ACTION_UPDATE_NOW = "update_now"
    const val ACTION_REMIND_LATER = "remind_later"
    const val ACTION_OPEN_SETTINGS = "open_settings"
    const val ACTION_ACKNOWLEDGED = "acknowledged"
    const val ACTION_GO_SETTINGS = "go_settings"
    const val ACTION_SKIPPED = "skipped"
    const val ACTION_REQUESTED = "requested"
    const val ACTION_GRANTED = "granted"
    const val ACTION_DENIED = "denied"

    const val RESULT_SUCCESS = "success"
    const val RESULT_FAILED = "failed"

    const val RESULT_UPDATE_AVAILABLE = "update_available"
    const val RESULT_NO_UPDATE = "no_update"
    const val RESULT_TIMEOUT = "timeout"
    const val RESULT_NETWORK_ERROR = "network_error"
    const val RESULT_SERVER_ERROR = "server_error"
    const val RESULT_PARSE_ERROR = "parse_error"

    const val TARGET_TTS = "tts"
    const val TARGET_NOTIFICATION = "notification"

    const val TARGET_GITHUB = "github"
    const val TARGET_PRIVACY_POLICY = "privacy_policy"

    const val SOURCE_DEFAULT_BANNER = "default_banner"
    const val SOURCE_PERMISSION_DIALOG = "permission_dialog"

    const val CHANNEL_WECHAT = "wechat"
    const val CHANNEL_ALIPAY = "alipay"

    const val URL_ABOUT = ScreenTracker.PATH_ABOUT

    // ==================== 前台会话 ====================

    /**
     * 每次应用进入前台的上报（不参与页面浏览量）
     *
     * @param foregroundSource cold / resume：从系统设置返回等场景属于 resume，
     *   不能计为冷启动
     * @param deviceInfo 匿名设备画像（键值均为 String / Int）
     */
    fun appForegrounded(
        foregroundSource: String,
        deviceInfo: Map<String, Any>,
        permit: CollectionPermit? = null,
    ) {
        val properties = linkedMapOf<String, Any>("foreground_source" to foregroundSource)
        properties.putAll(deviceInfo)
        track(EVENT_APP_FOREGROUNDED, properties, origin = TelemetryOrigin.APP, permit = permit)
    }

    // ==================== 预览播放 ====================

    fun previewPlayClick(
        providerId: String,
        voiceId: TelemetryIds.ClassifiedId,
        textLength: Int,
        outcome: String,
    ) {
        track(
            EVENT_PREVIEW_PLAY_CLICK,
            linkedMapOf(
                "provider_id" to providerId,
                "voice_id" to voiceId.value,
                "voice_category" to voiceId.category,
                "text_length" to textLength,
                "outcome" to outcome,
            )
        )
    }

    fun previewVoiceSelected(providerId: String, voiceId: TelemetryIds.ClassifiedId) {
        track(
            EVENT_PREVIEW_VOICE_SELECTED,
            linkedMapOf(
                "provider_id" to providerId,
                "voice_id" to voiceId.value,
                "voice_category" to voiceId.category,
            )
        )
    }

    fun beginPreviewPlayback(
        providerId: String,
        modelId: TelemetryIds.ClassifiedId,
        voiceId: TelemetryIds.ClassifiedId,
        textLength: Int,
    ): PreviewAttempt = PreviewAttempt(providerId, modelId, voiceId, textLength)

    class PreviewAttempt internal constructor(
        private val providerId: String,
        private val modelId: TelemetryIds.ClassifiedId,
        private val voiceId: TelemetryIds.ClassifiedId,
        private val textLength: Int,
    ) {
        private val permit = TelemetryController.capturePermit()
        private val startUptimeMs = SystemClock.elapsedRealtime()

        @Volatile
        private var firstAudioUptimeMs = 0L

        @Volatile
        private var firstAudioSampleRate = 0

        @Volatile
        private var status: String? = null

        @Volatile
        private var errorCode: String? = null

        @Volatile
        private var reported = false

        private var endUptimeMs = 0L

        fun markFirstAudio(sampleRate: Int) {
            if (firstAudioUptimeMs == 0L) {
                firstAudioUptimeMs = SystemClock.elapsedRealtime()
                firstAudioSampleRate = sampleRate
            }
        }

        fun markSuccess() = mark(STATUS_SUCCESS)
        fun markStopped() = mark(STATUS_STOPPED)

        fun markError(code: String) {
            errorCode = code.take(64).ifBlank { "unknown" }
            mark(STATUS_ERROR)
        }

        /** 幂等上报；未标记终态时静默跳过（预览可能在拿到终态前被 release） */
        fun report() {
            synchronized(this) {
                if (reported) return
                val finalStatus = status ?: return
                reported = true
                if (!TelemetryController.isPermitCurrent(permit)) return
                val durationMs = (endUptimeMs - startUptimeMs).toInt()
                val properties = linkedMapOf<String, Any>(
                    "provider_id" to providerId,
                    "model_id" to modelId.value,
                    "model_category" to modelId.category,
                    "voice_id" to voiceId.value,
                    "voice_category" to voiceId.category,
                    "text_length" to textLength,
                    "status" to finalStatus,
                    "duration_ms" to durationMs,
                    "duration_bucket" to durationBucket(durationMs),
                )
                errorCode?.let { properties["error_code"] = it }
                if (firstAudioUptimeMs > 0L) {
                    properties["first_audio_ms"] = (firstAudioUptimeMs - startUptimeMs).toInt()
                    properties["sample_rate"] = firstAudioSampleRate
                }
                track(EVENT_PREVIEW_PLAYBACK, properties, permit = permit)
            }
        }

        private fun mark(finalStatus: String) {
            synchronized(this) {
                if (status != null) return
                status = finalStatus
                endUptimeMs = SystemClock.elapsedRealtime()
            }
        }
    }

    // ==================== 供应商与配置 ====================

    fun providerSwitched(fromProviderId: String, toProviderId: String) {
        if (fromProviderId == toProviderId) return
        track(
            EVENT_PROVIDER_SWITCHED,
            linkedMapOf(
                "from_provider" to fromProviderId,
                "to_provider" to toProviderId,
            )
        )
    }

    fun configSaved(
        providerId: String,
        isLocalModel: Boolean,
        hasCustomApiUrl: Boolean,
        hasCustomModelId: Boolean,
        voiceId: TelemetryIds.ClassifiedId,
    ) {
        track(
            EVENT_CONFIG_SAVED,
            linkedMapOf(
                "provider_id" to providerId,
                "is_local_model" to bool(isLocalModel),
                "has_custom_api_url" to bool(hasCustomApiUrl),
                "has_custom_model_id" to bool(hasCustomModelId),
                "voice_id" to voiceId.value,
                "voice_category" to voiceId.category,
            )
        )
    }

    // ==================== 模型下载 ====================

    fun modelDownloadDialog(modelId: String, sizeDisplay: String, source: String, confirmed: Boolean) {
        val properties = linkedMapOf<String, Any>(
            "model_id" to modelId,
            "source" to source,
            "action" to if (confirmed) ACTION_CONFIRM else ACTION_DISMISS,
        )
        if (sizeDisplay.isNotBlank()) properties["size_display"] = sizeDisplay
        track(EVENT_MODEL_DOWNLOAD_DIALOG, properties)
    }

    fun modelDownloadStart(modelId: String, fileCount: Int, totalSizeMb: Int) {
        track(
            EVENT_MODEL_DOWNLOAD_START,
            linkedMapOf(
                "model_id" to modelId,
                "file_count" to fileCount,
                "total_size_mb" to totalSizeMb,
            ),
            url = ScreenTracker.PATH_DOWNLOAD_SERVICE,
            origin = TelemetryOrigin.DOWNLOAD_SERVICE,
        )
    }

    /**
     * 下载终态
     *
     * @param failureCode 有限失败枚举（disk_space / network / archive / verify 等），
     *   绝不携带异常消息、URL 或文件名
     */
    fun modelDownloadResult(
        modelId: String,
        status: String,
        durationMs: Int,
        progress: Int,
        failureCode: String? = null,
    ) {
        val properties = linkedMapOf<String, Any>(
            "model_id" to modelId,
            "status" to status,
            "duration_ms" to durationMs,
            "duration_bucket" to durationBucket(durationMs),
            "progress" to progress,
        )
        if (!failureCode.isNullOrBlank()) properties["failure_code"] = failureCode
        track(
            EVENT_MODEL_DOWNLOAD_RESULT,
            properties,
            url = ScreenTracker.PATH_DOWNLOAD_SERVICE,
            origin = TelemetryOrigin.DOWNLOAD_SERVICE,
        )
    }

    // ==================== 更新检查 ====================

    fun updateCheck(trigger: String, result: UpdateCheckResult, durationMs: Int) {
        val (resultKey, latestVersion) = when (result) {
            is UpdateCheckResult.UpdateAvailable -> RESULT_UPDATE_AVAILABLE to result.updateInfo.versionName
            is UpdateCheckResult.NoUpdateAvailable -> RESULT_NO_UPDATE to null
            is UpdateCheckResult.NetworkTimeout -> RESULT_TIMEOUT to null
            is UpdateCheckResult.NetworkError -> RESULT_NETWORK_ERROR to null
            is UpdateCheckResult.ServerError -> RESULT_SERVER_ERROR to null
            is UpdateCheckResult.ParseError -> RESULT_PARSE_ERROR to null
        }
        val properties = linkedMapOf<String, Any>(
            "trigger" to trigger,
            "result" to resultKey,
            "duration_ms" to durationMs,
        )
        if (!latestVersion.isNullOrBlank()) properties["latest_version"] = latestVersion
        track(
            EVENT_UPDATE_CHECK,
            properties,
            origin = if (trigger == TRIGGER_STARTUP) TelemetryOrigin.STARTUP else TelemetryOrigin.UI,
        )
    }

    fun updateDialogAction(source: String, action: String, version: String) {
        track(
            EVENT_UPDATE_DIALOG_ACTION,
            linkedMapOf(
                "source" to source,
                "action" to action,
                "version" to version,
            )
        )
    }

    // ==================== 打赏与关于页 ====================

    fun donateChannelClick(channel: String, url: String = URL_ABOUT) {
        track(EVENT_DONATE_CHANNEL_CLICK, linkedMapOf("channel" to channel), url)
    }

    fun donateQrSaved(channel: String, success: Boolean, url: String = URL_ABOUT) {
        track(
            EVENT_DONATE_QR_SAVED,
            linkedMapOf(
                "channel" to channel,
                "result" to if (success) RESULT_SUCCESS else RESULT_FAILED,
            ),
            url,
        )
    }

    fun externalLinkOpen(target: String, url: String = URL_ABOUT) {
        track(EVENT_EXTERNAL_LINK_OPEN, linkedMapOf("target" to target), url)
    }

    fun qqGroupCopied(url: String = URL_ABOUT) {
        track(EVENT_QQ_GROUP_COPIED, emptyMap(), url)
    }

    // ==================== 启动流程 ====================

    fun startupNetworkAction(action: String, offlineCapable: Boolean) {
        track(
            EVENT_STARTUP_NETWORK_ACTION,
            linkedMapOf(
                "action" to action,
                "offline_capable" to bool(offlineCapable),
            ),
            origin = TelemetryOrigin.STARTUP,
        )
    }

    fun notificationPermission(action: String) {
        track(EVENT_NOTIFICATION_PERMISSION, linkedMapOf("action" to action))
    }

    fun batteryOptimization(action: String) {
        track(EVENT_BATTERY_OPTIMIZATION, linkedMapOf("action" to action))
    }

    fun settingsJump(target: String, source: String) {
        track(
            EVENT_SETTINGS_JUMP,
            linkedMapOf(
                "target" to target,
                "source" to source,
            )
        )
    }

    // ==================== 崩溃 ====================

    /**
     * 补报上次进程遗留的受限崩溃摘要（正常启动后调用，不在崩溃现场联网）
     */
    fun appCrashSummary(
        summary: CrashReporter.CrashSummary,
        permit: CollectionPermit? = null,
    ): Boolean = track(
        EVENT_APP_CRASH,
        crashProperties(summary),
        url = "/",
        origin = TelemetryOrigin.CRASH_REPAIR,
        permit = permit,
    )

    internal fun crashProperties(summary: CrashReporter.CrashSummary): Map<String, Any> {
        val properties = linkedMapOf<String, Any>(
            "exception_class" to summary.exceptionClass,
            "exception_chain_depth" to summary.exceptionChainDepth,
            "frame_count" to summary.frames.size,
            "thread_type" to summary.threadType,
            "crash_timestamp_s" to summary.timestampSec,
            "crash_app_version" to summary.appVersion,
            "crash_version_code" to summary.versionCode,
            "crash_build_channel" to summary.buildChannel,
        )
        summary.exceptionChain.drop(1).forEachIndexed { index, className ->
            properties["exception_${index + 2}"] = className
        }
        summary.frames.forEachIndexed { index, frame ->
            properties["frame_${index + 1}"] = frame
        }
        return properties
    }

    /** RxJava 全局错误策略吞掉的孤儿错误；只带异常类名与线程类型 */
    fun rxSuppressed(exceptionClassName: String, threadName: String) {
        track(
            EVENT_RX_SUPPRESSED,
            linkedMapOf(
                "exception_class" to TelemetryIds.exceptionClass(exceptionClassName),
                "thread_type" to threadTypeOf(threadName),
            ),
            url = "/",
            origin = TelemetryOrigin.APP,
        )
    }

    // ==================== 内部工具 ====================

    private fun track(
        eventName: String,
        properties: Map<String, Any>,
        url: String = ScreenTracker.currentScreen(),
        origin: String = TelemetryOrigin.UI,
        permit: CollectionPermit? = null,
    ): Boolean = TalkifyTelemetry.trackEvent(eventName, properties, url, origin, permit)

    private fun bool(value: Boolean): Int = if (value) 1 else 0

    private fun threadTypeOf(name: String): String = if (name == "main") "main" else "background"

    private fun durationBucket(durationMs: Int): String = when {
        durationMs < 1_000 -> "<1s"
        durationMs < 3_000 -> "1-3s"
        durationMs < 10_000 -> "3-10s"
        durationMs < 30_000 -> "10-30s"
        durationMs < 60_000 -> "30-60s"
        else -> ">60s"
    }
}
