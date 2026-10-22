package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TtsMetricsAggregatorTest {

    private var now = 1_000L
    private lateinit var boundary: FakePermitCommitBoundary
    private val windows = mutableListOf<Map<String, Any>>()
    private val scheduler = ManualWindowScheduler()
    private lateinit var aggregator: TtsMetricsAggregator

    private var permitEpoch: Long
        get() = boundary.epoch
        set(value) {
            boundary.epoch = value
        }

    private var permitCurrent: Boolean
        get() = boundary.enabled
        set(value) {
            boundary.enabled = value
        }

    @Before
    fun setUp() {
        boundary = FakePermitCommitBoundary()
        aggregator = TtsMetricsAggregator(
            monoClock = { now },
            wallClock = { now },
            scheduler = scheduler,
            permitBoundary = boundary,
            sink = { properties, _ -> windows.add(properties) },
        )
    }

    private fun summary(
        status: String = TtsTelemetryTracker.STATUS_SUCCESS,
        textLength: Int = 10,
    ) = TtsMetricsAggregator.Summary(
        providerId = "openAI",
        modelCategory = TelemetryIds.CATEGORY_DEFAULT,
        status = status,
        hasAudio = status == TtsTelemetryTracker.STATUS_SUCCESS,
        providerCompleted = true,
        textLength = textLength,
        durationMs = 100,
        prepareMs = 10,
        synthesisMs = 80,
        firstAudioMs = 20,
        audioBytes = 1_024,
    )

    private fun permit(epoch: Long = permitEpoch) = CollectionPermit(true, epoch)

    @Test
    fun `第一个窗口安排五分钟后到期且无需后续请求即可发送`() {
        aggregator.record(summary(), permit())
        assertEquals(listOf(TtsMetricsAggregator.WINDOW_MS), scheduler.scheduledDelays)

        scheduler.fireActive()

        assertEquals(1, windows.size)
        assertEquals(1, windows.single()["request_count"])
        assertTrue(scheduler.activeCount() == 0)
    }

    @Test
    fun `恰好五百次立即冲刷不再等待下一次请求`() {
        repeat(TtsMetricsAggregator.MAX_REQUESTS) {
            aggregator.record(summary(), permit())
        }

        assertEquals(1, windows.size)
        assertEquals(500, windows.single()["request_count"])
        assertTrue(scheduler.activeCount() == 0)

        aggregator.record(summary(), permit())
        assertEquals(1, windows.size)
        assertEquals(TtsMetricsAggregator.WINDOW_MS, scheduler.scheduledDelays.last())
        assertEquals(1, scheduler.activeCount())
    }

    @Test
    fun `到期与五百次竞争只消费一次`() {
        repeat(TtsMetricsAggregator.MAX_REQUESTS) {
            aggregator.record(summary(), permit())
        }
        scheduler.fireActive()

        assertEquals(1, windows.size)
    }

    @Test
    fun `撤回许可取消定时任务且旧窗口不恢复`() {
        aggregator.record(summary(), permit())
        permitEpoch = 2
        aggregator.discardStale()
        scheduler.fireActive()

        assertTrue(windows.isEmpty())
        assertEquals(0, scheduler.activeCount())
    }

    @Test
    fun `窗口字段通过事件schema`() {
        aggregator.record(summary(), permit())
        aggregator.flush()

        val prepared = TelemetrySchema.prepare(
            TelemetryEvent(
                name = TtsTelemetryTracker.EVENT_TTS_METRICS_WINDOW,
                url = ScreenTracker.PATH_TTS_SERVICE,
                origin = TelemetryOrigin.TTS_SERVICE,
                data = windows.single(),
            ),
        )
        assertTrue(prepared != null)
    }

    @Test
    fun `许可代际失效后到期任务不发送旧窗口`() {
        aggregator.record(summary(), permit())
        permitEpoch = 2
        scheduler.fireActive()

        assertTrue(windows.isEmpty())
    }

    @Test
    fun `许可代际失效后显式冲刷也不发送旧窗口`() {
        aggregator.record(summary(), permit())
        permitCurrent = false
        aggregator.flush()

        assertTrue(windows.isEmpty())
    }

    @Test
    fun `文本字符数按Long累计后限幅`() {
        repeat(500) {
            aggregator.record(summary(textLength = Int.MAX_VALUE), permit())
        }

        assertEquals(Int.MAX_VALUE.toLong(), windows.single()["text_chars"])
    }

    @Test
    fun `关闭期间创建的窗口不上送`() {
        permitCurrent = false
        aggregator.record(summary(), permit())
        permitCurrent = true
        aggregator.flush()

        assertTrue(windows.isEmpty())
        assertEquals(0, scheduler.activeCount())
    }

    @Test
    fun `旧代际record恢复后不删除新代际窗口`() {
        aggregator.record(summary(), permit(1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        boundary.beforeCommit = {
            boundary.beforeCommit = null
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        val stale = Thread {
            aggregator.record(summary(), permit(1))
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        permitEpoch = 3
        aggregator.record(summary(), permit(3))
        release.countDown()
        stale.join(2_000)

        aggregator.flush()
        assertEquals(1, windows.size)
        assertEquals(1, windows.single()["request_count"])
    }

    @Test
    fun `旧代际flush恢复后不清理新代际窗口`() {
        aggregator.record(summary(), permit(1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        boundary.beforeCommit = {
            boundary.beforeCommit = null
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        val staleFlush = Thread { aggregator.flush() }.apply { isDaemon = true; start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        permitEpoch = 3
        aggregator.record(summary(), permit(3))
        release.countDown()
        staleFlush.join(2_000)

        assertTrue("失效flush不得发送旧窗口", windows.isEmpty())
        aggregator.flush()
        assertEquals(1, windows.size)
        assertEquals(1, windows.single()["request_count"])
    }

    @Test
    fun `许可切换不会插入已通过校验的窗口提交`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        boundary.afterCheck = {
            boundary.afterCheck = null
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        val first = Thread {
            aggregator.record(summary(), permit(1))
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val switched = CountDownLatch(1)
        val second = Thread {
            boundary.switchTo(3)
            aggregator.record(summary(), permit(3))
            switched.countDown()
        }.apply { isDaemon = true; start() }
        assertFalse("提交进行中时关闭/重开必须等待", switched.await(200, TimeUnit.MILLISECONDS))

        release.countDown()
        first.join(2_000)
        assertTrue(switched.await(2, TimeUnit.SECONDS))
        second.join(2_000)

        aggregator.flush()
        assertEquals(1, windows.size)
        assertEquals(1, windows.single()["request_count"])
    }

    @Test
    fun `撤回清理后旧record不会重建窗口或定时任务`() {
        aggregator.record(summary(), permit(1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        boundary.beforeCommit = {
            boundary.beforeCommit = null
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        val stale = Thread {
            aggregator.record(summary(), permit(1))
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        permitEpoch = 2
        aggregator.discardStale()
        release.countDown()
        stale.join(2_000)

        assertEquals(0, scheduler.activeCount())
        scheduler.fireActive()
        assertTrue(windows.isEmpty())
    }
}
