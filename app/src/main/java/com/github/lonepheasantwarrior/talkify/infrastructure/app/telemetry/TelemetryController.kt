package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.content.Context
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder.UmamiRecorder
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import java.util.concurrent.Executors

/**
 * 遥测控制器（应用级唯一写入口）
 *
 * 统一持有用户许可、界面诊断许可、前后台状态与许可代际：
 * - UI 只提交一个动作（[setTelemetryEnabled] / [setDiagnosticsEnabled]），持久化、
 *   内存状态、队列、在途请求与录制会话由本控制器协调
 * - 每次许可变更递增 `policyEpoch`；关闭时先禁止新采集/发送，再清空队列、取消
 *   统计请求、清空会话缓存并丢弃录制缓冲
 * - 异步生产者（TTS attempt、设备画像、窗口汇总、崩溃摘要）在创建时捕获
 *   [CollectionPermit]，提交时要求仍为同一代际；关闭期间产生的数据不会因重新开启而复活
 * - 基础统计与界面诊断是两个独立许可，后者默认关闭且不继承既有统计开关
 *
 * 技术边界：已经写入网络的字节无法撤回；许可快照能保证关闭返回后不再启动旧请求、
 * 旧响应不能写入新代际。
 */
object TelemetryController {

    private const val TAG = "TalkifyTelemetry"

    private val policy = TelemetryPolicyState()
    private var initialized = false

    @Volatile
    private var diagnosticsEnabled = false

    @Volatile
    private var appForeground = false

    private var hasSeenForeground = false

    /** 设备画像查询与崩溃补报放到后台，避免主线程磁盘/设备查询 */
    private val backgroundExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "talkify-telemetry-bg").apply { isDaemon = true }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val repository = SharedPreferencesAppConfigRepository(context)
        val telemetryEnabled = repository.isTelemetryEnabled()
        diagnosticsEnabled = repository.isDiagnosticsEnabled()
        policy.initialize(telemetryEnabled)
        initialized = true
        applyPolicy()
        UmamiClient.onRemoteDisabled = {
            UmamiRecorder.stop(flushBufferedEvents = false)
        }
        TtsLogger.i(TAG) {
            "遥测初始化: build=${UmamiClient.configState.label} enabled=$telemetryEnabled diagnostics=$diagnosticsEnabled"
        }
        if (!telemetryEnabled) {
            CrashReporter.clearPending()
        } else {
            // 不依赖 Activity：仅由前台 TTS 服务唤醒的进程也要尝试补报上次崩溃摘要
            backgroundExecutor.execute { CrashReporter.flushPendingIfAllowed() }
        }
    }

    fun isTelemetryEnabled(): Boolean = policy.isEnabled()

    fun isDiagnosticsEnabled(): Boolean = diagnosticsEnabled

    fun isAppForeground(): Boolean = appForeground

    fun currentPolicyEpoch(): Long = policy.epoch()

    internal fun capturePermit(): CollectionPermit = policy.capture()

    internal fun isPermitCurrent(permit: CollectionPermit): Boolean = policy.isCurrent(permit)

    /** 在许可边界内提交（崩溃摘要写入等）；关闭会等待提交结束后再清理 */
    internal fun <T> runIfPermitCurrent(permit: CollectionPermit, block: () -> T): T? =
        policy.runIfCurrent(permit, block)

    /**
     * 切换匿名使用统计开关（持久化 + 热生效）
     *
     * 关闭时立即丢弃在途/待发事件与录制缓冲；重新开启不补发关闭期间的事件。
     */
    fun setTelemetryEnabled(context: Context, enabled: Boolean) {
        synchronized(this) {
            if (!policy.setEnabled(enabled)) return
            SharedPreferencesAppConfigRepository(context).setTelemetryEnabled(enabled)
            applyPolicy()
            if (!enabled) {
                UmamiRecorder.stop(flushBufferedEvents = false)
                CrashReporter.clearPending()
                TtsTelemetryTracker.clearMetrics()
            } else {
                // 关闭期间可能仍有并发 report 落入汇总器：开启前清掉旧代际窗口；
                // 清理只针对旧代际，不会删除重开后已建立的新窗口
                TtsTelemetryTracker.clearMetrics()
                if (appForeground) {
                    UmamiClient.onForegroundProbe()
                    ScreenTracker.restartSession()
                    if (diagnosticsEnabled) UmamiRecorder.start()
                }
                backgroundExecutor.execute { CrashReporter.flushPendingIfAllowed() }
            }
        }
    }

    /**
     * 切换界面诊断开关（默认关闭，独立于基础统计）
     *
     * 当前版本录制实现处于关闭状态，开关只改变本地许可，不会产生采集流量。
     */
    fun setDiagnosticsEnabled(context: Context, enabled: Boolean) {
        synchronized(this) {
            if (diagnosticsEnabled == enabled) return
            diagnosticsEnabled = enabled
            SharedPreferencesAppConfigRepository(context).setDiagnosticsEnabled(enabled)
            if (enabled) {
                if (policy.isEnabled() && appForeground) UmamiRecorder.start()
            } else {
                UmamiRecorder.stop(flushBufferedEvents = false)
            }
        }
    }

    fun onAppForeground() {
        synchronized(this) {
            appForeground = true
            // 始终维护 ScreenTracker 的前后台状态（其上报仍受统计许可门控），
            // 否则关闭统计期间进入前台、随后重新开启时无法锚定当前页面
            if (policy.isEnabled()) {
                UmamiClient.onForegroundProbe()
            }
            ScreenTracker.onAppForeground()
            val source = if (hasSeenForeground) "resume" else "cold"
            hasSeenForeground = true
            if (!policy.isEnabled()) return
            // pageview 已在 ScreenTracker.onAppForeground 中先入队作为会话锚点；
            // 崩溃补报与设备画像都放到后台线程，避免主线程做磁盘读取
            backgroundExecutor.execute { CrashReporter.flushPendingIfAllowed() }
            val context = TalkifyAppHolder.getContext()
            val permit = policy.capture()
            if (context != null) {
                // 设备画像在后台查询后再提交；提交前复核捕获时的许可代际，
                // 关闭/重开期间完成的旧任务不得把画像写入新会话
                backgroundExecutor.execute {
                    try {
                        if (!isPermitCurrent(permit)) return@execute
                        AppActionTracker.appForegrounded(
                            source,
                            DeviceInfoCollector.collect(context),
                            permit,
                        )
                    } catch (e: Throwable) {
                        TtsLogger.w(TAG) { "设备画像采集失败: ${e.javaClass.simpleName}" }
                    }
                }
            } else {
                AppActionTracker.appForegrounded(source, emptyMap(), permit)
            }
            if (diagnosticsEnabled) UmamiRecorder.start()
        }
    }

    fun onAppBackground() {
        synchronized(this) {
            appForeground = false
            ScreenTracker.onAppBackground()
            UmamiRecorder.stop()
            TtsTelemetryTracker.flushMetrics()
        }
    }

    private fun applyPolicy() {
        UmamiClient.setPolicy(policy.isEnabled(), policy.epoch())
    }

    /** 仅供单测复位（进程级单例） */
    internal fun resetForTest() {
        synchronized(this) {
            initialized = false
            diagnosticsEnabled = false
            appForeground = false
            hasSeenForeground = false
            policy.initialize(false)
        }
        UmamiClient.resetForTest()
    }
}
