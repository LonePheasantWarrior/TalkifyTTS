package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationChannel
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TelemetryController
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

class TalkifyApplication : Application() {

    companion object {
        private const val TAG = "TalkifyApplication"
    }

    override fun onCreate() {
        super.onCreate()
        // release 构建关闭调试日志：用户朗读原文等敏感内容不进系统 logcat（P1-13）
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        TtsLogger.setDebugEnabled(isDebuggable)
        TtsLogger.i(TAG) { "TalkifyApplication onCreate" }
        TalkifyAppHolder.setContext(this)
        TalkifyExceptionHandler.initialize()
        // 遥测控制器是许可、队列、会话与前后台状态的唯一写入口：
        // 必须在进程创建时初始化，TTS 服务可在无 Activity 场景独立产生合成事件
        TelemetryController.initialize(this)
        createNotificationChannels()
        deleteLegacyTelemetryPrefs()
        observeAppForeground()
        trackCurrentActivity()
    }

    /**
     * 观察应用级前台切换
     *
     * 不能依赖 [Application.onCreate]：TTS 前台服务常驻，进程在任务划走后仍存活，
     * 重开应用不会重建进程。[ProcessLifecycleOwner] 以可见 Activity 为准，
     * 冷启动、划走重开、温热重进均触发 onStart，旋转屏幕等 Activity 重建不会误报。
     * 页面计数与 app_foregrounded 由 [TelemetryController] 统一负责。
     */
    private fun observeAppForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                TelemetryController.onAppForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                TelemetryController.onAppBackground()
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
