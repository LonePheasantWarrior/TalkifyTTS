package com.github.lonepheasantwarrior.talkify

import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.exceptions.OnErrorNotImplementedException
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketException

/**
 * [TalkifyExceptionHandler] RxJava 全局错误策略单测（纯 JVM，RxJava 经 DashScope
 * SDK 传递依赖进入测试 classpath）
 *
 * 覆盖 doc/local/崩溃问题修复方案.md §6.1 用例表：#0 对照装置自证、#1 dispose 竞态
 * 孤儿错误端到端回归、#2 UndeliverableException 吞掉、#3/#4 isBug 裸形态崩溃语义
 * 保持、#5 安装幂等。分诊类用例直接调用 [TalkifyExceptionHandler.handleRxJavaGlobalError]
 * 精确断言（装配与分诊分离），#0/#1 走真实 RxJava 管线端到端验证。
 *
 * 注意：RxJava 错误处理器与 Thread 默认未捕获处理器均为进程级全局状态，
 * 每个用例前后必须复位/恢复，避免用例间污染。
 */
class TalkifyExceptionHandlerTest {

    /** 记录型假处理器：替代系统默认处理器，观察"是否走到崩溃路径"与到达的异常 */
    private class RecordingUncaughtHandler : Thread.UncaughtExceptionHandler {
        val errors = mutableListOf<Throwable>()
        val threads = mutableListOf<Thread>()

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            threads += thread
            errors += throwable
        }
    }

    private lateinit var fakeDefaultHandler: RecordingUncaughtHandler
    private var originalDefaultHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        originalDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        fakeDefaultHandler = RecordingUncaughtHandler()
        Thread.setDefaultUncaughtExceptionHandler(fakeDefaultHandler)
        TalkifyExceptionHandler.resetRxJavaErrorPolicyForTest()
    }

    @After
    fun tearDown() {
        TalkifyExceptionHandler.resetRxJavaErrorPolicyForTest()
        Thread.setDefaultUncaughtExceptionHandler(originalDefaultHandler)
    }

    // ==================== 用例 #0 对照组（装置自证） ====================

    /**
     * 不安装策略时，同样的孤儿错误到达线程默认未捕获处理器——
     * 证明测试装置能观察到崩溃路径，防止后续"被吞"断言假阳性
     * （可容忍 RxJava 默认路径的 printStackTrace 噪声）
     */
    @Test
    fun controlWithoutPolicyOrphanErrorReachesDefaultUncaughtHandler() {
        triggerDisposedCancellableOrphanError()

        assertEquals(1, fakeDefaultHandler.errors.size)
        // 孤儿错误经 RxJavaPlugins.onError 统一包装（RxJava 2.2.21 源码核实）
        assertTrue(fakeDefaultHandler.errors[0] is UndeliverableException)
        assertEquals("Socket is closed", fakeDefaultHandler.errors[0].cause?.message)
    }

    // ==================== 用例 #1 端到端回归 ====================

    /**
     * 模拟真实竞态：订阅后立即 dispose，Cancellable 抛 SocketException。
     * 修复前该用例命中崩溃路径（见对照用例），安装策略后孤儿错误被吞，
     * 未捕获处理器不被调用
     */
    @Test
    fun policySwallowsOrphanErrorFromDisposedCancellable() {
        TalkifyExceptionHandler.installRxJavaErrorPolicy()
        triggerDisposedCancellableOrphanError()

        assertTrue(fakeDefaultHandler.errors.isEmpty())
    }

    // ==================== 用例 #2 ====================

    /** UndeliverableException 一律吞掉（与内因类型无关），交不到未捕获处理器 */
    @Test
    fun policySwallowsUndeliverableExceptionRegardlessOfCause() {
        TalkifyExceptionHandler.installRxJavaErrorPolicy()
        TalkifyExceptionHandler.handleRxJavaGlobalError(
            UndeliverableException(SocketException("Socket is closed"))
        )

        assertTrue(fakeDefaultHandler.errors.isEmpty())
    }

    // ==================== 用例 #3 / #4 isBug 裸形态维持崩溃语义 ====================

    /** 真 Bug 信号（isBug 分类，不经 UndeliverableException 包装）委托未捕获处理器 */
    @Test
    fun policyDelegatesBareBugExceptionToUncaughtHandler() {
        TalkifyExceptionHandler.installRxJavaErrorPolicy()
        val bug = RuntimeException("bug")
        TalkifyExceptionHandler.handleRxJavaGlobalError(bug)

        assertEquals(listOf(bug), fakeDefaultHandler.errors)
        assertEquals(Thread.currentThread(), fakeDefaultHandler.threads.single())
    }

    @Test
    fun policyDelegatesOnErrorNotImplementedExceptionToUncaughtHandler() {
        TalkifyExceptionHandler.installRxJavaErrorPolicy()
        val bug = OnErrorNotImplementedException(IllegalArgumentException("missing onError"))
        TalkifyExceptionHandler.handleRxJavaGlobalError(bug)

        assertEquals(listOf(bug), fakeDefaultHandler.errors)
    }

    // ==================== 用例 #5 幂等 ====================

    /** 连续安装两次：无异常抛出，策略行为不变（孤儿错误仍被吞） */
    @Test
    fun installIsIdempotent() {
        TalkifyExceptionHandler.installRxJavaErrorPolicy()
        TalkifyExceptionHandler.installRxJavaErrorPolicy()
        triggerDisposedCancellableOrphanError()

        assertTrue(fakeDefaultHandler.errors.isEmpty())
    }

    /**
     * 触发与线上一致的孤儿错误：Flowable 订阅后立即 dispose，emitter 的
     * Cancellable 抛 SocketException（dispose 掐断在途 SSE 流的等价形态）。
     * 全程同步发生在当前测试线程：Cancellable 异常经 CancellableDisposable →
     * RxJavaPlugins.onError，无处理器时默认委托线程未捕获处理器
     */
    private fun triggerDisposedCancellableOrphanError() {
        val disposable = Flowable.create<Any>({ emitter ->
            emitter.setCancellable { throw SocketException("Socket is closed") }
        }, BackpressureStrategy.BUFFER).subscribe()
        disposable.dispose()
    }
}
