package com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.EngineIds
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository

/**
 * 微软语音合成引擎 - 声音仓储实现
 *
 * 负责从应用资源中加载微软语音合成引擎对应的声音列表
 * 遵循 [VoiceRepository] 接口，便于后续扩展
 */
class MicrosoftTtsVoiceRepository(
    private val context: Context
) : VoiceRepository {

    private val engineVoiceMap = mapOf(
        EngineIds.MicrosoftTts.value to VoiceConfig(
            voiceIdsResId = R.array.microsoft_tts_voices,
            displayNamesResId = R.array.microsoft_tts_voice_display_names
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

    private data class VoiceConfig(
        val voiceIdsResId: Int,
        val displayNamesResId: Int
    )
}
