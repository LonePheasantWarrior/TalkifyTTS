package com.github.lonepheasantwarrior.talkify.service.multirole

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.multirole.AttributionReason
import com.github.lonepheasantwarrior.talkify.domain.multirole.AnalyzedSegment
import com.github.lonepheasantwarrior.talkify.domain.multirole.PlanWarnings
import com.github.lonepheasantwarrior.talkify.domain.multirole.SegmentKind
import com.github.lonepheasantwarrior.talkify.domain.multirole.SpeechPlan
import com.github.lonepheasantwarrior.talkify.domain.multirole.SpeechPlanBuilder
import com.github.lonepheasantwarrior.talkify.domain.multirole.SpeechSegment
import com.github.lonepheasantwarrior.talkify.service.delivery.SynthesisCallbackConsumer
import com.github.lonepheasantwarrior.talkify.service.delivery.SynthesisDeliveryBridge
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.testutil.FakeTtsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class SpeechPlanExecutorTest {

    private class RecordingCallback(
        private val maxBuffer: Int = 4096,
    ) : SynthesisCallback {
        var starts = 0
        var doneCalls = 0
        var errors = 0
        val writes = CopyOnWriteArrayList<Int>()

        override fun getMaxBufferSize(): Int = maxBuffer

        override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
            starts++
            return TextToSpeech.SUCCESS
        }

        override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
            writes += length
            return TextToSpeech.SUCCESS
        }

        override fun done(): Int {
            doneCalls++
            return TextToSpeech.SUCCESS
        }

        override fun error() {
            errors++
        }

        override fun error(errorCode: Int) {
            errors++
        }

        override fun hasFinished(): Boolean = doneCalls > 0
        override fun hasStarted(): Boolean = starts > 0
    }

    private fun plan(text: String, vararg segments: SpeechSegment): SpeechPlan =
        SpeechPlan(text, segments.toList(), emptyList(), PlanWarnings(), true)

    private fun execute(
        plan: SpeechPlan,
        provider: FakeTtsProvider,
        expected: AudioConfig? = AudioConfig(sampleRate = 24000),
        callback: RecordingCallback = RecordingCallback(),
    ): Pair<SynthesisCallbackConsumer.DeliveryResult, RecordingCallback> {
        val bridge = SynthesisDeliveryBridge()
        val result = runBlocking {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val executor = SpeechPlanExecutor(scope)
                val job = executor.start(
                    SpeechPlanExecutor.Config(
                        provider = provider,
                        providerConfig = FakeTtsProvider.FakeConfig(),
                        params = SynthesisParams(),
                        plan = plan,
                        bridge = bridge,
                        expectedAudioConfig = expected,
                        timeoutMs = 5_000,
                    ),
                    isCancelled = { false },
                )
                val delivered = SynthesisCallbackConsumer(callback).deliver(
                    bridge, 5_000, isCancelled = { false }
                )
                withTimeoutOrNull(2_000) { job.join() }
                delivered
            } finally {
                scope.cancel()
            }
        }
        return result to callback
    }

    @Test
    fun `two segments run in order with request level voice override`() {
        val provider = FakeTtsProvider { _, _, listener ->
            listener.onAudioAvailable(
                byteArrayOf(1, 0, 2, 0), 24000, 2, 1
            )
            listener.onSynthesisCompleted()
        }
        val plan = plan(
            "你好世界",
            SpeechSegment(0, 2, SegmentKind.SPEECH, null, "voice_a"),
            SpeechSegment(2, 4, SegmentKind.SPEECH, null, "voice_b"),
        )
        val (result, callback) = execute(plan, provider)
        assertEquals(SynthesisCallbackConsumer.Status.SUCCESS, result.status)
        assertEquals(1, callback.starts)
        assertEquals(listOf("你好", "世界"), provider.calls.map { it.text })
        assertEquals(listOf("voice_a", "voice_b"), provider.calls.map { it.voiceOverride })
        assertEquals(8, result.audioBytes.toInt())
    }

    @Test
    fun `single voice segment keeps override null`() {
        val provider = FakeTtsProvider { _, _, listener ->
            listener.onAudioAvailable(byteArrayOf(1, 0), 24000, 2, 1)
            listener.onSynthesisCompleted()
        }
        val plan = plan("text", SpeechSegment(0, 4, SegmentKind.NARRATION, null, ""))
        execute(plan, provider)
        assertEquals(listOf(null), provider.calls.map { it.voiceOverride })
    }

    @Test
    fun `no audio completes as failure and never reports success`() {
        val provider = FakeTtsProvider { _, _, listener -> listener.onSynthesisCompleted() }
        val (result, callback) = execute(
            plan("text", SpeechSegment(0, 4, SegmentKind.NARRATION, null, "")), provider
        )
        assertTrue(result.status != SynthesisCallbackConsumer.Status.SUCCESS)
        assertEquals(0, callback.starts)
        assertEquals(0, callback.doneCalls)
    }

    @Test
    fun `partial audio followed by error reports failure without replay`() {
        val provider = FakeTtsProvider { _, _, listener ->
            listener.onAudioAvailable(byteArrayOf(1, 0, 2, 0), 24000, 2, 1)
            listener.onError("boom")
        }
        val (result, callback) = execute(
            plan("text", SpeechSegment(0, 4, SegmentKind.NARRATION, null, "")), provider
        )
        assertEquals(SynthesisCallbackConsumer.Status.ERROR, result.status)
        assertEquals(1, callback.starts)
        assertTrue(result.audioBytes > 0)
    }

    @Test
    fun `expected format mismatch aborts before delivering mismatched audio`() {
        val provider = FakeTtsProvider { _, _, listener ->
            listener.onAudioAvailable(byteArrayOf(1, 0), 48000, 2, 1)
            listener.onSynthesisCompleted()
        }
        val (result, callback) = execute(
            plan("text", SpeechSegment(0, 4, SegmentKind.NARRATION, null, "")),
            provider,
            expected = AudioConfig(sampleRate = 24000),
        )
        assertEquals(SynthesisCallbackConsumer.Status.ERROR, result.status)
        assertEquals(0, callback.starts)
        assertEquals(SpeechPlanExecutor.TOKEN_FORMAT_MISMATCH, result.errorMessage)
    }

    @Test
    fun `consumer rejects mid stream format changes`() {
        val provider = FakeTtsProvider { _, _, listener ->
            listener.onAudioAvailable(byteArrayOf(1, 0), 24000, 2, 1)
            listener.onAudioAvailable(byteArrayOf(1, 0), 48000, 2, 1)
            listener.onSynthesisCompleted()
        }
        val bridge = SynthesisDeliveryBridge()
        val callback = RecordingCallback()
        val result = runBlocking {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val executor = SpeechPlanExecutor(scope)
                val job = executor.start(
                    SpeechPlanExecutor.Config(
                        provider = provider,
                        providerConfig = FakeTtsProvider.FakeConfig(),
                        params = SynthesisParams(),
                        plan = plan("text", SpeechSegment(0, 4, SegmentKind.NARRATION, null, "")),
                        bridge = bridge,
                        expectedAudioConfig = null,
                    ),
                    isCancelled = { false },
                )
                val delivered = SynthesisCallbackConsumer(callback).deliver(bridge, 5_000) { false }
                withTimeoutOrNull(2_000) { job.join() }
                delivered
            } finally {
                scope.cancel()
            }
        }
        assertEquals(SynthesisCallbackConsumer.Status.FORMAT_MISMATCH, result.status)
        assertEquals(1, callback.starts)
    }

    @Test
    fun `writes never exceed callback max buffer and stay frame aligned`() {
        val provider = FakeTtsProvider { _, _, listener ->
            listener.onAudioAvailable(ByteArray(10) { 1 }, 24000, 2, 1)
            listener.onSynthesisCompleted()
        }
        val callback = RecordingCallback(maxBuffer = 4)
        execute(
            plan("text", SpeechSegment(0, 4, SegmentKind.NARRATION, null, "")),
            provider,
            callback = callback,
        )
        assertEquals(listOf(4, 4, 2), callback.writes.toList())
    }

    @Test
    fun `bridge cancellation wakes consumer and stops next segment`() {
        val bridge = SynthesisDeliveryBridge()
        val provider = FakeTtsProvider { _, _, _ ->
            // 不回调任何终态，模拟供应商挂起
        }
        val result = runBlocking {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val executor = SpeechPlanExecutor(scope)
                val job = executor.start(
                    SpeechPlanExecutor.Config(
                        provider = provider,
                        providerConfig = FakeTtsProvider.FakeConfig(),
                        params = SynthesisParams(),
                        plan = plan(
                            "text",
                            SpeechSegment(0, 2, SegmentKind.NARRATION, null, ""),
                            SpeechSegment(2, 4, SegmentKind.NARRATION, null, ""),
                        ),
                        bridge = bridge,
                    ),
                    isCancelled = { false },
                )
                scope.launch {
                    delay(50.milliseconds)
                    bridge.cancel()
                }
                val delivered = SynthesisCallbackConsumer(RecordingCallback())
                    .deliver(bridge, 5_000) { false }
                job.cancel()
                delivered
            } finally {
                scope.cancel()
            }
        }
        assertEquals(SynthesisCallbackConsumer.Status.CANCELLED, result.status)
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun `builder merges adjacent same voice but preserves every character`() {
        val profile = com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile(
            id = com.github.lonepheasantwarrior.talkify.domain.model.multirole.ProfileId("p"),
            name = "p",
            providerId = "fake",
            narratorVoice = com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef("n"),
            defaultRoleVoice = com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef("d"),
        )
        val text = "你好世界"
        val analysis = listOf(
            AnalyzedSegment(0, 2, SegmentKind.SPEECH, null, "张三", false, "v", AttributionReason.LEFT_LABEL),
            AnalyzedSegment(2, 4, SegmentKind.SPEECH, null, "张三", false, "v", AttributionReason.LEFT_LABEL),
        )
        val built = SpeechPlanBuilder.build(text, analysis, profile)
        assertEquals(1, built.segments.size)
        assertEquals("你好世界", built.segments.joinToString("") {
            text.substring(it.start, it.endExclusive)
        })
    }

    @Test
    fun `punctuation-only ranges are attached not dropped`() {
        val profile = com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile(
            id = com.github.lonepheasantwarrior.talkify.domain.model.multirole.ProfileId("p"),
            name = "p",
            providerId = "fake",
            narratorVoice = com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef("n"),
            defaultRoleVoice = com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef("d"),
        )
        val text = "。“你好”。"
        val analysis = listOf(
            AnalyzedSegment(0, 1, SegmentKind.NARRATION, null, null, false, "n", null),
            AnalyzedSegment(1, 4, SegmentKind.SPEECH, null, "张三", false, "v", AttributionReason.LEFT_LABEL),
            AnalyzedSegment(4, 5, SegmentKind.NARRATION, null, null, false, "n", null),
        )
        val built = SpeechPlanBuilder.build(text, analysis, profile)
        assertEquals(text, built.segments.joinToString("") {
            text.substring(it.start, it.endExclusive)
        })
        assertEquals(1, built.segments.size)
    }

    @Test
    fun `default settings are disabled`() {
        assertEquals(false, MultiRoleSettings().enabled)
    }
}
