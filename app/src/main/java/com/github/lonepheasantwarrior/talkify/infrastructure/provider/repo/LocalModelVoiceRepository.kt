package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProvider
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalVoiceCatalog

/**
 * 本地模型供应商 - 音色仓储实现
 *
 * 音色以内置音色目录（res/xml/local_model_voices.xml，参考音频随 APK 分发）为准；
 * 目录异常为空时回退 [LocalModelRegistry] 注册表音色。
 */
class LocalModelVoiceRepository(
    private val configRepository: LocalModelConfigRepository
) : VoiceRepository {

    override suspend fun getVoicesForProvider(provider: TtsProvider): List<VoiceInfo> {
        if (provider.id != ProviderIds.LocalModel.providerId) return emptyList()

        // 读取用户当前选择的模型 ID
        val config = configRepository.getConfig(ProviderIds.LocalModel.providerId)
        val modelId = config.modelId.ifBlank {
            ProviderIds.LocalModel.defaultModelId
        }

        // 从注册表获取该模型的元信息（采样率等）
        val modelInfo = LocalModelRegistry.getModel(modelId)
            ?: return emptyList()

        val catalogVoices = LocalVoiceCatalog.getVoices()
        val voices = if (catalogVoices.isNotEmpty()) catalogVoices else modelInfo.voiceList
        return voices.map { voice ->
            VoiceInfo(
                voiceId = voice.voiceId,
                displayName = "${voice.displayName} (${voice.language.uppercase()})",
                sampleRate = modelInfo.sampleRate
            )
        }
    }
}
