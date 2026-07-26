package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProvider
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository

/**
 * 本地模型供应商 - 音色仓储实现
 *
 * 音色信息从 [LocalModelRegistry] 动态获取（而非 XML 资源文件）。
 * 根据当前用户选中的 modelId 返回对应模型的音色列表。
 *
 * 与传统供应商不同，本地模型的音色因模型而异：
 * - VITS 模型通常只有 1 个默认音色
 * - Kokoro 模型支持多个可选音色
 */
class LocalModelVoiceRepository(
    private val context: Context
) : VoiceRepository {

    private val configRepository by lazy {
        LocalModelConfigRepository(context)
    }

    override suspend fun getVoicesForProvider(provider: TtsProvider): List<VoiceInfo> {
        if (provider.id != ProviderIds.LocalModel.providerId) return emptyList()

        // 读取用户当前选择的模型 ID
        val config = configRepository.getConfig(ProviderIds.LocalModel.providerId)
        val modelId = config.modelId.ifBlank {
            ProviderIds.LocalModel.defaultModelId
        }

        // 从注册表获取该模型的音色列表
        val modelInfo = LocalModelRegistry.getModel(modelId)
            ?: return emptyList()

        return modelInfo.voiceList.map { voice ->
            VoiceInfo(
                voiceId = voice.voiceId,
                displayName = "${voice.displayName} (${voice.language.uppercase()})",
                sampleRate = modelInfo.sampleRate
            )
        }
    }
}
