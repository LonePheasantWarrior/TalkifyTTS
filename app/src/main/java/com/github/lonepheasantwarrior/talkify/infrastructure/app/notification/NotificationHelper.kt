package com.github.lonepheasantwarrior.talkify.infrastructure.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 通知辅助工具类
 *
 * 提供通知通道创建、通知构建、发送和取消的通用方法
 * 封装 Android 通知系统 API，简化通知操作流程
 *
 * 主要功能：
 * - 创建通知通道（兼容 Android 8.0+）
 * - 构建通知对象
 * - 发送通知
 * - 取消通知
 *
 * 使用示例：
 * <pre>
 * val options = NotificationOptions(
 *     channel = TalkifyNotificationChannel.TTS_PLAYBACK,
 *     notificationId = 1001,
 *     content = NotificationContent("标题", "内容", R.drawable.ic_notification)
 * )
 * NotificationHelper.sendNotification(context, options)
 * </pre>
 */
object NotificationHelper {

    private const val DEFAULT_NOTIFICATION_ID = 1000

    /**
     * 创建通知通道
     *
     * Android 8.0 (API 26) 及以上版本要求必须先创建通知通道才能发送通知
     * 此方法封装了通道创建的通用逻辑，支持配置通道名称、描述和重要性级别
     *
     * @param context 应用程序上下文
     * @param channel 通知通道枚举，包含通道 ID 和重要性级别
     * @param channelName 通道显示名称（用户可见）
     * @param channelDescription 通道描述（用户可见）
     */
    fun createNotificationChannel(
        context: Context,
        channel: TalkifyNotificationChannel,
        channelName: String,
        channelDescription: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = android.app.NotificationChannel(
                channel.channelId,
                channelName,
                channel.importance
            )
            notificationChannel.description = channelDescription
            notificationChannel.setShowBadge(false)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)
        }
    }

    /**
     * 根据配置选项构建通知对象
     *
     * 使用 NotificationCompat.Builder 创建兼容性好且功能完整的通知
     * 支持设置标题、文本、图标、点击事件、持续性、静默等属性
     *
     * @param context 应用程序上下文
     * @param options 通知选项配置
     * @return 构建完成的 Notification 对象
     */
    fun buildNotification(
        context: Context,
        options: NotificationOptions
    ): Notification {
        val builder = NotificationCompat.Builder(context, options.channel.channelId)
        builder.setContentTitle(options.content.title)
        builder.setContentText(options.content.text)
        builder.setSmallIcon(options.content.smallIconResId)
        builder.setOngoing(options.isOngoing)
        builder.setSilent(options.isSilent)
        builder.setPriority(options.priority)

        if (options.pendingIntent != null) {
            builder.setContentIntent(options.pendingIntent)
        }

        if (options.category != null) {
            builder.setCategory(options.category)
        }

        return builder.build()
    }

    /**
     * 发送通知
     *
     * 将构建好的通知对象发送到系统通知栏
     *
     * @param context 应用程序上下文
     * @param notification 要发送的通知对象
     * @param notificationId 通知标识符，用于后续取消通知
     */
    fun sendNotification(
        context: Context,
        notification: Notification,
        notificationId: Int = DEFAULT_NOTIFICATION_ID
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    /**
     * 根据选项发送通知
     *
     * 便捷方法：内部自动构建通知并发送
     *
     * @param context 应用程序上下文
     * @param options 通知选项配置
     */
    fun sendNotification(
        context: Context,
        options: NotificationOptions
    ) {
        val notification = buildNotification(context, options)
        sendNotification(context, notification, options.notificationId)
    }

    /**
     * 取消指定通知
     *
     * 根据通知 ID 移除已发送的通知
     *
     * @param context 应用程序上下文
     * @param notificationId 要取消的通知 ID
     */
    fun cancelNotification(
        context: Context,
        notificationId: Int = DEFAULT_NOTIFICATION_ID
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }

    /**
     * 取消所有通知
     *
     * 移除应用发送的所有通知
     *
     * @param context 应用程序上下文
     */
    fun cancelAllNotifications(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
    }
}
