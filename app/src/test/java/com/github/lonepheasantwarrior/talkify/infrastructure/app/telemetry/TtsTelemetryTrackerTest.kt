package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TtsTelemetryTrackerTest {

    private var now = 1_000L
    private val events = mutableListOf<Map<String, Any>>()
    private val windows = mutableListOf<Map<String, Any>>()

    private lateinit var boundary: FakePermitCommitBoundary

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
        TtsTelemetryTracker.resetForTest()
        TtsTelemetryTracker.clock = { now }
        TtsTelemetryTracker.successSample = { false }
        TtsTelemetryTracker.trackingAllowed = { true }
        TtsTelemetryTracker.permitProvider = { CollectionPermit(true, permitEpoch) }
        TtsTelemetryTracker.permitValidator = {
            it.enabled && permitCurrent && it.epoch == permitEpoch
        }
        TtsTelemetryTracker.eventReporter = { properties, _ -> events.add(properties) }
        TtsTelemetryTracker.metricsAggregator = TtsMetricsAggregator(
            monoClock = { now },
            wallClock = { now },
            scheduler = ManualWindowScheduler(),
            permitBoundary = boundary,
            sink = { properties, _ -> windows.add(properties) },
        )
    }

    @After
    fun tearDown() {
        TtsTelemetryTracker.resetForTest()
    }

    private fun successAttempt(sampled: Boolean = true): TtsTelemetryTracker.Attempt {
        TtsTelemetryTracker.successSample = { sampled }
        val attempt = TtsTelemetryTracker.begin("openAI", 10)
        attempt.updateMetadata(
            TelemetryIds.model("gpt-4o-mini-tts", "gpt-4o-mini-tts"),
            TelemetryIds.voice("alloy", isKnown = { it == "alloy" }),
        )
        attempt.markSynthesisStarted()
        now += 120
        attempt.markFirstAudio(24_000)
        attempt.recordAudioDelivered(4_096)
        attempt.markProviderCompleted()
        return attempt
    }

    @Test
    fun `框架交付成功产生且只产生一条成功终态`() {
        val attempt = successAttempt(sampled = true)
        now += 80
        attempt.markSuccess()
        attempt.report()
        attempt.report()

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(TtsTelemetryTracker.STATUS_SUCCESS, event["status"])
        assertEquals(1, event["has_audio"])
        assertEquals(1, event["provider_completed"])
        assertEquals(4_096, event["audio_bytes"])
        assertEquals(120, event["first_audio_ms"])
        assertEquals(24_000, event["sample_rate"])
        assertEquals(200, event["duration_ms"])
        assertEquals("gpt-4o-mini-tts", event["model_id"])
        assertEquals("alloy", event["voice_id"])
        assertEquals(1, event["sampled"])
        assertFalse(event.containsKey("failure_stage"))
    }

    @Test
    fun `未抽中的成功明细不上送但计入窗口汇总`() {
        val attempt = successAttempt(sampled = false)
        attempt.markSuccess()
        attempt.report()
        assertEquals(0, events.size)

        TtsTelemetryTracker.flushMetrics()
        val window = windows.single()
        assertEquals(1, window["request_count"])
        assertEquals(1, window["success_count"])
        assertEquals(1, window["has_audio_count"])
    }

    @Test
    fun `供应商完成但零音频不算成功`() {
        val attempt = TtsTelemetryTracker.begin("openAI", 10)
        attempt.markSynthesisStarted()
        attempt.markProviderCompleted()
        attempt.markSuccess()
        attempt.report()

        val event = events.single()
        assertEquals(TtsTelemetryTracker.STATUS_ERROR, event["status"])
        assertEquals(TtsTelemetryTracker.STAGE_SYNTHESIS, event["failure_stage"])
        assertEquals("empty_audio", event["error_code"])
        assertEquals(0, event["has_audio"])
    }

    @Test
    fun `框架拒收覆盖供应商成功`() {
        val attempt = successAttempt(sampled = true)
        attempt.markFrameworkFailure("start_-1")
        attempt.markSuccess()
        attempt.report()

        val event = events.single()
        assertEquals(TtsTelemetryTracker.STATUS_ERROR, event["status"])
        assertEquals(TtsTelemetryTracker.STAGE_FRAMEWORK_DELIVERY, event["failure_stage"])
        assertEquals("start_-1", event["error_code"])
    }

    @Test
    fun `准备阶段失败不被遗漏`() {
        val attempt = TtsTelemetryTracker.begin("openAI", 5)
        attempt.markError("not_configured", TtsTelemetryTracker.STAGE_PREPARATION)
        attempt.report()

        val event = events.single()
        assertEquals(TtsTelemetryTracker.STATUS_ERROR, event["status"])
        assertEquals(TtsTelemetryTracker.STAGE_PREPARATION, event["failure_stage"])
        assertEquals("not_configured", event["error_code"])
    }

    @Test
    fun `超时与取消分别记录`() {
        val timeout = TtsTelemetryTracker.begin("openAI", 5)
        timeout.markSynthesisStarted()
        timeout.markTimeout()
        timeout.report()

        val cancelled = TtsTelemetryTracker.begin("openAI", 5)
        cancelled.markCancelled("user_stopped")
        cancelled.report()

        assertEquals(TtsTelemetryTracker.STATUS_TIMEOUT, events[0]["status"])
        assertEquals(TtsTelemetryTracker.STATUS_CANCELLED, events[1]["status"])
        assertEquals("user_stopped", events[1]["error_code"])
    }

    @Test
    fun `空文本只计ignored不进入成功率分母`() {
        val attempt = TtsTelemetryTracker.begin("openAI", 0)
        attempt.markIgnored("empty_text")
        attempt.report()

        assertEquals(0, events.size)
        TtsTelemetryTracker.flushMetrics()
        val window = windows.single()
        assertEquals(0, window["request_count"])
        assertEquals(1, window["ignored_count"])
    }

    @Test
    fun `未标记终态的统一出口按异常收尾`() {
        val attempt = TtsTelemetryTracker.begin("openAI", 1)
        attempt.report()
        val event = events.single()
        assertEquals(TtsTelemetryTracker.STATUS_ERROR, event["status"])
        assertEquals("no_terminal", event["error_code"])
    }

    @Test
    fun `窗口按供应商与模型类别汇总并包含时延直方图`() {
        repeat(2) {
            val attempt = successAttempt(sampled = false)
            attempt.markSuccess()
            attempt.report()
        }
        val failed = TtsTelemetryTracker.begin("openAI", 8)
        failed.updateMetadata(
            TelemetryIds.model("my-private", "gpt-4o-mini-tts"),
            TelemetryIds.voice("my-voice", isKnown = { false }),
        )
        failed.markSynthesisStarted()
        failed.markError("1004", TtsTelemetryTracker.STAGE_SYNTHESIS)
        failed.report()

        TtsTelemetryTracker.flushMetrics()
        assertEquals(2, windows.size)

        val defaultWindow = windows.first { it["model_category"] == "default" }
        assertEquals("openAI", defaultWindow["provider_id"])
        assertEquals(2, defaultWindow["request_count"])
        assertEquals(2, defaultWindow["success_count"])
        assertEquals(2, defaultWindow["duration_lt_1s"])

        val customWindow = windows.first { it["model_category"] == "custom" }
        assertEquals(1, customWindow["request_count"])
        assertEquals(1, customWindow["error_count"])
        assertEquals(1, customWindow["first_audio_missing"])
    }

    @Test
    fun `关闭统计期间不聚合也不上送`() {
        TtsTelemetryTracker.trackingAllowed = { false }
        val attempt = TtsTelemetryTracker.begin("openAI", 5)
        attempt.markSynthesisStarted()
        attempt.markError("1004", TtsTelemetryTracker.STAGE_SYNTHESIS)
        attempt.report()
        TtsTelemetryTracker.flushMetrics()

        assertTrue(events.isEmpty())
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `损坏或未知供应商归一为unknown`() {
        val attempt = TtsTelemetryTracker.begin("not-a-provider", 1)
        attempt.markError("bad_config", TtsTelemetryTracker.STAGE_PREPARATION)
        attempt.report()

        val event = events.single()
        assertEquals("unknown", event["provider_id"])
        assertTrue(
            TelemetrySchema.prepare(
                TelemetryEvent(
                    name = TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT,
                    url = ScreenTracker.PATH_TTS_SERVICE,
                    origin = TelemetryOrigin.TTS_SERVICE,
                    data = event,
                ),
            ) != null,
        )
    }

    @Test
    fun `TTS明细字段通过事件schema`() {
        val attempt = successAttempt(sampled = true)
        attempt.markSuccess()
        attempt.report()

        val prepared = TelemetrySchema.prepare(
            TelemetryEvent(
                name = TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT,
                url = ScreenTracker.PATH_TTS_SERVICE,
                origin = TelemetryOrigin.TTS_SERVICE,
                data = events.single(),
            ),
        )
        assertTrue(prepared != null)
    }

    @Test
    fun `关闭期间创建的attempt重新开启后不上报`() {
        permitCurrent = false
        val attempt = TtsTelemetryTracker.begin("openAI", 5)
        attempt.markSynthesisStarted()
        attempt.markError("1004", TtsTelemetryTracker.STAGE_SYNTHESIS)
        attempt.report()

        permitCurrent = true
        attempt.report()
        TtsTelemetryTracker.flushMetrics()

        assertTrue(events.isEmpty())
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `跨许可代际的attempt重新开启后不复活`() {
        val attempt = TtsTelemetryTracker.begin("openAI", 5)
        attempt.markSynthesisStarted()
        attempt.markError("1004", TtsTelemetryTracker.STAGE_SYNTHESIS)
        permitEpoch = 2
        attempt.report()
        attempt.report()
        TtsTelemetryTracker.flushMetrics()

        assertTrue(events.isEmpty())
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `首包时间与采样率成组发布`() {
        val attempt = TtsTelemetryTracker.begin("openAI", 3)
        attempt.markSynthesisStarted()
        attempt.markFirstAudio(16_000)
        attempt.markError("1004", TtsTelemetryTracker.STAGE_SYNTHESIS)
        attempt.report()

        val event = events.single()
        assertEquals(0, event["first_audio_ms"])
        assertEquals(16_000, event["sample_rate"])
    }
}
