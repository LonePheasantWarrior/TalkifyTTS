package com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.EngineIds
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository

/**
 * 豆包语音合成 2.0 引擎 - 声音仓储实现
 *
 * 负责从应用资源中加载豆包语音合成引擎对应的声音列表
 * 遵循 [VoiceRepository] 接口，便于后续扩展其他引擎服务
 */
class SeedTts2VoiceRepository(
    private val context: Context
) : VoiceRepository {

    private val engineVoiceMap = mapOf(
        EngineIds.SeedTts2.value to VoiceConfig(
            voiceIdsResId = R.array.volcengine_seed_TTS_2_voices,
            displayNamesResId = R.array.volcengine_seed_TTS_2_voices_display_names
        )
    )

    override suspend fun getVoicesForEngine(engine: TtsEngine): List<VoiceInfo> {
        val config = engineVoiceMap[engine.id] ?: return emptyList()

        val voiceIds = context.resources.getStringArray(config.voiceIdsResId)
        val displayNames = context.resources.getStringArray(config.displayNamesResId)

        if (voiceIds.size != displayNames.size) {
            return emptyList()
        }

        return voiceIds.mapIndexed { index, voiceId ->
            VoiceInfo(
                voiceId = voiceId,
                displayName = displayNames[index]
            )
        }
    }

    /**
     * 获取声音详细信息（包含描述）
     *
     * @param engine 引擎信息
     * @return 声音详细信息列表
     */
    fun getVoicesWithDescription(engine: TtsEngine): List<VoiceInfoWithDescription> {
        val config = engineVoiceMap[engine.id] ?: return emptyList()

        val voiceIds = context.resources.getStringArray(config.voiceIdsResId)
        val displayNames = context.resources.getStringArray(config.displayNamesResId)
        val descriptions = try {
            context.resources.getStringArray(R.array.volcengine_seed_TTS_2_voices_descriptions)
        } catch (e: Exception) {
            emptyArray<String>()
        }

        if (voiceIds.size != displayNames.size) {
            return emptyList()
        }

        return voiceIds.mapIndexed { index, voiceId ->
            VoiceInfoWithDescription(
                voiceId = voiceId,
                displayName = displayNames[index],
                description = descriptions.getOrNull(index) ?: ""
            )
        }
    }

    private data class VoiceConfig(
        val voiceIdsResId: Int,
        val displayNamesResId: Int
    )

    /**
     * 声音详细信息（含描述）
     */
    data class VoiceInfoWithDescription(
        val voiceId: String,
        val displayName: String,
        val description: String
    )
}
