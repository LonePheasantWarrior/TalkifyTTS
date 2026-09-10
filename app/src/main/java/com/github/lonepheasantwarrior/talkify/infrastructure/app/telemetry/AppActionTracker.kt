package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.os.SystemClock
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateCheckResult

/**
 * 应用动作事件埋点（语义层）
 *
 * 集中定义用户动作的事件名、属性 schema 与词表。与 [AppPageTracker] 构成
 * "做了什么 / 去了哪里"的分工：**打开某个页面/抽屉/弹窗属于"去了哪里"，
 * 走 [AppPageTracker] 的 pageview（虚拟路由），绝不在本组件中定义为事件**；
 * 本组件只管点击/触发功能类动作（预览播放、配置保存、模型下载、更新检查、
 * 打赏、启动检查选择、崩溃等），与 [TtsTelemetryTracker]（语音合成作业）并列。
 *
 * 事件名、属性 schema 与枚举词表全部收敛在本组件内。新增动作埋点时
 * **先在此组件添加方法与词表常量**，再在业务侧调用，禁止在业务层拼事件名。
 *
 * **隐私红线**（改动前必读）：
 * - 绝不携带用户输入的文本内容、API Key、音色/模型以外的自由文本——只允许枚举值、
 *   ID、计数与时长
 * - 布尔属性一律转 0/1 整数；可空可选属性缺省时不携带该键
 * - 崩溃事件只带异常类名与线程名，不带异常消息（消息可能夹带用户文本）
 *
 * 所有上报经 [TalkifyTelemetry] 门面，全异步、零阻塞；上报失败只记日志，绝不影响业务。
 *
 * @see TalkifyTelemetry
 * @see TtsTelemetryTracker
 * @see AppPageTracker
 */
object AppActionTracker {

    // ==================== 事件名 ====================

    /** 预览"播放"按钮点击（含被拦截的点击，构成完整漏斗） */
    const val EVENT_PREVIEW_PLAY_CLICK = "preview_play_click"

    /** 一次预览播放会话的终态（成功播完/用户停止/出错） */
    const val EVENT_PREVIEW_PLAYBACK = "preview_playback"

    /** 预览区音色 chip 点击 */
    const val EVENT_PREVIEW_VOICE_SELECTED = "preview_voice_selected"

    /** 供应商切换 */
    const val EVENT_PROVIDER_SWITCHED = "provider_switched"

    /** 配置保存（含下载确认弹窗两条分支） */
    const val EVENT_CONFIG_SAVED = "config_saved"

    /** 本地模型下载确认弹窗的按钮选择 */
    const val EVENT_MODEL_DOWNLOAD_DIALOG = "model_download_dialog"

    /** 本地模型下载任务实际开始（前台服务启动） */
    const val EVENT_MODEL_DOWNLOAD_START = "model_download_start"

    /** 本地模型下载终态（完成/失败/取消） */
    const val EVENT_MODEL_DOWNLOAD_RESULT = "model_download_result"

    /** 检查更新（手动 + 启动自动） */
    const val EVENT_UPDATE_CHECK = "update_check"

    /** 更新弹窗按钮选择 */
    const val EVENT_UPDATE_DIALOG_ACTION = "update_dialog_action"

    /** 打赏渠道按钮点击（微信/支付宝） */
    const val EVENT_DONATE_CHANNEL_CLICK = "donate_channel_click"

    /** 打赏二维码保存结果 */
    const val EVENT_DONATE_QR_SAVED = "donate_qr_saved"

    /** 外部链接打开 */
    const val EVENT_EXTERNAL_LINK_OPEN = "external_link_open"

    /** QQ 群号复制 */
    const val EVENT_QQ_GROUP_COPIED = "qq_group_copied"

    /** 网络阻断弹窗按钮选择 */
    const val EVENT_STARTUP_NETWORK_ACTION = "startup_network_action"

    /** 通知权限弹窗流转 */
    const val EVENT_NOTIFICATION_PERMISSION = "notification_permission"

