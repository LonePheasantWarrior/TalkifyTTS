package com.github.lonepheasantwarrior.talkify.service.provider.impl

import com.github.lonepheasantwarrior.talkify.domain.model.GoogleConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini TTS - generateContent 接口规范实现
 *
 * 端点：`POST /v1beta/models/{model}:streamGenerateContent?alt=sse`，模型 ID 位于路径中，
 * 因此配置的 API 地址语义为 "/models" 基础地址（默认
 * `https://generativelanguage.googleapis.com/v1beta/models`，模型与动作名自动拼接），
 * 适配域名镜像类反向代理与遵循 Gemini 规范的第三方网关。
 *
 * 请求体：`contents[].parts[]` 承载逐字稿，风格经 `parts[].speech_metadata.style`；
 * `generationConfig.responseModalities=["AUDIO"]` 与 `generationConfig.responseFormat.audio`
 * 显式指定 AUDIO_L16（headerless raw PCM 16bit 小端）+ 采样率；
 * 音色经 `generationConfig.speechConfig.voiceConfig.voice`（预置音色名，或
 * Voice design / replication 自定义 ID `voice_...`、`voicekey_...`）。
 *
 * 流式响应（alt=sse）：每条 `data:` 为一个 GenerateContentResponse，
 * 音频位于 `candidates[].content.parts[].inlineData.data`（base64）。
 */
internal object GoogleGenerateContentApi : GoogleApiSpec {

    /** 默认基础地址（"/models" 前缀，模型与动作名自动拼接在其后） */
    const val DEFAULT_API_URL = GoogleConfig.DEFAULT_GENERATE_CONTENT_API_URL

    /** 流式合成动作与 SSE 响应格式参数 */
    private const val STREAM_ACTION = ":streamGenerateContent"
    private const val SSE_QUERY = "?alt=sse"

    /** 显式请求 headerless raw PCM 输出（流式默认值，显式指定以避免服务端默认漂移） */
    private const val MIME_TYPE_AUDIO_L16 = "AUDIO_L16"

    override fun buildEndpoint(customApiUrl: String, model: String): String {
        val base = customApiUrl.ifBlank { DEFAULT_API_URL }.trimEnd('/')
        return "$base/$model$STREAM_ACTION$SSE_QUERY"
    }

    override fun buildRequestBody(
        model: String,
        text: String,
        voiceId: String,
        style: String?,
        sampleRate: Int
    ): JSONObject {
        val part = JSONObject().apply {
            put("text", text)
            if (style != null) {
                put("speech_metadata", JSONObject().put("style", style))
            }
        }

        return JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(part))
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put(
                        "responseFormat",
                        JSONObject().apply {
                            put(
                                "audio",
                                JSONObject().apply {
                                    put("mimeType", MIME_TYPE_AUDIO_L16)
                                    put("sampleRate", sampleRate)
                                }
                            )
                        }
                    )
                    put(
                        "speechConfig",
                        JSONObject().apply {
                            put(
                                "voiceConfig",
                                JSONObject().put("voice", voiceId)
                            )
                        }
                    )
                }
            )
        }
    }

    override fun extractAudio(json: JSONObject): ByteArray? {
        val candidates = json.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: continue
            for (j in 0 until parts.length()) {
                // REST JSON 兼容 camelCase 与 snake_case 两种字段形态
                val inline = parts.optJSONObject(j)
                    ?.let { it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data") }
                    ?: continue
                val decoded = decodeBase64Audio(inline.optString("data")) ?: continue
                if (decoded.isNotEmpty()) return decoded
            }
        }
        return null
    }

    override fun extractError(json: JSONObject): String? {
        extractGoogleErrorObject(json)?.let { return it }

        // 安全策略拦截不产生音频、也无 error 对象，经 promptFeedback.blockReason 透出
        val blockReason = json.optJSONObject("promptFeedback")
            ?.optString("blockReason", "")
            .orEmpty()
        if (blockReason.isNotBlank()) {
            return "语音合成失败: 内容被安全策略拦截 ($blockReason)"
        }
        return null
    }
}
