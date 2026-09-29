package com.github.lonepheasantwarrior.talkify.service.provider

import kotlin.math.roundToInt

/**
 * Android TTS 合成参数到各供应商 API 参数的映射。
 *
 * Android 侧参数范围：
 * - pitch / speechRate: [0, 200]，100 为默认值
 * - volume: [0.0, 1.0]，1.0 为默认值
 *
 * 各映射器为纯函数，便于单元测试与跨供应商复用。
 */
internal object AzureParamMapper {

    /** Android 语速 [0,200] → Azure 相对百分比（如 "+50%" / "-20%"） */
    fun convertRate(speechRate: Float): String {
        val ratePercent = ((speechRate - 100) / 100 * 100).toInt()
        return if (ratePercent >= 0) "+${ratePercent}%" else "${ratePercent}%"
    }

    /** Android 音量 [0,1] → Azure 相对百分比（如 "+50%" / "-50%"） */
    fun convertVolume(volume: Float): String {
        val volumePercent = (volume * 100 - 100).toInt()
        return if (volumePercent >= 0) "+${volumePercent}%" else "${volumePercent}%"
    }

    /** Android 音调 [0,200] → Azure 相对Hz偏移（如 "+50Hz" / "-25Hz"） */
    fun convertPitch(pitch: Float): String {
        val pitchHz = ((pitch - 100) / 100 * 50).toInt()
        return if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
    }
}

internal object VolcengineParamMapper {

    /** Android 语速 [0,200] → 火山 [-50, 100]，0 为默认 */
    fun convertSpeechRate(androidRate: Float): Int {
        return when {
            androidRate <= 50f -> -50
            androidRate >= 200f -> 100
            else -> ((androidRate - 100f) / 100f * 100f).roundToInt()
        }
    }

    /** Android 音量 [0,1] → 火山响度 [-50, 100]，0 为默认 */
    fun convertLoudnessRate(androidVolume: Float): Int {
        return when {
            androidVolume <= 0.25f -> -50
            androidVolume >= 1.0f -> 100
            else -> ((androidVolume - 0.5f) / 0.5f * 100f).roundToInt()
        }
    }
}

internal object TencentParamMapper {

    /** Android 语速 [0,200] → 腾讯离散值 {-2,-1,0,1,2}（极端 6） */
    fun convertSpeechRate(androidRate: Float): Float {
        return when {
            androidRate <= 50f -> -2f
            androidRate <= 80f -> -1f
            androidRate <= 120f -> 0f
            androidRate <= 150f -> 1f
            androidRate <= 200f -> 2f
            else -> 6f
        }
    }

    /** Android 音量 [0,1] → 腾讯 [-10, 10] */
    fun convertVolume(androidVolume: Float): Float {
        return when {
            androidVolume <= 0f -> -10f
            androidVolume >= 1f -> 10f
            else -> (androidVolume - 0.5f) * 20f
        }
    }
}

internal object MiniMaxParamMapper {

    /** Android 语速 [0,200] → MiniMax [0.5, 2.0] */
    fun convertSpeechRate(androidRate: Float): Float {
        return when {
            androidRate <= 50f -> 0.5f
            androidRate >= 200f -> 2.0f
            else -> androidRate / 100f
        }
    }

    /**
     * Android 音量 [0,1] → MiniMax vol (0, 10]，默认 1.0。
     * 采用保守映射，避免极端增益导致输出削波失真。
     */
    fun convertVolume(volume: Float): Float {
        return (1.0f + volume * 1.0f).coerceIn(0.1f, 10f)
    }
}

internal object GoogleParamMapper {

    /**
     * Android 语速 [0,200] → Gemini speech_metadata.style 语气提示。
     *
     * Gemini TTS 没有数值化的语速参数，仅支持通过 style 自然语言控制语速
     * （如 "speaking slowly" / "speaking rapidly"）。为避免轻微偏离默认语速
     * 时产生不必要的风格干预，仅在明显偏离（<0.75x 或 >1.5x）时给出提示。
     *
     * @return 风格提示短语，默认语速区间返回 null（不干预）
     */
    fun speechRateStyleHint(androidRate: Float): String? {
        return when {
            androidRate < 75f -> "speaking slowly"
            androidRate > 150f -> "speaking rapidly"
            else -> null
        }
    }
}

internal object OpenAIParamMapper {

    /**
     * Android 语速 [0,200] → OpenAI instructions 语气提示。
     *
     * gpt-4o-mini-tts 不支持数值化的 speed 参数，语速经 instructions
     * 自然语言控制（如 "speaking slowly" / "speaking rapidly"）。
     * 与 [GoogleParamMapper] 同一策略：仅在明显偏离（<0.75x 或 >1.5x）时提示。
     *
     * @return 风格提示短语，默认语速区间返回 null（不干预）
     */
    fun speechRateStyleHint(androidRate: Float): String? {
        return when {
            androidRate < 75f -> "speaking slowly"
            androidRate > 150f -> "speaking rapidly"
            else -> null
        }
    }
}
