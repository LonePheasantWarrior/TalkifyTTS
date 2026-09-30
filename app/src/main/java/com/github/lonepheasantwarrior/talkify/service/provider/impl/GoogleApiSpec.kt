package com.github.lonepheasantwarrior.talkify.service.provider.impl

import org.json.JSONObject

/**
 * Google Gemini TTS 接口规范抽象
 *
 * Google 供应商支持两套接口规范（配置项"接口规范"决定分派）：
 * - [GoogleInteractionsApi]：`POST /v1beta/interactions`（`stream: true`），默认规范
 * - [GoogleGenerateContentApi]：`POST /v1beta/models/{model}:streamGenerateContent?alt=sse`
 *
 * 两套规范共用同一批模型与音色、相同的 `x-goog-api-key` 认证头与 SSE 传输形态，
 * 差异收敛在端点组装、请求体结构与流式事件的音频/错误提取路径三处；
 * 传输调度（分块流水线、SSE 解析循环、代理、取消）由 [GoogleProvider] 统一承担。
 *
 * 实现为无状态纯函数对象，便于单元测试直接覆盖报文结构。
 */
internal interface GoogleApiSpec {

    /**
     * 组装单块合成的完整请求端点。
     *
     * @param customApiUrl 用户自定义 API 地址，空白时回退该规范默认端点；
     * 地址语义随规范而定（完整端点或 "/models" 基础前缀）
     * @param model 有效模型 ID
     */
    fun buildEndpoint(customApiUrl: String, model: String): String

    /**
     * 组装单块合成的请求体（规范不含某字段时忽略对应参数，如
     * generateContent 的 model 仅位于端点路径）。
     *
     * @param model 有效模型 ID
     * @param text 单块待合成文本（逐字稿）
     * @param voiceId 有效音色标识
     * @param style 风格描述（speech_metadata.style），null 表示不携带、由模型按默认风格朗读
     * @param sampleRate 音频采样率（Hz）
     */
    fun buildRequestBody(
        model: String,
        text: String,
        voiceId: String,
        style: String?,
        sampleRate: Int
    ): JSONObject

    /** 从流式事件 JSON 中提取音频数据；非音频事件或无法解析时返回 null */
    fun extractAudio(json: JSONObject): ByteArray?

    /** 从流式事件 JSON 中提取错误消息；非错误事件返回 null */
    fun extractError(json: JSONObject): String?
}

/**
 * 提取 `{"error": {"message": ..., "status": ...}}` 结构化错误对象的消息，
 * 两套规范共用的错误形态；无 error 对象时返回 null
 */
internal fun extractGoogleErrorObject(json: JSONObject): String? {
    val error = json.optJSONObject("error") ?: return null
    val message = error.optString("message", "")
    val status = error.optString("status", "")
    return when {
        message.isNotBlank() -> message
        status.isNotBlank() -> "语音合成失败: $status"
        else -> "语音合成失败"
    }
}

/**
 * base64 音频数据解码。
 *
 * MIME 解码器对换行等空白容错，避免分块 base64 偶发换行导致失败；
 * 数据为空或非法时返回 null
 */
internal fun decodeBase64Audio(data: String): ByteArray? {
    if (data.isBlank()) return null
    return try {
        java.util.Base64.getMimeDecoder().decode(data)
    } catch (_: IllegalArgumentException) {
        null
    }
}
