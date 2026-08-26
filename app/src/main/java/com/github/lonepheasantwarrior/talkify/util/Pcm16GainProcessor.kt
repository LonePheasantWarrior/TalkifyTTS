package com.github.lonepheasantwarrior.talkify.util

import android.media.AudioFormat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * PCM 16-bit 小端音频增幅器。
 *
 * 样本低于 [SOFT_LIMIT_THRESHOLD] 时保持线性增益，超过阈值后逐渐压缩，
 * 避免直接截断造成明显的削波失真。
 */
object Pcm16GainProcessor {
    private const val SOFT_LIMIT_THRESHOLD = 0.9f
    private const val PCM16_SCALE = 32768f
    private const val MAX_GAIN_DB = 12f

    fun process(
        audioData: ByteArray,
        audioFormat: Int,
        enabled: Boolean,
        gainDb: Float
    ): ByteArray {
        if (!enabled || gainDb <= 0f || audioFormat != AudioFormat.ENCODING_PCM_16BIT) {
            return audioData
        }

        val gain = 10.0.pow(gainDb.coerceIn(0f, MAX_GAIN_DB).toDouble() / 20.0).toFloat()
        val output = audioData.copyOf()
        var index = 0

        while (index + 1 < output.size) {
            val low = output[index].toInt() and 0xFF
            val high = output[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val amplified = sample / PCM16_SCALE * gain
            val limited = softLimit(amplified).coerceIn(-1f, 32767f / PCM16_SCALE)
            val pcm = (limited * PCM16_SCALE).toInt().coerceIn(-32768, 32767)

            output[index] = (pcm and 0xFF).toByte()
            output[index + 1] = ((pcm shr 8) and 0xFF).toByte()
            index += 2
        }

        return output
    }

    private fun softLimit(sample: Float): Float {
        val magnitude = abs(sample)
        if (magnitude <= SOFT_LIMIT_THRESHOLD) return sample

        val remaining = 1f - SOFT_LIMIT_THRESHOLD
        val curve = (1.0 - exp(
            -((magnitude - SOFT_LIMIT_THRESHOLD) / remaining).toDouble()
        )).toFloat()
        val compressed = SOFT_LIMIT_THRESHOLD + remaining * curve
        return if (sample < 0f) -compressed else compressed
    }
}
