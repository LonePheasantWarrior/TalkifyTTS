package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 腾讯语音合成引擎配置
 *
 * 继承 [BaseEngineConfig]，封装腾讯云语音合成引擎所需的配置信息
 * 包含腾讯云服务的 AppID、SecretID、SecretKey 和语音模型配置
 *
 * 配置项说明：
 * - appId：腾讯云账户的 AppID
 * - secretId：腾讯云 API 的 SecretID
 * - secretKey：腾讯云 API 的 SecretKey
 * - voiceId：声音 ID，格式为"音色ID::语言代码"
 *
 * 使用示例：
 * ```
 * val config = TencentTtsConfig(
 *     appId = "12345678",
 *     secretId = "AKIDxxx",
 *     secretKey = "xxx",
 *     voiceId = "502007::zh-CN"
 * )
 * ```
 *
 * @property voiceId 声音 ID，格式为 "音色ID::语言代码"
 *                   如 "502007::zh-CN"、"502006::en-US" 等
 *                   可用声音列表参考腾讯云语音合成官网文档
 * @property appId 腾讯云账户的 AppID
 *                  从腾讯云控制台获取
 * @property secretId 腾讯云 API 的 SecretID
 *                    从腾讯云 API 密钥管理控制台获取
 * @property secretKey 腾讯云 API 的 SecretKey
 *                     从腾讯云 API 密钥管理控制台获取
 */
data class TencentTtsConfig(
    override val voiceId: String = "",
    val appId: String = "",
    val secretId: String = "",
    val secretKey: String = ""
) : BaseEngineConfig(voiceId)
