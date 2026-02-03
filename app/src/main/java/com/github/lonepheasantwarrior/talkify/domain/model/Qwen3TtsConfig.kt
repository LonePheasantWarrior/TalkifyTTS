package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 通义千问3语音合成引擎配置
 *
 * 继承 [BaseEngineConfig]，封装通义千问3引擎所需的配置信息
 * 包含阿里云百炼服务的 API Key 和语音模型配置
 *
 * 配置项说明：
 * - apiKey：阿里云百炼平台的 API Key，用于身份认证
 * - voiceId：声音 ID，格式为"声音名称::语言代码"
 *
 * 使用示例：
 * ```
 * val config = Qwen3TtsConfig(
 *     apiKey = "sk-xxx",
 *     voiceId = "CHERRY::zh-CN"
 * )
 * ```
 *
 * @property voiceId 声音 ID，格式为 "声音名称::语言代码"
 *                   如 "CHERRY::zh-CN"、"EMMA::en-US" 等
 *                   可用声音列表参考 [AudioParameters.Voice]
 * @property apiKey 阿里云百炼平台的 API Key
 *                  从阿里云控制台获取，需具有百炼服务访问权限
 */
data class Qwen3TtsConfig(
    override val voiceId: String = "",
    val apiKey: String = ""
) : BaseEngineConfig(voiceId)
