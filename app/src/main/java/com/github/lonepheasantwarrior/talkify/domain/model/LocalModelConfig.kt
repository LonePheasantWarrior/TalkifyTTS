package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 本地模型供应商配置
 *
 * 继承 [BaseProviderConfig]，本地模型不使用 API Key，
 * [modelId] 用于存储用户选中的本地模型 ID，
 * [apiUrl] 保留字段但 UI 不展示。
 *
 * @param voiceId 当前选中的音色 ID
 * @param apiUrl 保留字段（本地模型不使用）
 * @param modelId 用户选中的本地模型 ID
 */
data class LocalModelConfig(
    override val voiceId: String = "",
    override val apiUrl: String = "",
    override val modelId: String = ""
) : BaseProviderConfig(voiceId, apiUrl, modelId)
