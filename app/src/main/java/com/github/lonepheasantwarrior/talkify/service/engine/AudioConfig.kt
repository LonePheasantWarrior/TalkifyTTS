package com.github.lonepheasantwarrior.talkify.service.engine

import android.media.AudioFormat

/**
 * TTS 引擎音频配置
 *
 * 封装引擎特定的音频参数，支持不同引擎使用不同的音频格式
 * 便于多引擎适配和配置管理
 *
 * @param sampleRate 采样率（Hz），默认 24000
 * @param audioFormat 音频格式（AudioFormat.ENCODING_PCM_16BIT 等）
 * @param channelCount 声道数，默认 1（单声道）
 */
data class AudioConfig(
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val audioFormat: Int = DEFAULT_AUDIO_FORMAT,
    val channelCount: Int = DEFAULT_CHANNEL_COUNT
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 24000
        const val DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val DEFAULT_CHANNEL_COUNT = 1

        /**
         * 通义千问3 TTS 默认配置
         * 参考阿里云文档，音频采样率为 24000Hz
         */
        val QWEN3_TTS = AudioConfig(
            sampleRate = 24000,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            channelCount = 1
        )

        /**
         * 豆包语音合成 2.0 默认配置
         * 参考火山引擎文档，音频采样率为 24000Hz
         */
        val SEED_TTS2 = AudioConfig(
            sampleRate = 24000,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            channelCount = 1
        )

        /**
         * 创建标准配置
         *
         * @param sampleRate 采样率
         * @param channelCount 声道数
         * @return 标准音频配置
         */
        fun createStandard(sampleRate: Int = DEFAULT_SAMPLE_RATE, channelCount: Int = 1): AudioConfig {
            return AudioConfig(
                sampleRate = sampleRate,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
                channelCount = channelCount
            )
        }
    }

    /**
     * 获取音频格式描述
     *
     * @return 格式描述字符串
     */
    fun getFormatDescription(): String {
        val formatName = when (audioFormat) {
            AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
            AudioFormat.ENCODING_PCM_8BIT -> "PCM_8BIT"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            AudioFormat.ENCODING_INVALID -> "INVALID"
            else -> "UNKNOWN($audioFormat)"
        }
        return "${sampleRate}Hz, $formatName, ${if (channelCount == 1) "mono" else "stereo"}"
    }
}
