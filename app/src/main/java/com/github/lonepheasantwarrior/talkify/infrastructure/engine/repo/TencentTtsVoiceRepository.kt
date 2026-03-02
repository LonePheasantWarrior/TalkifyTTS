package com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.EngineIds
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository

/**
 * 腾讯云语音合成引擎 - 声音仓储实现
 *
 * 负责从应用资源中加载腾讯云引擎对应的声音列表
 * 支持三种音色类型：精品音色、大模型音色、超自然大模型音色
 * 遵循 [VoiceRepository] 接口，便于后续扩展其他引擎服务
 */
class TencentTtsVoiceRepository(
    private val context: Context
) : VoiceRepository {

    companion object {
        const val GROUP_PREMIUM = "精品音色"
        const val GROUP_LLM = "大模型音色"
        const val GROUP_NATURAL = "超自然大模型音色"
    }

    override suspend fun getVoicesForEngine(engine: TtsEngine): List<VoiceInfo> {
        if (engine.id != EngineIds.TencentTts.value) {
            return emptyList()
        }

        val voices = mutableListOf<VoiceInfo>()

        voices.addAll(loadVoicesFromResource(
            R.array.tencent_premium_tts_voices,
            R.array.tencent_premium_tts_voice_display_names,
            R.array.tencent_premium_tts_voice_sample_rates,
            GROUP_PREMIUM
        ))

        voices.addAll(loadVoicesFromResource(
            R.array.tencent_llm_tts_voices,
            R.array.tencent_llm_tts_voice_display_names,
            R.array.tencent_llm_tts_voice_sample_rates,
            GROUP_LLM
        ))

        voices.addAll(loadVoicesFromResource(
            R.array.tencent_natural_tts_voices,
            R.array.tencent_natural_tts_voice_display_names,
            R.array.tencent_natural_tts_voice_sample_rates,
            GROUP_NATURAL
        ))

        return voices
    }

    private fun loadVoicesFromResource(
        voiceIdsRes: Int,
        displayNamesRes: Int,
        sampleRatesRes: Int,
        group: String
    ): List<VoiceInfo> {
        return try {
            val voiceIds = context.resources.getStringArray(voiceIdsRes)
            val displayNames = context.resources.getStringArray(displayNamesRes)
            val sampleRates = context.resources.getStringArray(sampleRatesRes)
            
            voiceIds.zip(displayNames).zip(sampleRates).map { (voiceAndName, sampleRateStr) ->
                val (voiceId, displayName) = voiceAndName
                VoiceInfo(
                    voiceId = voiceId,
                    displayName = displayName,
                    group = group,
                    sampleRate = parseSampleRate(sampleRateStr)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析采样率字符串，返回最高支持的采样率
     * 例如："8k/16k" -> 16000，"8k/16k/24k" -> 24000
     */
    private fun parseSampleRate(sampleRateStr: String): Int? {
        return try {
            val rates = sampleRateStr.split("/")
                .map { it.trim().lowercase() }
                .mapNotNull { rateStr ->
                    when {
                        rateStr.contains("8k") -> 8000
                        rateStr.contains("16k") -> 16000
                        rateStr.contains("24k") -> 24000
                        else -> null
                    }
                }
            rates.maxOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