    /** 电池优化弹窗按钮选择 */
    const val EVENT_BATTERY_OPTIMIZATION = "battery_optimization"

    /** 跳转系统设置页 */
    const val EVENT_SETTINGS_JUMP = "settings_jump"

    /** 全局未捕获异常（崩溃） */
    const val EVENT_APP_CRASH = "app_crash"

    // ==================== 词表 ====================

    /** preview_play_click.outcome：已开始播放 */
    const val OUTCOME_STARTED = "started"

    /** preview_play_click.outcome：文本为空被拦截 */
    const val OUTCOME_EMPTY_TEXT = "empty_text"

    /** preview_play_click.outcome：供应商未配置被拦截 */
    const val OUTCOME_NOT_CONFIGURED = "not_configured"

    /** preview_play_click.outcome：本地模型未下载，转下载确认弹窗 */
    const val OUTCOME_MODEL_NEED_DOWNLOAD = "model_need_download"

    /** preview_play_click.outcome：本地模型下载中冲突被拦截 */
    const val OUTCOME_MODEL_DOWNLOADING = "model_downloading"

    /** model_download_dialog.source：配置面板内的下载确认弹窗 */
    const val SOURCE_CONFIG_SHEET = "config_sheet"

    /** model_download_dialog.source：预览播放触发的下载确认弹窗 */
    const val SOURCE_PREVIEW = "preview"

    /** update_check.trigger / update_dialog_action.source：启动时自动检查 */
    const val TRIGGER_STARTUP = "startup"

    /** update_check.trigger / update_dialog_action.source：关于页手动触发 */
    const val TRIGGER_MANUAL = "manual"

    /** settings_jump.target 等：目标/来源枚举值，见各方法 KDoc */

    // ==================== 预览播放 ====================

    /**
     * 预览"播放"按钮点击
     *
     * 在 MainScreen 的 onPlayClick 各分支（含被拦截提前返回的分支）调用，
     * 每次点击恰上报一条，与 [EVENT_PREVIEW_PLAYBACK] 构成"点击 → 实际播放"漏斗
     *
     * @param providerId 当前供应商 ID
     * @param voiceId 选中音色 ID（未选中时传空字符串）
     * @param textLength 输入文本字数
     * @param outcome 结果词表：started / empty_text / not_configured /
     *   model_need_download / model_downloading
     */
    fun previewPlayClick(providerId: String, voiceId: String, textLength: Int, outcome: String) {
        track(EVENT_PREVIEW_PLAY_CLICK, linkedMapOf(
            "provider_id" to providerId,
            "voice_id" to voiceId.ifBlank { DEFAULT_VALUE },
            "text_length" to textLength,
            "outcome" to outcome,
        ))
    }

    /** 预览区音色 chip 点击（providerId 为当前供应商，voiceId 为被点击音色） */
    fun previewVoiceSelected(providerId: String, voiceId: String) {
        track(EVENT_PREVIEW_VOICE_SELECTED, linkedMapOf(
            "provider_id" to providerId,
            "voice_id" to voiceId.ifBlank { DEFAULT_VALUE },
        ))
    }

    /**
     * 创建一次预览播放会话的计时器（Attempt 模式，与 [TtsTelemetryTracker.Attempt] 同构）
     *
     * 应在 TtsPreviewPlayer.speak() 供应商解析成功后调用；
     * 终态在各类出口标记，最终在 stopPlayback 统一出口上报
     */
    fun beginPreviewPlayback(providerId: String, modelId: String, voiceId: String, textLength: Int): PreviewAttempt =
        PreviewAttempt(providerId, modelId, voiceId, textLength)

