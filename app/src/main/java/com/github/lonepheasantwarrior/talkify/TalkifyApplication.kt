package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationChannel
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.DeviceInfoCollector
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TalkifyTelemetry
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder.UmamiRecorder
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
        deleteLegacyTelemetryPrefs()
        observeAppForeground()
        trackCurrentActivity()
    }

    /**
     * 观察应用级前台切换，每次回到前台上报一次启动信号
     *
     * 不能依赖 [Application.onCreate]：TTS 前台服务常驻，进程在任务划走后仍存活，
     * 重开应用不会重建进程。[ProcessLifecycleOwner] 以可见 Activity 为准，
     * 冷启动、划走重开、温热重进均触发 onStart，旋转屏幕等 Activity 重建不会误报
     */
    private fun observeAppForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                reportAppOpened()
                UmamiRecorder.start()
            }

            override fun onStop(owner: LifecycleOwner) {
                UmamiRecorder.stop()
            }
        })
    }

    /**
     * 追踪当前前台 Activity，供遥测录制子系统读取窗口语义树
     *
     * onResume 设置、onPause 清除，仅瞬态持有，不构成 Activity 泄漏
     */
    private fun trackCurrentActivity() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                TalkifyAppHolder.setCurrentActivity(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (TalkifyAppHolder.currentActivity() === activity) {
                    TalkifyAppHolder.setCurrentActivity(null)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * 清理旧版限频遥测遗留的计数存储
     */
    private fun deleteLegacyTelemetryPrefs() {
        deleteSharedPreferences("talkify_tts_telemetry")
    }

    /**
     * 上报应用回到前台信号与匿名设备画像
     *
     * pageview 作为会话锚点驱动 Umami 仪表盘核心指标；app_opened 事件附带设备画像，
     * 显示在事件（Events）区域
     */
    private fun reportAppOpened() {
        TalkifyTelemetry.trackPageView()
        TalkifyTelemetry.trackEvent("app_opened", DeviceInfoCollector.collect(this))
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
