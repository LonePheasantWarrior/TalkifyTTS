package com.github.lonepheasantwarrior.talkify.service.delivery

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import com.github.lonepheasantwarrior.talkify.service.delivery.SynthesisDeliveryBridge.Event
import java.util.concurrent.TimeUnit

/**
 * 在 `onSynthesizeText` 所在线程串行消费交付事件并调用框架回调。
 *
 * - `start()` 最多一次；流格式由首个实际音频确立；
 * - 每个 `audioAvailable` 写入长度 <= `getMaxBufferSize()` 且按帧边界切分；
 * - 回调顺序由本类唯一决定：消费结束前不会调用 `done()`；
 *   终态调用由服务层根据 [DeliveryResult] 在本线程完成。
 */
class SynthesisCallbackConsumer(
    private val callback: SynthesisCallback,
) {

    enum class Status {
        SUCCESS,
        ERROR,
        TIMEOUT,
        CANCELLED,
        FRAMEWORK_REJECTED,
        NO_AUDIO,
        FORMAT_MISMATCH,
    }

    data class DeliveryResult(
        val status: Status,
        val providerCompleted: Boolean,
        val audioBytes: Long,
        val firstSampleRate: Int?,
        val errorMessage: String? = null,
        val frameworkDetail: String? = null,
    )

    fun deliver(
        bridge: SynthesisDeliveryBridge,
        timeoutMs: Long,
        isCancelled: () -> Boolean,
    ): DeliveryResult {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        var started = false
        var sampleRate = 0
        var audioFormat = 0
        var channelCount = 0
        var firstSampleRate: Int? = null
        var audioBytes = 0L
        var providerCompleted = false
        var pendingFrameTail = ByteArray(0)
        var maxBufferSize = runCatching { callback.maxBufferSize }.getOrDefault(4096)
        if (maxBufferSize <= 0) maxBufferSize = 4096

        while (true) {
            if (isCancelled() || bridge.isCancelled) {
                return DeliveryResult(
                    Status.CANCELLED, providerCompleted, audioBytes, firstSampleRate
                )
            }
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) {
                return DeliveryResult(
                    Status.TIMEOUT, providerCompleted, audioBytes, firstSampleRate
                )
            }
            val event = bridge.take(remainingMs) ?: return DeliveryResult(
                Status.TIMEOUT, providerCompleted, audioBytes, firstSampleRate
            )
            when (event) {
                is Event.Cancelled -> return DeliveryResult(
                    Status.CANCELLED, providerCompleted, audioBytes, firstSampleRate
                )

                is Event.Audio -> {
                    if (!started) {
                        val startResult = callback.start(
                            event.sampleRate, event.audioFormat, event.channelCount
                        )
                        if (startResult != TextToSpeech.SUCCESS) {
                            return DeliveryResult(
                                Status.FRAMEWORK_REJECTED,
                                providerCompleted,
                                audioBytes,
                                firstSampleRate,
                                frameworkDetail = "start_$startResult",
                            )
                        }
                        started = true
                        sampleRate = event.sampleRate
                        audioFormat = event.audioFormat
                        channelCount = event.channelCount
                        firstSampleRate = event.sampleRate
                    } else if (sampleRate != event.sampleRate ||
                        audioFormat != event.audioFormat ||
                        channelCount != event.channelCount
                    ) {
                        return DeliveryResult(
                            Status.FORMAT_MISMATCH,
                            providerCompleted,
                            audioBytes,
                            firstSampleRate,
                            errorMessage = "audio_format_changed_mid_stream",
                            frameworkDetail = "${event.sampleRate}/${event.audioFormat}/${event.channelCount}",
                        )
                    }
                    val frameSize = channelCount * bytesPerSample(audioFormat)
                    if (frameSize <= 0) {
                        return DeliveryResult(
                            Status.FORMAT_MISMATCH,
                            providerCompleted,
                            audioBytes,
                            firstSampleRate,
                            errorMessage = "invalid_audio_format",
                        )
                    }
                    // 供应商分块/交付桥切片可能落在帧中间：跨事件缓存不足一帧的尾字节，
                    // 只把完整帧写入框架；请求结束时仍有残留则按格式异常失败，避免错位
                    val data = if (pendingFrameTail.isEmpty()) {
                        event.data
                    } else {
                        pendingFrameTail + event.data
                    }
                    val usableBytes = data.size - (data.size % frameSize)
                    pendingFrameTail = if (usableBytes < data.size) {
                        data.copyOfRange(usableBytes, data.size)
                    } else {
                        ByteArray(0)
                    }
                    if (usableBytes > 0) {
                        // 每次写入不超过 maxBufferSize，且切分点仍在帧边界上
                        val writeLimit = (maxBufferSize / frameSize) * frameSize
                        val chunkSize = if (writeLimit > 0) writeLimit else frameSize
                        var offset = 0
                        while (offset < usableBytes) {
                            val size = minOf(chunkSize, usableBytes - offset)
                            val result = callback.audioAvailable(data, offset, size)
                            if (result != TextToSpeech.SUCCESS) {
                                return DeliveryResult(
                                    Status.FRAMEWORK_REJECTED,
                                    providerCompleted,
                                    audioBytes,
                                    firstSampleRate,
                                    frameworkDetail = "audio_$result",
                                )
                            }
                            audioBytes += size
                            offset += size
                        }
                    }
                }

                Event.Completed -> {
                    providerCompleted = true
                    if (pendingFrameTail.isNotEmpty() && started) {
                        return DeliveryResult(
                            Status.FORMAT_MISMATCH,
                            providerCompleted = true,
                            audioBytes = audioBytes,
                            firstSampleRate = firstSampleRate,
                            errorMessage = "unaligned_audio_frame",
                        )
                    }
                    return if (!started || audioBytes == 0L) {
                        DeliveryResult(
                            Status.NO_AUDIO,
                            providerCompleted = true,
                            audioBytes = audioBytes,
                            firstSampleRate = firstSampleRate,
                        )
                    } else {
                        DeliveryResult(
                            Status.SUCCESS,
                            providerCompleted = true,
                            audioBytes = audioBytes,
                            firstSampleRate = firstSampleRate,
                        )
                    }
                }

                is Event.Failed -> return DeliveryResult(
                    Status.ERROR,
                    providerCompleted,
                    audioBytes,
                    firstSampleRate,
                    errorMessage = event.message,
                )
            }
        }
    }

    private fun bytesPerSample(format: Int): Int = when (format) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }
}
