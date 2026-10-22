package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CrashReporterTest {

    /** 记录 cause 读取次数，用于验证空栈异常链也受深度预算约束 */
    private class CountingCauseThrowable(
        private val next: Throwable?,
        private val reads: AtomicInteger,
    ) : Throwable() {

        init {
            stackTrace = emptyArray()
        }

        override val cause: Throwable?
            get() {
                reads.incrementAndGet()
                return next
            }
    }

    private fun throwableWithFrame(
        message: String,
        frameClass: String = "com.github.lonepheasantwarrior.talkify.service.TalkifyTtsService",
        cause: Throwable? = null,
    ): Throwable = RuntimeException(message, cause).apply {
        stackTrace = arrayOf(
            StackTraceElement(frameClass, "processRequestSynchronously", "TalkifyTtsService.kt", 723),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 1012),
        )
    }

    @Test
    fun `摘要只保留异常类名链与应用栈帧`() {
        val root = throwableWithFrame("root-secret")
        val middle = throwableWithFrame("middle-secret", cause = root)
        val outer = throwableWithFrame("outer-secret", cause = middle)

        val summary = CrashReporter.buildSummary(Thread("main"), outer)

        assertEquals("java.lang.RuntimeException", summary.exceptionClass)
        assertEquals(3, summary.exceptionChainDepth)
        assertTrue(summary.frames.isNotEmpty())
        assertTrue(summary.frames.first().startsWith("com.github.lonepheasantwarrior.talkify"))
        assertTrue(summary.frames.first().contains(":723"))
        assertFalse(summary.frames.any { it.contains("secret") })
        assertEquals("main", summary.threadType)
    }

    @Test
    fun `异常链最多三级且后台线程按background归类`() {
        val fourth = throwableWithFrame("d")
        val third = throwableWithFrame("c", cause = fourth)
        val second = throwableWithFrame("b", cause = third)
        val first = throwableWithFrame("a", cause = second)

        val summary = CrashReporter.buildSummary(Thread("worker-7"), first)

        assertEquals(3, summary.exceptionChainDepth)
        assertEquals("background", summary.threadType)
    }

    @Test
    fun `无应用栈帧时回退到最外层普通帧`() {
        val throwable = throwableWithFrame("x", frameClass = "java.util.concurrent.ThreadPoolExecutor")
        val summary = CrashReporter.buildSummary(Thread("main"), throwable)
        assertTrue(summary.frames.first().startsWith("java.util.concurrent.ThreadPoolExecutor"))
        assertTrue(summary.frames.size <= 8)
    }

    @Test
    fun `栈帧长度受限且包含构建版本`() {
        val longClass = "com.github.lonepheasantwarrior.talkify." + "VeryLongSegment".repeat(20)
        val summary = CrashReporter.buildSummary(
            Thread("main"),
            throwableWithFrame("x", frameClass = longClass),
        )
        assertTrue(summary.frames.all { it.length <= 160 })
        assertTrue(summary.appVersion.isNotBlank())
        assertTrue(summary.buildChannel == "debug" || summary.buildChannel == "release")
    }

    @Test(timeout = 2_000)
    fun `循环cause在有限步骤内结束`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.stackTrace = emptyArray()
        b.stackTrace = emptyArray()
        a.initCause(b)
        b.initCause(a)

        val summary = CrashReporter.buildSummary(Thread("main"), a)

        assertTrue(summary.exceptionChainDepth <= 3)
        assertTrue(summary.frames.isEmpty())
    }

    @Test(timeout = 2_000)
    fun `超深cause链受最大深度约束`() {
        var cause: Throwable = RuntimeException("0").apply { stackTrace = emptyArray() }
        repeat(1_000) { index ->
            cause = RuntimeException("$index", cause).apply { stackTrace = emptyArray() }
        }

        val summary = CrashReporter.buildSummary(Thread("worker"), cause)

        assertEquals(3, summary.exceptionChainDepth)
    }

    @Test(timeout = 2_000)
    fun `超大栈只扫描固定预算`() {
        val throwable = RuntimeException("x").apply {
            stackTrace = Array(10_000) { index ->
                StackTraceElement(
                    "com.github.lonepheasantwarrior.talkify.Deep$index",
                    "run",
                    "Deep.kt",
                    index,
                )
            }
        }

        val summary = CrashReporter.buildSummary(Thread("main"), throwable)

        assertTrue(summary.frames.size <= 8)
        assertTrue(summary.frames.all { it.length <= 160 })
    }

    @Test(timeout = 2_000)
    fun `空栈深链的帧扫描仍受cause深度预算约束`() {
        val reads = AtomicInteger(0)
        var nested: Throwable = CountingCauseThrowable(null, reads)
        repeat(1_000) {
            nested = CountingCauseThrowable(nested, reads)
        }

        val summary = CrashReporter.buildSummary(Thread("main"), nested)

        assertEquals(CrashReporter.MAX_CHAIN, summary.exceptionChainDepth)
        assertTrue(summary.frames.isEmpty())
        assertTrue(
            "cause读取=${reads.get()} 应不超过类名链与帧扫描两段预算",
            reads.get() <= CrashReporter.MAX_CHAIN + CrashReporter.MAX_CAUSE_DEPTH,
        )
    }

    @Test
    fun `损坏或超大摘要解析失败`() {
        assertNull(CrashReporter.parse("not-json"))
        assertNull(CrashReporter.parse("{}"))
        assertNull(CrashReporter.parse("x".repeat(5_000)))
    }

    @Test
    fun `生成的哈希摘要帧可直接通过事件schema`() {
        val summary = CrashReporter.buildSummary(
            Thread("main"),
            throwableWithFrame(
                "secret",
                frameClass = "com.github.lonepheasantwarrior.talkify." + "VeryLongSegment".repeat(20),
            ),
        )
        val prepared = TelemetrySchema.prepare(
            TelemetryEvent(
                name = AppActionTracker.EVENT_APP_CRASH,
                url = "/",
                origin = TelemetryOrigin.CRASH_REPAIR,
                data = AppActionTracker.crashProperties(summary),
            ),
        )
        assertTrue("截断后的帧必须仍是合法签名", prepared != null)
    }

    @Test
    fun `摘要超过固定保留时限后过期`() {
        val summary = CrashReporter.CrashSummary(
            exceptionClass = "java.lang.RuntimeException",
            exceptionChain = listOf("java.lang.RuntimeException"),
            frames = emptyList(),
            threadType = "main",
            timestampSec = 1_000,
            appVersion = "1.0",
            versionCode = 1,
            buildChannel = "release",
        )
        val recordedMs = 1_000_000L
        assertFalse(CrashReporter.isExpired(summary, recordedMs + CrashReporter.PENDING_TTL_MS))
        assertTrue(CrashReporter.isExpired(summary, recordedMs + CrashReporter.PENDING_TTL_MS + 1))
    }
}
