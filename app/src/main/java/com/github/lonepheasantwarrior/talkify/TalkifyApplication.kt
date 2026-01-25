package com.github.lonepheasantwarrior.talkify

import android.app.Application
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationChannel
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

class TalkifyApplication : Application() {

    companion object {
        private const val TAG = "TalkifyApplication"
    }

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i(TAG) { "TalkifyApplication onCreate" }
        TalkifyAppHolder.setContext(this)
        TalkifyExceptionHandler.initialize()
        createNotificationChannels()
    }

    /**
     * 创建所有应用通知通道
     *
     * 在应用启动时预创建所有通知通道
     * 确保后续发送通知时通道已存在
     */
    private fun createNotificationChannels() {
        TtsLogger.d(TAG) { "Creating notification channels" }

        TalkifyNotificationHelper.ensureNotificationChannel(
            context = this,
            channel = TalkifyNotificationChannel.TTS_PLAYBACK,
            channelNameResId = R.string.notification_channel_name,
            channelDescriptionResId = R.string.notification_channel_description
        )

        TalkifyNotificationHelper.ensureNotificationChannel(
            context = this,
            channel = TalkifyNotificationChannel.SYSTEM_NOTIFICATION,
            channelNameResId = R.string.system_notification_channel_name,
            channelDescriptionResId = R.string.system_notification_channel_description
        )

        TtsLogger.d(TAG) { "Notification channels created" }
    }
}
