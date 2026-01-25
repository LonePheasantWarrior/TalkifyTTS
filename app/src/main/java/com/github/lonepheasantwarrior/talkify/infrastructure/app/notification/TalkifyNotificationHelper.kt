package com.github.lonepheasantwarrior.talkify.infrastructure.app.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.github.lonepheasantwarrior.talkify.MainActivity
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyNotificationActivity

/**
 * Talkify 快捷通知发送 Helper
 *
 * 提供应用中特定通知场景的一键发送功能
 * 通过密封类定义通知类型，便于扩展和管理
 *
 * 使用示例：
 * <pre>
 * // 发送 TTS 朗读通知
 * TalkifyNotificationHelper.sendTtsPlaybackNotification(context)
 *
 * // 启动前台服务并发送通知
 * startForeground(NOTIFICATION_ID, TalkifyNotificationHelper.startForegroundWithNotification(context))
 * </pre>
 */
object TalkifyNotificationHelper {

    private const val TTS_PLAYBACK_NOTIFICATION_ID = 1001

    /**
     * 通知类型密封类
     *
     * 定义应用中使用的通知类型，每个类型对应特定的资源和配置
     * 新增通知类型时只需添加新的密封子类即可
     *
     * @property channel 通知通道
     * @property notificationId 通知唯一标识符
     * @property titleResId 标题资源 ID
     * @property iconResId 图标资源 ID
     */
    sealed class TalkifyNotificationType(
        val channel: TalkifyNotificationChannel,
        val notificationId: Int,
        val titleResId: Int,
        val iconResId: Int
    ) {
        /**
         * TTS 朗读进行中通知
         *
         * 用于前台服务运行时的持久通知
         * 展示"Talkify 正在朗读"状态
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
     *
     * 默认打开 MainActivity，并使用单一顶部启动模式
     *
     * @param context 应用程序上下文
     * @return 配置好的 PendingIntent
     */
    private fun createDefaultPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 创建全屏 PendingIntent
     *
     * 用于 heads-up 悬浮通知，点击时显示全屏弹窗
     *
     * @param context 应用程序上下文
     * @return 配置好的全屏 PendingIntent
     */
    private fun createFullScreenPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TalkifyNotificationActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 确保通知通道已创建
     *
     * 在发送通知前调用，确保对应通道已注册到系统
     * 内部调用 [NotificationHelper.createNotificationChannel]
     *
     * @param context 应用程序上下文
     * @param channel 通知通道类型
     * @param channelNameResId 通道名称资源 ID
     * @param channelDescriptionResId 通道描述资源 ID
     */
    fun ensureNotificationChannel(
        context: Context,
        channel: TalkifyNotificationChannel,
        channelNameResId: Int,
        channelDescriptionResId: Int
    ) {
        NotificationHelper.createNotificationChannel(
            context = context,
            channel = channel,
            channelName = context.getString(channelNameResId),
            channelDescription = context.getString(channelDescriptionResId)
        )
    }

    /**
     * 根据通知类型构建通知选项
     *
     * 封装通知选项的创建过程，简化调用
     *
     * @param context 应用程序上下文
     * @param notificationType 通知类型
     * @param pendingIntent 自定义 PendingIntent，为 null 时使用默认
     * @return 配置好的 [NotificationOptions]
     */
    fun buildNotificationOptions(
        context: Context,
        notificationType: TalkifyNotificationType,
        pendingIntent: PendingIntent? = null
    ): NotificationOptions {
        val content = NotificationContent(
            title = context.getString(notificationType.titleResId),
            text = "",
            smallIconResId = notificationType.iconResId
        )

        return NotificationOptions(
            channel = notificationType.channel,
            notificationId = notificationType.notificationId,
            content = content,
            pendingIntent = pendingIntent ?: createDefaultPendingIntent(context),
            isOngoing = true,
            isSilent = true,
            category = android.app.Notification.CATEGORY_SERVICE
        )
    }

    /**
     * 发送指定类型的通知
     *
     * 一键发送通知，自动处理通道创建和选项构建
     *
     * @param context 应用程序上下文
     * @param notificationType 通知类型
     */
    fun sendNotification(
        context: Context,
        notificationType: TalkifyNotificationType
    ) {
        val options = buildNotificationOptions(context, notificationType)
        NotificationHelper.sendNotification(context, options)
    }

    /**
     * 发送 TTS 朗读通知
     *
     * 便捷方法：专门用于发送"Talkify 正在朗读"通知
     * 自动处理通道创建和通知构建
     *
     * @param context 应用程序上下文
     */
    fun sendTtsPlaybackNotification(context: Context) {
        val channel = TalkifyNotificationChannel.TTS_PLAYBACK
        ensureNotificationChannel(
            context = context,
            channel = channel,
            channelNameResId = R.string.notification_channel_name,
            channelDescriptionResId = R.string.notification_channel_description
        )
        sendNotification(context, TalkifyNotificationType.TtsPlayback)
    }

    /**
     * 取消 TTS 朗读通知
     *
     * 便捷方法：取消"Talkify 正在朗读"通知
     *
     * @param context 应用程序上下文
     */
    fun cancelTtsPlaybackNotification(context: Context) {
        NotificationHelper.cancelNotification(context, TTS_PLAYBACK_NOTIFICATION_ID)
    }

    /**
     * 构建前台服务通知
     *
     * 便捷方法：启动前台服务时自动发送 TTS 朗读通知
     * 这是 TTS 服务最常用的场景
     *
     * @param context 应用程序上下文
     * @return 构建好的 Notification 对象，可直接传递给 [android.app.Service.startForeground]
     */
    fun buildForegroundWithNotification(context: Context): android.app.Notification {
        val channel = TalkifyNotificationChannel.TTS_PLAYBACK
        ensureNotificationChannel(
            context = context,
            channel = channel,
            channelNameResId = R.string.notification_channel_name,
            channelDescriptionResId = R.string.notification_channel_description
        )

        val options = buildNotificationOptions(context, TalkifyNotificationType.TtsPlayback)
        return NotificationHelper.buildNotification(context, options)
    }

    /**
     * 发送系统通知
     *
     * 便捷方法：发送系统级重要通知
     * 支持自定义通知内容和可选的通知 ID、优先级及全屏 Intent
     * 高优先级通知会显示为 heads-up 悬浮通知
     *
     * @param context 应用程序上下文
     * @param text 通知正文内容（必填）
     * @param notificationId 通知 ID，为 null 时随机生成
     * @param priority 通知优先级，默认为 PRIORITY_HIGH
     * @param fullScreenIntent 全屏 Intent，为 null 时使用默认
     */
    fun sendSystemNotification(
        context: Context,
        text: String,
        notificationId: Int? = null,
        priority: Int = NotificationCompat.PRIORITY_HIGH,
        fullScreenIntent: PendingIntent? = null
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
            isOngoing = false,
            isSilent = false,
            category = android.app.Notification.CATEGORY_STATUS,
            priority = priority,
            fullScreenIntent = fullScreenIntent ?: createFullScreenPendingIntent(context)
        )

        NotificationHelper.sendNotification(context, options)
    }
}
