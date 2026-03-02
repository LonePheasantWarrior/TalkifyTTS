package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 微软语音合成引擎配置
 *
 * 继承 [BaseEngineConfig]，封装微软语音合成引擎所需的配置信息
 * 注意：微软语音合成无需 API Key，仅需配置音色即可
 *
 * 配置项说明：
 * - voiceId：声音 ID，格式为微软标准格式，如 "zh-CN-XiaoxiaoNeural"
 *
 * 使用示例：
 * ```
 * val config = MicrosoftTtsConfig(
 *     voiceId = "zh-CN-XiaoxiaoNeural"
 * )
 * ```
 *
 * @property voiceId 声音 ID，微软标准格式
 */
data class MicrosoftTtsConfig(
    override val voiceId: String = ""
) : BaseEngineConfig(voiceId)