    /**
     * 一次预览播放会话的计时器
     *
     * mark* 一律首写生效（先到的终态胜出），规避用户停止与异常收尾的竞态；
     * [markFirstAudio] 由 provider 回调线程调用，其余在播放主链路调用
     */
    class PreviewAttempt internal constructor(
        private val providerId: String,
        private val modelId: String,
        private val voiceId: String,
        private val textLength: Int,
    ) {
        private val startUptimeMs = SystemClock.elapsedRealtime()

        @Volatile
        private var firstAudioUptimeMs = 0L

        @Volatile
        private var firstAudioSampleRate = 0

        @Volatile
        private var status: String? = null
        private var errorCode: String? = null
        private var endUptimeMs = 0L

        /** 记录首包音频到达（仅首次有效） */
        fun markFirstAudio(sampleRate: Int) {
            if (firstAudioUptimeMs == 0L) {
                firstAudioUptimeMs = SystemClock.elapsedRealtime()
                firstAudioSampleRate = sampleRate
            }
        }

        /** 音频全部播完（含尾部缓冲排空） */
        fun markSuccess() = mark(STATUS_SUCCESS)

        /** 用户主动停止 */
        fun markStopped() = mark(STATUS_STOPPED)

        /** 记录失败，[code] 为 TtsErrorCode 数值或异常类名 */
        fun markError(code: String) {
            errorCode = code
            mark(STATUS_ERROR)
        }

        /** 上报本次预览播放事件（未标记终态时静默跳过；幂等，可安全多次调用） */
        fun report() {
            if (status == null || reported) return
            reported = true
            val durationMs = (endUptimeMs - startUptimeMs).toInt()

            val properties = linkedMapOf<String, Any>(
                "provider_id" to providerId,
                "model_id" to modelId.ifBlank { DEFAULT_VALUE },
                "voice_id" to voiceId.ifBlank { DEFAULT_VALUE },
                "text_length" to textLength,
                "status" to status!!,
                "duration_ms" to durationMs,
                "duration_bucket" to durationBucket(durationMs),
            )
            errorCode?.let { properties["error_code"] = it }
            if (firstAudioUptimeMs > 0L) {
                properties["first_audio_ms"] = (firstAudioUptimeMs - startUptimeMs).toInt()
                properties["sample_rate"] = firstAudioSampleRate
            }
            track(EVENT_PREVIEW_PLAYBACK, properties)
        }

        private fun mark(finalStatus: String) {
            if (status != null) return
            status = finalStatus
            endUptimeMs = SystemClock.elapsedRealtime()
        }

        @Volatile
        private var reported = false
    }

    // ==================== 供应商与配置 ====================

    /** 供应商切换（from == to 时静默跳过，过滤重复点选） */
    fun providerSwitched(fromProviderId: String, toProviderId: String) {
        if (fromProviderId == toProviderId) return
        track(EVENT_PROVIDER_SWITCHED, linkedMapOf(
            "from_provider" to fromProviderId,
            "to_provider" to toProviderId,
        ))
    }

    /**
     * 配置保存
     *
     * @param providerId 供应商 ID
     * @param isLocalModel 是否本地模型供应商（0/1）
     * @param hasCustomApiUrl 是否自定义 API 地址（0/1，本地模型恒为 0）
     * @param hasCustomModelId 是否自定义模型 ID（0/1，本地模型的选择不算自定义）
     * @param voiceId 保存的音色 ID（空则记 default）
     */
    fun configSaved(
        providerId: String,
        isLocalModel: Boolean,
        hasCustomApiUrl: Boolean,
        hasCustomModelId: Boolean,
        voiceId: String,
    ) {
        track(EVENT_CONFIG_SAVED, linkedMapOf(
            "provider_id" to providerId,
            "is_local_model" to bool(isLocalModel),
            "has_custom_api_url" to bool(hasCustomApiUrl),
            "has_custom_model_id" to bool(hasCustomModelId),
            "voice_id" to voiceId.ifBlank { DEFAULT_VALUE },
        ))
    }

    // ==================== 模型下载 ====================

