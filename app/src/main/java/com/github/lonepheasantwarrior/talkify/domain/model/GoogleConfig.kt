package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * Google Gemini TTS 语音合成供应商配置
 *
 * 继承 [BaseProviderConfig]，封装 Google 供应商所需的配置信息
 * 使用 Gemini API Key 进行认证
 *
 * @property voiceId 声音 ID，如 "Kore"、"Puck" 等（完整列表见 res/xml/google_gemini_tts_voices.xml）
 * @property apiUrl 自定义 API 地址，为空时使用默认地址
 *                   （默认：https://generativelanguage.googleapis.com/v1beta/interactions）。
 *                   中国大陆无法直连 Google 服务，可指向反向代理或兼容服务
 * @property modelId 自定义模型 ID，为空时使用默认模型（默认：gemini-3.8-flash-lite-tts）
 * @property apiKey Google 平台的 API Key，用于认证（x-goog-api-key 请求头），从 Google AI Studio 获取
 * @property styleInstruction 可选的风格指令（自然语言描述朗读风格、语气等）。
 *                             对应 API 的 speech_metadata.style 字段，例如 "用温柔的语气朗读"。
 *                             为空时不携带 style 注解，由模型按默认风格朗读
 * @property proxyProtocol 代理协议总开关："none" 直连（默认）、"http"、"socks"。
 *                             仅在 "http"/"socks" 时主机与端口生效
 * @property proxyHost 代理主机地址，仅协议为 "http"/"socks" 时必填
 * @property proxyPort 代理端口，仅协议为 "http"/"socks" 时生效，必须为 1-65535 的数字
 */
data class GoogleConfig(
    override val voiceId: String = "",
    override val apiUrl: String = "",
    override val modelId: String = "",
    val apiKey: String = "",
    val styleInstruction: String = "",
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
