package com.github.lonepheasantwarrior.talkify

import android.os.Process
import com.github.lonepheasantwarrior.talkify.TalkifyExceptionHandler.handleRxJavaGlobalError
import com.github.lonepheasantwarrior.talkify.TalkifyExceptionHandler.installRxJavaErrorPolicy
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppActionTracker
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
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

    private var rxJavaErrorPolicyInstalled = false

    private var installed = false

    fun initialize() {
        if (installed) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        installed = true
        installRxJavaErrorPolicy()
        TtsLogger.i("Global exception handler initialized", tag = TAG)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
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
        } finally {
            // 交还必须无条件执行：通知/遥测链路任何一环抛出都不能让崩溃线程悬挂
            previousHandler?.uncaughtException(thread, throwable)
                ?: Process.killProcess(Process.myPid())
        }
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

    /**
     * 安装 RxJava 全局错误策略（幂等）
     *
     * 背景：阿里云百炼供应商的 DashScope SDK 基于 RxJava 2 实现流式合成。
     * 停止/暂停朗读时 AliyunBailianProvider 会 dispose 订阅，SDK 的 SSE 连接
     * 随之以 SocketException 失败并被包装成 ApiException，继续向已取消的
     * 发射器投递 onError。RxJava 2 对"发射到已取消订阅"的孤儿错误一律路由到
     * RxJavaPlugins 全局处理器（非 bug 类异常统一包装为 UndeliverableException
     * 后分发），未配置时默认交给线程未捕获异常处理器，
     * 直接触发 FATAL EXCEPTION 杀死常驻 TTS 进程（SIG 9）。
     *
     * 策略：见 [handleRxJavaGlobalError] 的分诊规则。
     *
     * 线程模型：回调发生在抛错线程（如 OkHttp 读线程）；lambda 整体 try-catch——
     * 错误处理器自身再抛异常会被 RxJava 兜底路由回未捕获链路导致进程崩溃
     * （RxJavaPlugins.onError 对抛异常的处理器先 printStackTrace 再走 uncaught）
     */
    fun installRxJavaErrorPolicy() {
        if (rxJavaErrorPolicyInstalled) {
            return
        }
        rxJavaErrorPolicyInstalled = true
        RxJavaPlugins.setErrorHandler { throwable ->
            try {
                handleRxJavaGlobalError(throwable)
            } catch (selfThrown: Throwable) {
                TtsLogger.e("RxJava error policy handler threw", selfThrown, TAG)
            }
        }
        TtsLogger.i("RxJava error policy installed", tag = TAG)
    }

    /**
     * RxJava 全局错误分诊：到达 [RxJavaPlugins] 全局处理器的错误按类型处置
     *
     * - UndeliverableException（无论内因）：流已取消/已终止的孤儿错误，定义上没有
     *   消费者，其所属业务（本次合成）早已收场——记录 ERROR 日志 + `rx_suppressed`
     *   遥测后吞掉。刻意不做内因类型白名单：SDK 各版本、各传输路径的包装类型
     *   不稳定，"错误无处投递"才是 RxJava 语义层面跨版本稳定的边界
     * - 其余到达全局的错误（经 RxJavaPlugins.isBug 判定以裸形态穿透的六类契约
     *   违约信号，如 OnErrorNotImplementedException）：真 Bug，委托线程未捕获
     *   异常处理器，走既有 [TalkifyExceptionHandler] 崩溃链路，维持现状
     *
     * 单测直接调用本方法断言分诊行为（[installRxJavaErrorPolicy] 仅做装配）
     */
    internal fun handleRxJavaGlobalError(throwable: Throwable) {
        if (throwable is UndeliverableException) {
            TtsLogger.e(
                "Suppressed RxJava undeliverable error (flow already disposed)",
                throwable = throwable,
                tag = TAG
            )
            AppActionTracker.rxSuppressed(
                (throwable.cause ?: throwable).javaClass.name,
                Thread.currentThread().name
            )
            return
        }
        Thread.currentThread().uncaughtExceptionHandler
            ?.uncaughtException(Thread.currentThread(), throwable)
            ?: Process.killProcess(Process.myPid())
    }

    /** 仅供单元测试复位进程级策略状态（安装标志/前代处理器/RxJava 全局处理器），业务代码勿调 */
    internal fun resetForTest() {
        installed = false
        previousHandler = null
        rxJavaErrorPolicyInstalled = false
        RxJavaPlugins.setErrorHandler(null)
    }
}
