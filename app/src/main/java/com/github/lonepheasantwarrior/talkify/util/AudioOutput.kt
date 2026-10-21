package com.github.lonepheasantwarrior.talkify.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

/**
 * 音频输出抽象
 *
 * 只暴露 [TalkifyAudioPlayer] 实际使用的能力，使播放器的起播阈值、停止打断、
 * 写入失败与排空逻辑可在 JVM 用替身验证，不必依赖真机 AudioTrack。
 */
internal interface AudioOutput {

    /**
     * 底层是否支持显式管理起播阈值（API 31+）。
     * 不支持时起播阈值等于缓冲容量，短音频必须经 stop() 冲刷才能被 mixer 渲染。
     */
    val managesStartThreshold: Boolean

    /** 原生缓冲容量（帧） */
    val bufferCapacityFrames: Int

    /** 当前播放头（帧） */
    val playbackHeadFrames: Int

    /** AudioTrack.PLAYSTATE_* */
    val playState: Int

    fun play()

    fun pause()

    /** 写入音频字节，返回实际写入字节数；错误返回负数 */
    fun write(data: ByteArray, offset: Int, size: Int): Int

    fun stop()

    fun release()
}

/** 音频输出工厂：播放器只依赖该接口，测试可注入替身 */
internal fun interface AudioOutputFactory {
    fun create(
        sampleRate: Int,
        channelCount: Int,
        audioFormat: Int,
        desiredBufferSizeBytes: Int,
        bytesPerFrame: Int
    ): AudioOutput?
}

/** 生产实现：AudioTrack（MODE_STREAM） */
internal object AndroidAudioOutputFactory : AudioOutputFactory {

    private const val MIN_BUFFER_MULTIPLIER = 2

    override fun create(
        sampleRate: Int,
        channelCount: Int,
        audioFormat: Int,
        desiredBufferSizeBytes: Int,
        bytesPerFrame: Int
    ): AudioOutput? {
        return try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                audioFormat
            )
            if (minBufferSize <= 0) {
                TtsLogger.e(
                    "Invalid AudioTrack buffer size: $minBufferSize " +
                        "(sampleRate=$sampleRate, channelCount=$channelCount, audioFormat=$audioFormat)",
                    null,
                    "TalkifyAudioPlayer"
                )
                return null
            }

            // 取"设备最小值倍数"与"目标时长"中的较大者，并按帧对齐
            val desired = maxOf(minBufferSize * MIN_BUFFER_MULTIPLIER, desiredBufferSizeBytes)
            val bufferSizeBytes =
                (desired + bytesPerFrame - 1) / bytesPerFrame * bytesPerFrame

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(
                    if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                )
                .setEncoding(audioFormat)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                TtsLogger.e("AudioTrack is not initialized", null, "TalkifyAudioPlayer")
                track.release()
                return null
            }

            // API 31+ 起播阈值默认等于缓冲容量：短于容量的音频永远等不到起播。
            // 显式降到 1 帧，写入首帧即开始播放；缓冲容量仍保持较大以吸收抖动。
            // API 30 无此 API，由播放器在合成结束时按 AOSP 方案 stop() 冲刷短音频。
            var managesStartThreshold = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                managesStartThreshold = try {
                    track.setStartThresholdInFrames(1) == 1
                } catch (e: Exception) {
                    TtsLogger.w(
                        "setStartThresholdInFrames failed: ${e.message}",
                        tag = "TalkifyAudioPlayer"
                    )
                    false
                }
                if (!managesStartThreshold) {
                    TtsLogger.w(
                        "Start threshold is not 1 frame; short utterances will use stop() fallback",
                        tag = "TalkifyAudioPlayer"
                    )
                }
            }
            AndroidAudioOutput(track, managesStartThreshold)
        } catch (e: Exception) {
            TtsLogger.e("Failed to create AudioTrack: ${e.message}", e, "TalkifyAudioPlayer")
            null
        }
    }
}

private class AndroidAudioOutput(
    private val track: AudioTrack,
    override val managesStartThreshold: Boolean
) : AudioOutput {

    override val bufferCapacityFrames: Int
        get() = track.bufferCapacityInFrames

    override val playbackHeadFrames: Int
        get() = track.playbackHeadPosition

    override val playState: Int
        get() = track.playState

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun write(data: ByteArray, offset: Int, size: Int): Int =
        track.write(data, offset, size)

    override fun stop() = track.stop()

    override fun release() = track.release()
}
