package com.github.lonepheasantwarrior.talkify.service.provider.impl

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleGenerateContentApiTest {

    // ---- 端点构建 ----

    @Test
    fun `builds endpoint from default base`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-tts:streamGenerateContent?alt=sse",
            GoogleGenerateContentApi.buildEndpoint("", "gemini-3.8-flash-tts")
        )
    }

    @Test
    fun `builds endpoint from custom base trimming trailing slash`() {
        assertEquals(
            "https://proxy.example.com/v1beta/models/gemini-3.8-flash-tts:streamGenerateContent?alt=sse",
            GoogleGenerateContentApi.buildEndpoint(
                "https://proxy.example.com/v1beta/models/",
                "gemini-3.8-flash-tts"
            )
        )
    }

    // ---- 请求体构建 ----

    @Test
    fun `builds request body with contents generationConfig and voice`() {
        val body = GoogleGenerateContentApi.buildRequestBody(
            model = "gemini-3.8-flash-tts",
            text = "Have a wonderful day!",
            voiceId = "Kore",
            style = "cheerful and friendly",
            sampleRate = 24000
        )

        // model 仅位于端点路径，不进请求体
        assertFalse(body.has("model"))

        val turn = body.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        val textPart = turn.getJSONArray("parts").getJSONObject(0)
        assertEquals("Have a wonderful day!", textPart.getString("text"))
        assertEquals(
            "cheerful and friendly",
            textPart.getJSONObject("speech_metadata").getString("style")
        )

        val generationConfig = body.getJSONObject("generationConfig")
        assertEquals("AUDIO", generationConfig.getJSONArray("responseModalities").getString(0))
        val audio = generationConfig.getJSONObject("responseFormat").getJSONObject("audio")
        assertEquals("AUDIO_L16", audio.getString("mimeType"))
        assertEquals(24000, audio.getInt("sampleRate"))
        val voiceConfig = generationConfig.getJSONObject("speechConfig").getJSONObject("voiceConfig")
        assertEquals("Kore", voiceConfig.getString("voice"))
    }

    @Test
    fun `omits speech metadata when style is null`() {
        val body = GoogleGenerateContentApi.buildRequestBody(
            model = "gemini-3.8-flash-tts",
            text = "Have a wonderful day!",
            voiceId = "Kore",
            style = null,
            sampleRate = 24000
        )

        val textPart = body.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)
        assertFalse(textPart.has("speech_metadata"))
    }

    // ---- 音频提取 ----

    @Test
    fun `extracts audio from camelCase inlineData`() {
        val json = JSONObject(
            """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/l16","data":"AQIDBA=="}}]}}]}"""
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GoogleGenerateContentApi.extractAudio(json))
    }

    @Test
    fun `extracts audio from snake_case inline_data`() {
        val json = JSONObject(
            """{"candidates":[{"content":{"parts":[{"inline_data":{"data":"AQIDBA=="}}]}}]}"""
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GoogleGenerateContentApi.extractAudio(json))
    }

    @Test
    fun `skips text parts and returns first audio part`() {
        val json = JSONObject(
            """{"candidates":[{"content":{"parts":[
                {"text":"transcript"},
                {"inlineData":{"data":"AQIDBA=="}}
            ]}}]}"""
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GoogleGenerateContentApi.extractAudio(json))
    }

    @Test
    fun `returns null when candidates missing`() {
        assertNull(GoogleGenerateContentApi.extractAudio(JSONObject("""{"modelVersion":"gemini"}""")))
    }

    @Test
    fun `returns null when no inline data part`() {
        val json = JSONObject(
            """{"candidates":[{"content":{"parts":[{"text":"transcript"}]}}]}"""
        )
        assertNull(GoogleGenerateContentApi.extractAudio(json))
    }

    @Test
    fun `returns null for blank and invalid base64 data`() {
        assertNull(
            GoogleGenerateContentApi.extractAudio(
                JSONObject("""{"candidates":[{"content":{"parts":[{"inlineData":{"data":""}}]}}]}""")
            )
        )
        assertNull(
            GoogleGenerateContentApi.extractAudio(
                JSONObject("""{"candidates":[{"content":{"parts":[{"inlineData":{"data":"!!!!"}}]}}]}""")
            )
        )
    }

    @Test
    fun `tolerates line breaks in base64 data`() {
        val json = JSONObject(
            """{"candidates":[{"content":{"parts":[{"inlineData":{"data":"AQID\nBA=="}}]}}]}"""
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GoogleGenerateContentApi.extractAudio(json))
    }

    // ---- 错误提取 ----

    @Test
    fun `extracts structured error message`() {
        val json = JSONObject(
            """{"error":{"code":400,"message":"Invalid voice name","status":"INVALID_ARGUMENT"}}"""
        )
        assertEquals("Invalid voice name", GoogleGenerateContentApi.extractError(json))
    }

    @Test
    fun `structured error falls back to status`() {
        val json = JSONObject("""{"error":{"code":500,"status":"INTERNAL"}}""")
        assertEquals("语音合成失败: INTERNAL", GoogleGenerateContentApi.extractError(json))
    }

    @Test
    fun `extracts prompt feedback block reason`() {
        val json = JSONObject(
            """{"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}"""
        )
        assertEquals(
            "语音合成失败: 内容被安全策略拦截 (SAFETY)",
            GoogleGenerateContentApi.extractError(json)
        )
    }

    @Test
    fun `returns null for normal streaming response`() {
        val json = JSONObject(
            """{"candidates":[{"content":{"parts":[{"inlineData":{"data":"AQ=="}}]}}],"modelVersion":"gemini-3.8-flash-tts"}"""
        )
        assertNull(GoogleGenerateContentApi.extractError(json))
    }
}
