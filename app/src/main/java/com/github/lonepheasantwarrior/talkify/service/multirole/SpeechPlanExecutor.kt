package com.github.lonepheasantwarrior.talkify.service.multirole

import com.github.lonepheasantwarrior.talkify.domain.multirole.SpeechPlan
import com.github.lonepheasantwarrior.talkify.domain.multirole.SpeechSegment
import com.github.lonepheasantwarrior.talkify.service.delivery.SynthesisDeliveryBridge
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * 共享顺序执行器：系统 TTS 与多角色试听复用同一份计划、同一套音色覆盖与失败契约，
 * 只是音频输出适配器不同（框架 callback vs AudioTrack）。
 *
 * 线程模型：
 * - 本执行器运行在独立的生产者协程；供应商回调线程经
 *   [SynthesisDeliveryBridge] 有界入队；
 * - 消费方（合成线程/试听协程）串行读取事件；生产者不会在合成线程上等待队列；
 * - 取消会同时作废请求代数、清空队列并停止供应商。
 */
class SpeechPlanExecutor(private val scope: CoroutineScope) {

    enum class FailureKind {
        FORMAT_MISMATCH,
        ZERO_AUDIO,
        PROVIDER_ERROR,
        TIMEOUT,
    }

    data class Config(
        val provider: TtsProviderApi,
        val providerConfig: com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig,
        val params: SynthesisParams,
        val plan: SpeechPlan,
        val bridge: SynthesisDeliveryBridge,
        val expectedAudioConfig: AudioConfig? = null,
        val timeoutMs: Long = 120_000L,
    )

    /**
     * 启动顺序合成。返回的生产者 Job 由调用方负责取消；取消后应同时
     * `bridge.cancel()` 以唤醒消费方。
     */
    fun start(
        config: Config,
        isCancelled: () -> Boolean,
    ): Job = scope.launch {
        try {
            val deadline = System.nanoTime() + config.timeoutMs * 1_000_000
            for (segment in config.plan.segments) {
                if (isCancelled() || config.bridge.isCancelled) {
                    config.bridge.cancel()
                    return@launch
                }
                val text = config.plan.text.substring(segment.start, segment.endExclusive)
                if (text.isEmpty()) continue
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) {
                    config.bridge.publishFailed(TOKEN_TIMEOUT)
                    return@launch
                }
                val outcome = executeSegment(
                    config = config,
                    segment = segment,
                    text = text,
                    remainingMs = remainingMs,
                    isCancelled = isCancelled,
                )
                when (outcome) {
                    is SegmentOutcome.Success -> continue
                    is SegmentOutcome.Failure -> {
                        if (outcome.kind == FailureKind.TIMEOUT) {
                            config.bridge.publishFailed(TOKEN_TIMEOUT)
                        } else {
                            config.bridge.publishFailed(outcome.message ?: tokenFor(outcome.kind))
                        }
                        try {
                            config.provider.stop()
                        } catch (_: Exception) {
                        }
                        return@launch
                    }
                }
            }
            if (!isCancelled() && !config.bridge.isCancelled) {
                config.bridge.publishCompleted()
            } else {
                config.bridge.cancel()
            }
        } catch (e: CancellationException) {
            config.bridge.cancel()
            throw e
        } catch (e: Exception) {
            config.bridge.publishFailed(e.message ?: TOKEN_SYNTHESIS_FAILED)
        }
    }

    private suspend fun executeSegment(
        config: Config,
        segment: SpeechSegment,
        text: String,
        remainingMs: Long,
        isCancelled: () -> Boolean,
    ): SegmentOutcome {
        val done = AtomicBoolean(false)
        val segmentBytes = AtomicLong(0)
        val deferred = CompletableDeferred<SegmentOutcome>()
        val segmentParams = if (segment.voiceId.isBlank()) {
            config.params
        } else {
            config.params.copy(voiceOverride = segment.voiceId)
        }
        val listener = object : TtsSynthesisListener {
            override fun onSynthesisStarted() = Unit

            override fun onAudioAvailable(
                audioData: ByteArray,
                sampleRate: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                if (done.get() || isCancelled()) return
                val expected = config.expectedAudioConfig
                if (expected != null &&
                    (expected.sampleRate != sampleRate ||
                        expected.audioFormat != audioFormat ||
                        expected.channelCount != channelCount)
                ) {
                    if (done.compareAndSet(false, true)) {
                        deferred.complete(
                            SegmentOutcome.Failure(
                                FailureKind.FORMAT_MISMATCH,
                                TOKEN_FORMAT_MISMATCH,
                            )
                        )
                    }
                    return
                }
                val published = config.bridge.publishAudio(
                    audioData, sampleRate, audioFormat, channelCount
                )
                if (published) {
                    segmentBytes.addAndGet(audioData.size.toLong())
                } else if (done.compareAndSet(false, true)) {
                    deferred.complete(SegmentOutcome.Failure(FailureKind.PROVIDER_ERROR, null))
                }
            }

            override fun onSynthesisCompleted() {
                if (!done.compareAndSet(false, true)) return
                val bytes = segmentBytes.get()
                if (bytes <= 0) {
                    deferred.complete(
                        SegmentOutcome.Failure(FailureKind.ZERO_AUDIO, TOKEN_ZERO_AUDIO)
                    )
                } else {
                    deferred.complete(SegmentOutcome.Success)
                }
            }

            override fun onError(error: String) {
                if (!done.compareAndSet(false, true)) return
                deferred.complete(
                    SegmentOutcome.Failure(FailureKind.PROVIDER_ERROR, error)
                )
            }
        }

        try {
            config.provider.synthesize(text, segmentParams, config.providerConfig, listener)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SegmentOutcome.Failure(
                FailureKind.PROVIDER_ERROR,
                e.message ?: TOKEN_SYNTHESIS_FAILED,
            )
        }

        return try {
            withTimeoutOrNull(remainingMs.milliseconds) { deferred.await() }
                ?: SegmentOutcome.Failure(FailureKind.TIMEOUT, TOKEN_TIMEOUT)
        } catch (_: TimeoutCancellationException) {
            SegmentOutcome.Failure(FailureKind.TIMEOUT, TOKEN_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SegmentOutcome.Failure(
                FailureKind.PROVIDER_ERROR,
                e.message ?: TOKEN_SYNTHESIS_FAILED,
            )
        }
    }

    private sealed interface SegmentOutcome {
        data object Success : SegmentOutcome
        data class Failure(val kind: FailureKind, val message: String?) : SegmentOutcome
    }

    fun tokenFor(kind: FailureKind): String = when (kind) {
        FailureKind.FORMAT_MISMATCH -> TOKEN_FORMAT_MISMATCH
        FailureKind.ZERO_AUDIO -> TOKEN_ZERO_AUDIO
        FailureKind.PROVIDER_ERROR -> TOKEN_SYNTHESIS_FAILED
        FailureKind.TIMEOUT -> TOKEN_TIMEOUT
    }

    companion object {
        const val TOKEN_FORMAT_MISMATCH = "multirole_format_mismatch"
        const val TOKEN_ZERO_AUDIO = "multirole_zero_audio"
        const val TOKEN_TIMEOUT = "multirole_timeout"
        const val TOKEN_SYNTHESIS_FAILED = "multirole_synthesis_failed"
    }
}
