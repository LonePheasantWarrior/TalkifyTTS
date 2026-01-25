package com.github.lonepheasantwarrior.talkify.infrastructure.app.notification

import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat

/**
 * Talkify 应用通知通道枚举
 *
 * 统一管理应用中使用的所有通知通道，确保 Channel ID 的规范管理
 * 避免硬编码字符串，提高代码可维护性
 *
 * @property channelId 通知通道的唯一标识符
 * @property importance 通知通道的优先级级别
 */
enum class TalkifyNotificationChannel(
    val channelId: String,
    val importance: Int
) {
    /**
     * TTS 语音播放通知通道
     * 用于前台服务运行时的持久通知
     */
    TTS_PLAYBACK(
        channelId = "talkify_tts_playback",
        importance = NotificationManager.IMPORTANCE_LOW
    ),

    /**
     * 系统通知通道
     * 用于发送系统级的重要通知
     * 设置为高优先级以支持 heads-up 悬浮通知
     */
    SYSTEM_NOTIFICATION(
        channelId = "talkify_system_notification",
        importance = NotificationManager.IMPORTANCE_HIGH
    );

    companion object {
        /**
         * 根据 Channel ID 查找对应的枚举值
         *
         * @param channelId 通知通道 ID
         * @return 对应的 TalkifyNotificationChannel 枚举值，未找到时返回 null
         */
        fun fromChannelId(channelId: String): TalkifyNotificationChannel? {
            for (channel in entries) {
                if (channel.channelId == channelId) {
                    return channel
                }
            }
            return null
        }
    }
}

/**
 * 通知内容数据类
 *
 * 用于封装通知的展示内容，包括标题、文本和图标资源 ID
 *
 * @property title 通知标题
 * @property text 通知正文文本
 * @property smallIconResId 通知图标资源 ID
 */
data class NotificationContent(
    val title: String,
    val text: String,
    val smallIconResId: Int
)

/**
 * 通知选项数据类
 *
 * 封装创建和发送通知所需的全部配置信息
 * 提供灵活的参数设置，支持不同场景的通知需求
 *
 * @property channel 通知通道枚举
 * @property notificationId 通知唯一标识符
 * @property content 通知内容
 * @property pendingIntent 点击通知时触发的 PendingIntent，可选
 * @property isOngoing 是否为持续性通知（不可被用户滑动移除），默认为 false
 * @property isSilent 是否静默通知（不播放声音和振动），默认为 true
 * @property priority 通知优先级，默认为 PRIORITY_LOW
 * @property category 通知类别，用于系统分类展示，可选
 * @property fullScreenIntent 全屏 Intent，用于 heads-up 悬浮通知，可选
 */
data class NotificationOptions(
    val channel: TalkifyNotificationChannel,
    val notificationId: Int,
    val content: NotificationContent,
    val pendingIntent: PendingIntent? = null,
    val isOngoing: Boolean = false,
    val isSilent: Boolean = true,
    val priority: Int = NotificationCompat.PRIORITY_LOW,
    val category: String? = NotificationCompat.CATEGORY_SERVICE,
    val fullScreenIntent: PendingIntent? = null
)