    /**
     * 下载确认弹窗按钮选择
     *
     * @param modelId 模型 ID
     * @param sizeDisplay 注册表中的体积档位文案（如 "~200 MB"）
     * @param source 弹窗来源：config_sheet / preview
     * @param confirmed true=确认下载，false=取消
     */
    fun modelDownloadDialog(modelId: String, sizeDisplay: String, source: String, confirmed: Boolean) {
        val properties = linkedMapOf<String, Any>(
            "model_id" to modelId,
            "source" to source,
            "action" to if (confirmed) ACTION_CONFIRM else ACTION_DISMISS,
        )
        if (sizeDisplay.isNotBlank()) {
            properties["size_display"] = sizeDisplay
        }
        track(EVENT_MODEL_DOWNLOAD_DIALOG, properties)
    }

    /** 下载任务实际开始（由 LocalModelDownloadService 在 onStartCommand 校验通过后调用） */
    fun modelDownloadStart(modelId: String, fileCount: Int, totalSizeMb: Int) {
        track(EVENT_MODEL_DOWNLOAD_START, linkedMapOf(
            "model_id" to modelId,
            "file_count" to fileCount,
            "total_size_mb" to totalSizeMb,
        ))
    }

    /**
     * 下载终态（完成/失败/取消共用一条事件）
     *
     * @param modelId 模型 ID
     * @param status completed / failed / cancelled
     * @param durationMs 从服务接收任务到终态的耗时（毫秒）
     * @param progress 终态时刻的进度百分比（完成恒为 100）
     * @param errorReason 失败原因（仅失败时，服务内部中文短语，不含用户数据）
     */
    fun modelDownloadResult(
        modelId: String,
        status: String,
        durationMs: Int,
        progress: Int,
        errorReason: String? = null,
    ) {
        val properties = linkedMapOf<String, Any>(
            "model_id" to modelId,
            "status" to status,
            "duration_ms" to durationMs,
            "duration_bucket" to durationBucket(durationMs),
            "progress" to progress,
        )
        if (!errorReason.isNullOrBlank()) {
            properties["error_reason"] = errorReason.take(REASON_MAX_LENGTH)
        }
        track(EVENT_MODEL_DOWNLOAD_RESULT, properties)
    }

    // ==================== 更新检查 ====================

