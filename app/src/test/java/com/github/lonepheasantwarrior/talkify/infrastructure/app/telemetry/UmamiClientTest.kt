package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UmamiClientTest {

    private val permit = CollectionPermit(enabled = true, epoch = 1)
    private val calls = mutableListOf<FakeCall>()

    @Before
    fun setUp() {
        UmamiClient.resetForTest()
        TelemetryDiagnostics.resetForTest()
        UmamiClient.configOverride = true
        UmamiClient.effectiveBaseUrl = "https://telemetry.test"
        UmamiClient.effectiveWebsiteId = "123e4567-e89b-12d3-a456-426614174000"
        UmamiClient.permitGate = { it.enabled }
        UmamiClient.setPolicy(true, 1)
    }

    @After
    fun tearDown() {
        UmamiClient.resetForTest()
        TelemetryDiagnostics.resetForTest()
    }

    private var responseIndex = 0

    private fun install(handler: (Request, Int) -> Response) {
        calls.clear()
        responseIndex = 0
        UmamiClient.callFactory = { request ->
            FakeCall(request) { handler(request, ++responseIndex) }.also { calls.add(it) }
        }
    }

    private fun event() = TelemetryEvent(
        name = AppActionTracker.EVENT_APP_FOREGROUNDED,
        url = "/",
        origin = TelemetryOrigin.APP,
        data = mapOf("foreground_source" to AppActionTracker.FOREGROUND_COLD),
    )

    private fun sendOne(): Boolean {
        val accepted = UmamiClient.enqueueForTest(event(), permit)
        if (accepted) UmamiClient.drainOnceForTest()
        return accepted
    }

    private fun startDrain(): Thread =
        Thread { UmamiClient.drainOnceForTest() }.apply { isDaemon = true; start() }

    @Test
    fun `关闭返回后未登记的新Call不会执行`() {
        install { request, _ -> fakeResponse(request, 200, """{"cache":"TOKEN_A"}""") }
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        UmamiClient.testHooks = UmamiClient.SendTestHooks(
            beforeRegister = {
                blocked.countDown()
                release.await(2, TimeUnit.SECONDS)
            },
        )
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        val worker = startDrain()

        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        UmamiClient.setPolicy(false, 2)
        release.countDown()
        worker.join(2_000)

        assertEquals(1, calls.size)
        assertEquals(0, calls.single().executeCount)
        assertTrue(calls.single().canceled)
        assertNull(UmamiClient.sessionToken())
    }

    @Test
    fun `登记后关闭会在execute前取消Call`() {
        install { request, _ -> fakeResponse(request, 200, """{"cache":"TOKEN_A"}""") }
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        UmamiClient.testHooks = UmamiClient.SendTestHooks(
            beforeExecute = {
                blocked.countDown()
                release.await(2, TimeUnit.SECONDS)
            },
        )
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        val worker = startDrain()

        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        UmamiClient.setPolicy(false, 2)
        assertTrue(calls.single().canceled)
        release.countDown()
        worker.join(2_000)

        assertEquals(1, calls.single().executeCount)
        assertNull(UmamiClient.sessionToken())
    }

    @Test
    fun `旧响应解析后关闭重开不得写入新会话`() {
        install { request, _ -> fakeResponse(request, 200, """{"cache":"TOKEN_A"}""") }
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        UmamiClient.testHooks = UmamiClient.SendTestHooks(
            beforeApply = {
                blocked.countDown()
                release.await(2, TimeUnit.SECONDS)
            },
        )
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        val worker = startDrain()

        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        UmamiClient.setPolicy(false, 2)
        UmamiClient.setPolicy(true, 3)
        release.countDown()
        worker.join(2_000)

        assertNull(UmamiClient.sessionToken())
        assertEquals(0, TelemetryDiagnostics.snapshot().sent)
    }

    @Test
    fun `旧停用响应不能影响新代际`() {
        install { request, _ -> fakeResponse(request, 200, """{"disabled":true}""") }
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        UmamiClient.testHooks = UmamiClient.SendTestHooks(
            beforeApply = {
                blocked.countDown()
                release.await(2, TimeUnit.SECONDS)
            },
        )
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        val worker = startDrain()

        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        UmamiClient.setPolicy(false, 2)
        UmamiClient.setPolicy(true, 3)
        release.countDown()
        worker.join(2_000)

        assertFalse(UmamiClient.isRemoteDisabled())
    }

    @Test
    fun `旧代际队列条目不会发送`() {
        install { request, _ -> fakeResponse(request, 200, """{"cache":"TOKEN_A"}""") }
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        UmamiClient.setPolicy(true, 2)
        UmamiClient.drainOnceForTest()

        assertTrue(calls.isEmpty())
        assertTrue(TelemetryDiagnostics.snapshot().droppedPolicy >= 1)
    }

    @Test
    fun `2xx空体HTML畸形JSON与类型错误都计为协议错误`() {
        val bodies = listOf(
            null,
            "<html>proxy error</html>",
            "{not-json",
            """{"cache":123}""",
            """{"ok":"true","cache":"X"}""",
            "x".repeat(17 * 1024),
        )
        install { request, index -> fakeResponse(request, 200, bodies[index - 1]) }
        repeat(bodies.size) {
            assertTrue(sendOne())
        }

        val diagnostics = TelemetryDiagnostics.snapshot()
        assertEquals(bodies.size.toLong(), diagnostics.protocolErrors)
        assertEquals(0L, diagnostics.sent)
        assertNull(UmamiClient.sessionToken())
    }

    @Test
    fun `合法cache才更新会话并计成功`() {
        install { request, _ -> fakeResponse(request, 200, """{"cache":"TOKEN_A","disabled":false}""") }
        assertTrue(sendOne())

        assertEquals("TOKEN_A", UmamiClient.sessionToken())
        assertEquals(1L, TelemetryDiagnostics.snapshot().sent)
        assertEquals(0L, TelemetryDiagnostics.snapshot().protocolErrors)
    }

    @Test
    fun `过滤响应不重试且不更新令牌`() {
        install { request, _ -> fakeResponse(request, 200, """{"beep":"boop"}""") }
        assertTrue(sendOne())

        assertEquals(1L, TelemetryDiagnostics.snapshot().filtered)
        assertEquals(0L, TelemetryDiagnostics.snapshot().sent)
        assertEquals(1, calls.size)
        assertEquals(1, calls.single().executeCount)
    }

    @Test
    fun `400未携带令牌按拒绝处理而非无效令牌`() {
        install { request, _ -> fakeResponse(request, 400, """{"error":"invalid token"}""") }
        assertTrue(sendOne())

        val diagnostics = TelemetryDiagnostics.snapshot()
        assertEquals(0L, diagnostics.invalidToken)
        assertEquals(1L, diagnostics.rejected)
        assertEquals(1, calls.single().executeCount)
    }

    @Test
    fun `400携带令牌但非令牌错误不重试也不清缓存`() {
        install { request, index ->
            if (index == 1) {
                fakeResponse(request, 200, """{"cache":"TOKEN_A"}""")
            } else {
                fakeResponse(request, 400, """{"error":"schema validation failed"}""")
            }
        }
        assertTrue(sendOne())
        assertTrue(sendOne())

        assertEquals("TOKEN_A", UmamiClient.sessionToken())
        assertEquals(0L, TelemetryDiagnostics.snapshot().invalidToken)
        assertEquals(1L, TelemetryDiagnostics.snapshot().rejected)
        assertEquals(2, calls.sumOf { it.executeCount })
    }

    @Test
    fun `明确令牌失效清除缓存并受控重建一次`() {
        install { request, index ->
            when (index) {
                1 -> fakeResponse(request, 200, """{"cache":"TOKEN_A"}""")
                2 -> fakeResponse(request, 400, """{"error":"invalid token"}""")
                else -> fakeResponse(request, 200, """{"cache":"TOKEN_B"}""")
            }
        }
        assertTrue(sendOne())
        assertTrue(sendOne())

        assertEquals("TOKEN_B", UmamiClient.sessionToken())
        assertEquals(1L, TelemetryDiagnostics.snapshot().invalidToken)
        assertEquals(3, calls.sumOf { it.executeCount })
    }

    @Test
    fun `RetryAfter一百二十秒不会被截短且后续事件一并受约束`() {
        install { request, _ ->
            fakeResponse(request, 429, "", retryAfter = "120")
        }
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        UmamiClient.drainOnceForTest()
        UmamiClient.drainOnceForTest()

        assertEquals(1, calls.size)
        assertEquals(1, calls.single().executeCount)
        assertEquals(1L, TelemetryDiagnostics.snapshot().httpErrors)
        assertEquals(2L, TelemetryDiagnostics.snapshot().droppedTtl)
    }

    @Test
    fun `重试耗尽的最后一次429仍约束后续事件`() {
        install { request, index ->
            if (index == 3) {
                fakeResponse(request, 429, "", retryAfter = "120")
            } else {
                fakeResponse(request, 500, "")
            }
        }
        UmamiClient.sleeper = { }
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        UmamiClient.drainOnceForTest()

        assertEquals(3, calls.size)
        assertEquals(3L, TelemetryDiagnostics.snapshot().httpErrors)

        UmamiClient.drainOnceForTest()

        assertEquals(3, calls.size)
        assertEquals(1L, TelemetryDiagnostics.snapshot().droppedTtl)
    }

    @Test
    fun `重试耗尽的最后一次503携带RetryAfter仍约束后续事件`() {
        install { request, index ->
            if (index == 3) {
                fakeResponse(request, 503, "", retryAfter = "120")
            } else {
                fakeResponse(request, 500, "")
            }
        }
        UmamiClient.sleeper = { }
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        UmamiClient.drainOnceForTest()

        assertEquals(3, calls.size)

        UmamiClient.drainOnceForTest()

        assertEquals(3, calls.size)
        assertEquals(1L, TelemetryDiagnostics.snapshot().droppedTtl)
    }

    @Test
    fun `令牌重建占用重试预算后最后一次限流仍约束后续事件`() {
        install { request, index ->
            when (index) {
                1 -> fakeResponse(request, 200, """{"cache":"TOKEN_A"}""")
                2 -> fakeResponse(request, 400, """{"error":"invalid token"}""")
                3 -> fakeResponse(request, 500, "")
                else -> fakeResponse(request, 429, "", retryAfter = "120")
            }
        }
        UmamiClient.sleeper = { }
        assertTrue(sendOne())
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        UmamiClient.drainOnceForTest()

        assertEquals(4, calls.size)
        assertEquals(1L, TelemetryDiagnostics.snapshot().invalidToken)

        UmamiClient.drainOnceForTest()

        assertEquals(4, calls.size)
        assertEquals(1L, TelemetryDiagnostics.snapshot().droppedTtl)
    }

    @Test
    fun `关闭许可能立即结束重试等待`() {
        install { request, _ -> fakeResponse(request, 429, "", retryAfter = "5") }
        var sleepCalls = 0
        UmamiClient.sleeper = {
            sleepCalls++
            UmamiClient.setPolicy(false, 2)
        }
        assertTrue(UmamiClient.enqueueForTest(event(), permit))
        UmamiClient.drainOnceForTest()

        assertEquals(1, calls.single().executeCount)
        assertEquals(1, sleepCalls)
        assertTrue(TelemetryDiagnostics.snapshot().droppedPolicy >= 1)
    }
}
