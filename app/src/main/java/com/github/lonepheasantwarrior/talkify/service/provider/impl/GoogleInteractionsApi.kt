package com.github.lonepheasantwarrior.talkify.service.provider.impl

import com.github.lonepheasantwarrior.talkify.domain.model.GoogleConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini TTS - interactions 接口规范实现（默认规范）
 *
 * 端点：`POST /v1beta/interactions`（`stream: true`），完整端点，可经配置覆盖。
 *
 * 请求体：`input[].content[]` 承载逐字稿，风格经 `annotations[].speech_metadata.style`；
 * 音频输出经 `response_format` 显式指定 audio/l16（headerless raw PCM 16bit 小端）+ 采样率；
 * 音色经 `generation_config.speech_config[]`。
 *
 * 流式事件：音频增量形态为
 * `{"event_type": "step.delta", "delta": {"type": "audio", "data": "<base64>"}}`。
 */
internal object GoogleInteractionsApi : GoogleApiSpec {

    /** 默认端点（中国大陆无法直连 Google 服务，可指向反向代理或兼容服务） */
    const val DEFAULT_API_URL = GoogleConfig.DEFAULT_INTERACTIONS_API_URL

    /** 流式响应音频格式：audio/l16（headerless raw PCM） */
    private const val MIME_TYPE_RAW_PCM = "audio/l16"

    override fun buildEndpoint(customApiUrl: String, model: String): String {
        // 端点与模型解耦，自定义地址即完整端点
        return customApiUrl.ifBlank { DEFAULT_API_URL }
    }

    override fun buildRequestBody(
        model: String,
        text: String,
        voiceId: String,
        style: String?,
        sampleRate: Int
    ): JSONObject {
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", text)
            if (style != null) {
                put(
                    "annotations",
                    JSONArray().put(
                        JSONObject().apply {
                            put("type", "speech_metadata")
                            put("style", style)
                        }
                    )
                )
            }
        }

        return JSONObject().apply {
            put("model", model)
            put(
                "input",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "user_input")
                        put("content", JSONArray().put(textContent))
                    }
                )
            )
            put(
                "response_format",
                JSONObject().apply {
                    put("type", "audio")
                    put("mime_type", MIME_TYPE_RAW_PCM)
                    put("sample_rate", sampleRate)
                }
            )
            put(
                "generation_config",
                JSONObject().apply {
                    put(
                        "speech_config",
                        JSONArray().put(JSONObject().put("voice", voiceId))
                    )
                }
            )
            put("stream", true)
        }
    }

    override fun extractAudio(json: JSONObject): ByteArray? {
        val delta = json.optJSONObject("delta") ?: return null
        if (delta.optString("type") != "audio") return null
        return decodeBase64Audio(delta.optString("data"))
    }

    override fun extractError(json: JSONObject): String? {
        extractGoogleErrorObject(json)?.let { return it }

        // 错误事件形态：{"event_type": "...error...", "message": "..."}
        val eventType = json.optString("event_type", "")
        if (eventType.isNotBlank() && eventType.contains("error", ignoreCase = true)) {
            val message = json.optString("message", "")
            return message.ifBlank { "语音合成失败: $eventType" }
        }
        return null
    }
}
