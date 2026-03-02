package com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.service.engine.impl.TencentTtsEngine

/**
 * 腾讯云语音合成引擎 - 声音仓储实现
 *
 * 负责从应用资源中加载腾讯云引擎对应的声音列表
 * 遵循 [VoiceRepository] 接口，便于后续扩展其他引擎服务
 */
class TencentTtsVoiceRepository(
    private val context: Context
) : VoiceRepository {

    override suspend fun getVoicesForEngine(engine: TtsEngine): List<VoiceInfo> {
        if (engine.id != TencentTtsEngine.ENGINE_ID) {
            return emptyList()
        }

        val voiceIds = context.resources.getStringArray(R.array.tencent_tts_voices)
        val displayNames = context.resources.getStringArray(R.array.tencent_tts_voice_display_names)

        return voiceIds.zip(displayNames).map { (voiceId, displayName) ->
            VoiceInfo(
                voiceId = voiceId,
                displayName = displayName
            )
        }
    }
}
