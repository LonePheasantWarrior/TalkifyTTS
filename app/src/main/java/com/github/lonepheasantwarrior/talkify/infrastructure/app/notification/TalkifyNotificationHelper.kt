package com.github.lonepheasantwarrior.talkify.infrastructure.app.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.github.lonepheasantwarrior.talkify.MainActivity
import com.github.lonepheasantwarrior.talkify.R

/**
 * Talkify 快捷通知发送 Helper
 *
 * 提供应用中特定通知场景的一键发送功能。
 * 针对 Android 11+ 优化，移除了过时的 FullScreenIntent，改用标准的 Heads-up 优先级机制。
 */
object TalkifyNotificationHelper {

    private const val TTS_PLAYBACK_NOTIFICATION_ID = 1001

    /**
     * 通知类型密封类
     */
    sealed class TalkifyNotificationType(
        val channel: TalkifyNotificationChannel,
        val notificationId: Int,
        val titleResId: Int,
        val iconResId: Int
    ) {
        /**
         * TTS 朗读进行中通知 (前台服务常驻)
         */
        data object TtsPlayback : TalkifyNotificationType(
            channel = TalkifyNotificationChannel.TTS_PLAYBACK,
            notificationId = TTS_PLAYBACK_NOTIFICATION_ID,
            titleResId = R.string.notification_title,
            iconResId = R.drawable.ic_tts_notification
        )
    }

    /**
     * 创建点击通知时触发的默认 PendingIntent
     * 打开 MainActivity
     */
    private fun createDefaultPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        // Android 12+ (SDK 31+) 强制要求指定 FLAG_IMMUTABLE 或 FLAG_MUTABLE
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 确保通知通道已创建
     */
    fun ensureNotificationChannel(
        context: Context,
        channel: TalkifyNotificationChannel,
        channelNameResId: Int,
        channelDescriptionResId: Int
    ) {
        // 调用底层 NotificationHelper 创建渠道
        // 注意：底层实现中，SYSTEM_NOTIFICATION 对应的渠道重要性必须是 IMPORTANCE_HIGH 才能悬浮
        NotificationHelper.createNotificationChannel(
            context = context,
            channel = channel,
            channelName = context.getString(channelNameResId),
            channelDescription = context.getString(channelDescriptionResId)
        )
    }

    /**
     * 构建基础的通知选项 (主要用于 Service 等标准场景)
     */
    fun buildNotificationOptions(
        context: Context,
        notificationType: TalkifyNotificationType,
        pendingIntent: PendingIntent? = null
    ): NotificationOptions {
        val content = NotificationContent(
            title = context.getString(notificationType.titleResId),
            text = "", // 默认内容为空，通常由 Service 动态更新
            smallIconResId = notificationType.iconResId
        )

        return NotificationOptions(
            channel = notificationType.channel,
            notificationId = notificationType.notificationId,
            content = content,
            pendingIntent = pendingIntent ?: createDefaultPendingIntent(context),
            isOngoing = true, // 服务通知通常是常驻的
            isSilent = true,  // 服务通知通常不发声，避免打扰音频播放
            category = android.app.Notification.CATEGORY_SERVICE
        )
    }

    /**
     * 发送指定类型的通用通知
     */
    fun sendNotification(
        context: Context,
        notificationType: TalkifyNotificationType
    ) {
        val options = buildNotificationOptions(context, notificationType)
        NotificationHelper.sendNotification(context, options)
    }

    /**
     * 发送 TTS 朗读通知 (前台服务)
     */
    fun sendTtsPlaybackNotification(context: Context) {
        ensureNotificationChannel(
            context = context,
            channel = TalkifyNotificationChannel.TTS_PLAYBACK,
            channelNameResId = R.string.notification_channel_name,
            channelDescriptionResId = R.string.notification_channel_description
        )
        sendNotification(context, TalkifyNotificationType.TtsPlayback)
    }

    /**
     * 取消 TTS 朗读通知
     */
    fun cancelTtsPlaybackNotification(context: Context) {
        NotificationHelper.cancelNotification(context, TTS_PLAYBACK_NOTIFICATION_ID)
    }

    /**
     * 构建前台服务专用通知对象
     * 用于 Service.startForeground()
     */
    fun buildForegroundWithNotification(context: Context): android.app.Notification {
        ensureNotificationChannel(
            context = context,
            channel = TalkifyNotificationChannel.TTS_PLAYBACK,
            channelNameResId = R.string.notification_channel_name,
            channelDescriptionResId = R.string.notification_channel_description
        )

        val options = buildNotificationOptions(context, TalkifyNotificationType.TtsPlayback)
        return NotificationHelper.buildNotification(context, options)
    }

    /**
     * 发送系统通知 (Heads-up 悬浮通知)
     *
     * 只有 Priority 为 HIGH 且 Channel 重要性为 HIGH 时，才会触发悬浮。
     *
     * @param context 应用程序上下文
     * @param text 通知正文
     * @param notificationId 通知 ID
     * @param priority 优先级，默认 HIGH 以触发悬浮
     */
    fun sendSystemNotification(
        context: Context,
        text: String,
        notificationId: Int? = null,
        priority: Int = NotificationCompat.PRIORITY_HIGH
    ) {
        val channel = TalkifyNotificationChannel.SYSTEM_NOTIFICATION

        ensureNotificationChannel(
            context = context,
            channel = channel,
            channelNameResId = R.string.system_notification_channel_name,
            channelDescriptionResId = R.string.system_notification_channel_description
        )

        val content = NotificationContent(
            title = context.getString(R.string.system_notification_title),
            text = text,
            smallIconResId = R.drawable.ic_tts_notification
        )

        val options = NotificationOptions(
            channel = channel,
            notificationId = notificationId ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            content = content,
            pendingIntent = createDefaultPendingIntent(context),
            isOngoing = false, // 系统通知应该是可以滑除的
            isSilent = false,  // 系统通知应该有声音或震动
            category = android.app.Notification.CATEGORY_STATUS, // 或者 CATEGORY_ERROR
            priority = priority,
            fullScreenIntent = null
        )

        NotificationHelper.sendNotification(context, options)
    }
}