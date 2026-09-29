package com.github.lonepheasantwarrior.talkify

import android.content.Intent
import android.os.Process
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppActionTracker
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 全局未捕获异常处理器
 *
 * 捕获崩溃后发送系统通知提示用户，随后交还系统默认处理器终止进程。
 *
 * 不实现崩溃对话框：应用上下文无法挂载 Dialog（BadTokenException）；
 * 崩溃发生在主线程时主 looper 已阻塞、发生在后台线程时进程随默认处理器
 * 立即终止——对话框在两种场景下均不可达。崩溃告知经由系统通知完成。
 */
object TalkifyExceptionHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "TalkifyException"

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun initialize() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        TtsLogger.i("Global exception handler initialized", tag = TAG)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        TtsLogger.e("Uncaught exception caught", throwable = throwable, tag = TAG)
        reportCrashTelemetry(thread, throwable)

        val context = TalkifyAppHolder.getContext()
        if (context != null) {
            // 发送崩溃通知，提示用户应用发生错误
            TalkifyNotificationHelper.sendSystemNotification(
                context,
                context.getString(R.string.crash_notification_message)
            )
        }

        previousHandler?.uncaughtException(thread, throwable)
            ?: Process.killProcess(Process.myPid())
    }

    /**
     * 崩溃遥测：后台线程阻塞上报，限时 2 秒
     *
     * 崩溃链路进程随时被杀，纯异步请求大概率无法送达，故短暂等待发送完成；
     * 全链路 try-catch，绝不干扰原有崩溃处理
     */
    private fun reportCrashTelemetry(crashThread: Thread, throwable: Throwable) {
        try {
            val latch = CountDownLatch(1)
            Thread({
                try {
                    AppActionTracker.appCrash(throwable.javaClass.name, crashThread.name)
                } catch (_: Throwable) {
                } finally {
                    latch.countDown()
                }
            }, "talkify-crash-telemetry").start()
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }
}
