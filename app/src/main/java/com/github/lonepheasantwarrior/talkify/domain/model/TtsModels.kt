package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * TTS 供应商数据类
 *
 * @property id 供应商唯一标识符（ProviderIds.providerId），用于内部路由与持久化
 * @property name 供应商展示名称（ProviderIds.provider），UI 主展示项
 * @property defaultModelId 该供应商的默认模型标识符（ProviderIds.defaultModelId），UI 次展示项
 */
data class TtsProvider(
    val id: String,
    val name: String,
    val defaultModelId: String
)
