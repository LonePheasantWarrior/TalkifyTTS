package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * Google Gemini TTS 语音合成供应商配置
 *
 * 继承 [BaseProviderConfig]，封装 Google 供应商所需的配置信息
 * 使用 Gemini API Key 进行认证
 *
 * @property voiceId 声音 ID，如 "Kore"、"Puck" 等（完整列表见 res/xml/google_gemini_tts_voices.xml），
 *                   generateContent 规范下亦接受 Voice design / replication 自定义 ID（voice_...、voicekey_...）
 * @property apiUrl 自定义 API 地址，为空时按 [apiSpec] 回退对应默认值。
 *                   地址语义随接口规范变化：interactions 为完整端点；
 *                   generateContent 为 "/models" 基础地址（模型与动作名自动拼接）。
 *                   中国大陆无法直连 Google 服务，可指向反向代理或兼容服务
 * @property modelId 自定义模型 ID，为空时使用默认模型（默认：gemini-3.8-flash-lite-tts）
 * @property apiKey Google 平台的 API Key，用于认证（x-goog-api-key 请求头），从 Google AI Studio 获取
 * @property styleInstruction 可选的风格指令（自然语言描述朗读风格、语气等）。
 *                             对应 API 的 speech_metadata.style 字段，例如 "用温柔的语气朗读"。
 *                             为空时不携带 style 注解，由模型按默认风格朗读
 * @property apiSpec 接口规范：[SPEC_INTERACTIONS]（默认）或 [SPEC_GENERATE_CONTENT]，
 *                   决定合成请求的端点与报文结构（见 GoogleApiSpec 两套实现）
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
    val apiSpec: String = SPEC_INTERACTIONS,
    val proxyProtocol: String = PROTOCOL_NONE,
    val proxyHost: String = "",
    val proxyPort: String = ""
) : BaseProviderConfig(voiceId, apiUrl, modelId) {

    companion object {
        const val PROTOCOL_NONE = "none"
        const val PROTOCOL_HTTP = "http"
        const val PROTOCOL_SOCKS = "socks"

        /** 接口规范：interactions（默认规范，POST /v1beta/interactions，stream: true） */
        const val SPEC_INTERACTIONS = "interactions"

        /** 接口规范：generateContent（POST /v1beta/models/{model}:streamGenerateContent?alt=sse） */
        const val SPEC_GENERATE_CONTENT = "generateContent"

        /** interactions 规范默认端点（完整端点） */
        const val DEFAULT_INTERACTIONS_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/interactions"

        /** generateContent 规范默认基础地址（"/models" 前缀，模型与动作名自动拼接在其后） */
        const val DEFAULT_GENERATE_CONTENT_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"

        /** 该供应商归入"高级设置"折叠面板的配置项 key（低频项：接口规范、API 地址、模型 ID、代理设置） */
        val ADVANCED_ITEM_KEYS: Set<String> = setOf(
            "api_spec",
            "api_url",
            "model_id",
            "proxy_protocol",
            "proxy_host",
            "proxy_port"
        )
    }
}