    /**
     * 一次更新检查（手动与启动自动共用，trigger 区分）
     *
     * @param trigger startup / manual
     * @param result 更新检查结果（内部映射为结果词表：update_available / no_update /
     *   timeout / network_error / server_error / parse_error）
     * @param durationMs 检查耗时（毫秒）
     */
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
        if (!latestVersion.isNullOrBlank()) {
            properties["latest_version"] = latestVersion
        }
        track(EVENT_UPDATE_CHECK, properties)
    }

    /**
     * 更新弹窗按钮选择
     *
     * @param source 弹窗来源：startup（启动自动弹窗）/ manual（关于页手动检查后弹出）
     * @param action update_now / remind_later
     * @param version 弹窗展示的新版本号
     */
    fun updateDialogAction(source: String, action: String, version: String) {
        track(EVENT_UPDATE_DIALOG_ACTION, linkedMapOf(
            "source" to source,
            "action" to action,
            "version" to version,
        ))
    }

    // ==================== 打赏与关于页 ====================

    /** 打赏渠道按钮点击（channel：wechat / alipay） */
    fun donateChannelClick(channel: String, url: String = "/") {
        track(EVENT_DONATE_CHANNEL_CLICK, linkedMapOf("channel" to channel), url)
    }

    /** 打赏二维码保存结果（channel：wechat / alipay，success 0/1） */
    fun donateQrSaved(channel: String, success: Boolean, url: String = "/") {
        track(EVENT_DONATE_QR_SAVED, linkedMapOf(
            "channel" to channel,
            "result" to if (success) RESULT_SUCCESS else RESULT_FAILED,
        ), url)
    }

    /** 外部链接打开（target：github 等，见调用点） */
    fun externalLinkOpen(target: String, url: String = "/") {
        track(EVENT_EXTERNAL_LINK_OPEN, linkedMapOf("target" to target), url)
    }

    /** QQ 群号复制 */
    fun qqGroupCopied(url: String = "/") {
        track(EVENT_QQ_GROUP_COPIED, emptyMap(), url)
    }

    // ==================== 启动流程 ====================

    /**
     * 网络阻断弹窗按钮选择
     *
     * 打开弹窗本身是 pageview（[AppPageTracker.PATH_NETWORK_BLOCKED]），
     * 事件的 offline_capable 承接打开场景的离线可用性
     *
     * @param action open_settings / acknowledged
     * @param offlineCapable 弹窗展示时本地模型是否已就绪可离线使用（0/1）
     */
    fun startupNetworkAction(action: String, offlineCapable: Boolean) {
        track(EVENT_STARTUP_NETWORK_ACTION, linkedMapOf(
            "action" to action,
            "offline_capable" to bool(offlineCapable),
        ))
    }

    /**
     * 通知权限弹窗流转
     *
     * action 词表：requested（点击"去授权"）/ skipped（点击"跳过"）/
     * granted（系统授权弹窗最终同意）/ denied（系统授权弹窗最终拒绝）
     */
    fun notificationPermission(action: String) {
        track(EVENT_NOTIFICATION_PERMISSION, linkedMapOf("action" to action))
    }

    /** 电池优化弹窗按钮选择（action：go_settings / skipped） */
    fun batteryOptimization(action: String) {
        track(EVENT_BATTERY_OPTIMIZATION, linkedMapOf("action" to action))
    }

    /**
     * 跳转系统设置页
     *
     * @param target tts / network / notification / app_details
     * @param source 触发来源：default_banner / network_dialog / permission_dialog 等
     */
    fun settingsJump(target: String, source: String) {
        track(EVENT_SETTINGS_JUMP, linkedMapOf(
            "target" to target,
            "source" to source,
        ))
    }

    // ==================== 崩溃 ====================

    /**
     * 全局未捕获异常（崩溃）
     *
     * 只携带异常类名与线程名，绝不携带异常消息（可能夹带用户文本）。
     * 走阻塞式上报以对冲崩溃即杀进程导致的丢失，调用方需自行限时。
     */
    fun appCrash(exceptionClassName: String, threadName: String) {
        TalkifyTelemetry.trackEventBlocking(EVENT_APP_CRASH, linkedMapOf(
            "exception_class" to exceptionClassName,
            "thread_name" to threadName,
        ))
    }

    // ==================== 内部工具 ====================

    private const val DEFAULT_VALUE = "default"
    private const val REASON_MAX_LENGTH = 100

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

    // update_check 结果词表
    const val RESULT_UPDATE_AVAILABLE = "update_available"
    const val RESULT_NO_UPDATE = "no_update"
    const val RESULT_TIMEOUT = "timeout"
    const val RESULT_NETWORK_ERROR = "network_error"
    const val RESULT_SERVER_ERROR = "server_error"
    const val RESULT_PARSE_ERROR = "parse_error"

    // settings_jump.target
    const val TARGET_TTS = "tts"
    const val TARGET_NOTIFICATION = "notification"

    // external_link_open.target
    const val TARGET_GITHUB = "github"

    // settings_jump.source
    const val SOURCE_DEFAULT_BANNER = "default_banner"
    const val SOURCE_PERMISSION_DIALOG = "permission_dialog"

    // 打赏渠道
    const val CHANNEL_WECHAT = "wechat"
    const val CHANNEL_ALIPAY = "alipay"

    // 事件发生的页面路径（对齐 MainActivity 的路由映射）
    const val URL_ABOUT = "/about"

    /** 上报（带可选页面路径，About 页事件传 "/about" 以便按页面拆分） */
    private fun track(eventName: String, properties: Map<String, Any>, url: String = "/") {
        TalkifyTelemetry.trackEvent(eventName, properties, url)
    }

    private fun bool(value: Boolean): Int = if (value) 1 else 0

    private fun durationBucket(durationMs: Int): String = when {
        durationMs < 1_000 -> "<1s"
        durationMs < 3_000 -> "1-3s"
        durationMs < 10_000 -> "3-10s"
        durationMs < 30_000 -> "10-30s"
        durationMs < 60_000 -> "30-60s"
        else -> ">60s"
    }
}
