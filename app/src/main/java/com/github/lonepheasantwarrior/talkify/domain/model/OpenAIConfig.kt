package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * OpenAI 语音合成供应商配置
 *
 * 继承 [BaseProviderConfig]，封装 OpenAI 供应商所需的配置信息。
 * 请求规范兼容 OpenAI `POST /v1/audio/speech` 接口，
 * 指向该规范的第三方转接平台仅需修改 [apiUrl]
 *
 * @property voiceId 声音 ID，如 "alloy"、"coral" 等
 *                    （完整列表见 res/xml/openai_tts_voices.xml）
 * @property apiUrl 自定义 API 地址（完整端点，含 /audio/speech 路径），为空时使用默认地址
 *                   （默认：https://api.openai.com/v1/audio/speech）。
 *                   第三方转接平台或反向代理可直接指向其完整端点
 * @property modelId 自定义模型 ID，为空时使用默认模型（默认：gpt-4o-mini-tts）
 * @property apiKey OpenAI 平台的 API Key，用于认证（Authorization: Bearer 请求头）
 * @property styleInstruction 可选的风格指令（自然语言描述朗读风格、语气等）。
 *                             对应 API 的 instructions 字段，例如 "用温柔的语气朗读"。
 *                             为空时不携带 instructions，由模型按默认风格朗读
 * @property customVoiceId 可选的自定义声音 ID（第三方转接平台的音色标识五花八门，
 *                             可能不在预置音色列表内）。非空时优先于 [voiceId] 生效，
 *                             配置界面中"声音选择"随之展示为"自定义"
 * @property proxyProtocol 代理协议总开关："none" 直连（默认）、"http"、"socks"。
 *                             仅在 "http"/"socks" 时主机与端口生效
 * @property proxyHost 代理主机地址，仅协议为 "http"/"socks" 时必填
 * @property proxyPort 代理端口，仅协议为 "http"/"socks" 时生效，必须为 1-65535 的数字
 */
data class OpenAIConfig(
    override val voiceId: String = "",
    override val apiUrl: String = "",
    override val modelId: String = "",
    val apiKey: String = "",
    val styleInstruction: String = "",
    val customVoiceId: String = "",
    val proxyProtocol: String = PROTOCOL_NONE,
    val proxyHost: String = "",
    val proxyPort: String = ""
) : BaseProviderConfig(voiceId, apiUrl, modelId) {

    companion object {
        const val PROTOCOL_NONE = "none"
        const val PROTOCOL_HTTP = "http"
        const val PROTOCOL_SOCKS = "socks"

        /** 该供应商归入"高级设置"折叠面板的配置项 key（低频项：API 地址、模型 ID、代理设置） */
        val ADVANCED_ITEM_KEYS: Set<String> = setOf(
            "api_url",
            "model_id",
            "proxy_protocol",
            "proxy_host",
            "proxy_port"
        )
    }
}
