package com.github.lonepheasantwarrior.talkify.service.provider.impl

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleInteractionsApiTest {

    // ---- 端点构建 ----

    @Test
    fun `blank api url falls back to default endpoint`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/interactions",
            GoogleInteractionsApi.buildEndpoint("", "gemini-3.8-flash-lite-tts")
        )
    }

    @Test
    fun `custom api url is used as complete endpoint`() {
        assertEquals(
            "https://proxy.example.com/v1beta/interactions",
            GoogleInteractionsApi.buildEndpoint("https://proxy.example.com/v1beta/interactions", "gemini-3.8-flash-lite-tts")
        )
    }

    // ---- 请求体构建 ----

    @Test
    fun `builds request body with model input response format and voice`() {
        val body = GoogleInteractionsApi.buildRequestBody(
            model = "gemini-3.8-flash-lite-tts",
            text = "你好",
            voiceId = "Kore",
            style = null,
            sampleRate = 24000
        )

        assertEquals("gemini-3.8-flash-lite-tts", body.getString("model"))

        val input = body.getJSONArray("input")
        assertEquals(1, input.length())
        val userInput = input.getJSONObject(0)
        assertEquals("user_input", userInput.getString("type"))
        val textContent = userInput.getJSONArray("content").getJSONObject(0)
        assertEquals("text", textContent.getString("type"))
        assertEquals("你好", textContent.getString("text"))
        assertFalse(textContent.has("annotations"))

        val responseFormat = body.getJSONObject("response_format")
        assertEquals("audio", responseFormat.getString("type"))
        assertEquals("audio/l16", responseFormat.getString("mime_type"))
        assertEquals(24000, responseFormat.getInt("sample_rate"))

        val speechConfig = body.getJSONObject("generation_config").getJSONArray("speech_config")
        assertEquals("Kore", speechConfig.getJSONObject(0).getString("voice"))

        assertTrue(body.getBoolean("stream"))
    }

    @Test
    fun `attaches speech metadata style annotation when style provided`() {
        val body = GoogleInteractionsApi.buildRequestBody(
            model = "gemini-3.8-flash-lite-tts",
            text = "你好",
            voiceId = "Kore",
            style = "cheerful and friendly",
            sampleRate = 24000
        )

        val annotation = body.getJSONArray("input").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)
            .getJSONArray("annotations").getJSONObject(0)
        assertEquals("speech_metadata", annotation.getString("type"))
        assertEquals("cheerful and friendly", annotation.getString("style"))
    }

    // ---- 音频提取 ----

    @Test
    fun `extracts audio delta event`() {
        val json = JSONObject(
            """{"event_type":"step.delta","delta":{"type":"audio","data":"AQIDBA=="}}"""
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GoogleInteractionsApi.extractAudio(json))
    }

    @Test
    fun `returns null for non audio delta`() {
        val json = JSONObject(
            """{"event_type":"step.delta","delta":{"type":"text","data":"aGVsbG8="}}"""
        )
        assertNull(GoogleInteractionsApi.extractAudio(json))
    }

    @Test
    fun `returns null when delta missing`() {
        val json = JSONObject("""{"event_type":"step.finish"}""")
        assertNull(GoogleInteractionsApi.extractAudio(json))
    }

    @Test
    fun `returns null for audio event with blank data`() {
        val json = JSONObject(
            """{"event_type":"step.delta","delta":{"type":"audio","data":""}}"""
        )
        assertNull(GoogleInteractionsApi.extractAudio(json))
    }

    @Test
    fun `tolerates line breaks in base64 data`() {
        val json = JSONObject(
            """{"event_type":"step.delta","delta":{"type":"audio","data":"AQID\nBA=="}}"""
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GoogleInteractionsApi.extractAudio(json))
    }

    // ---- 错误提取 ----

    @Test
    fun `extracts structured error message`() {
        val json = JSONObject(
            """{"error":{"code":429,"message":"quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
        )
        assertEquals("quota exceeded", GoogleInteractionsApi.extractError(json))
    }

    @Test
    fun `structured error falls back to status`() {
        val json = JSONObject("""{"error":{"code":500,"status":"INTERNAL"}}""")
        assertEquals("语音合成失败: INTERNAL", GoogleInteractionsApi.extractError(json))
    }

    @Test
    fun `extracts error event by event type`() {
        val json = JSONObject(
            """{"event_type":"interaction.error","message":"model not found"}"""
        )
        assertEquals("model not found", GoogleInteractionsApi.extractError(json))
    }

    @Test
    fun `error event without message uses event type`() {
        val json = JSONObject("""{"event_type":"interaction.error"}""")
        assertEquals("语音合成失败: interaction.error", GoogleInteractionsApi.extractError(json))
    }

    @Test
    fun `returns null for non error events`() {
        assertNull(
            GoogleInteractionsApi.extractError(
                JSONObject("""{"event_type":"step.delta","delta":{"type":"audio","data":"AQ=="}}""")
            )
        )
        assertNull(GoogleInteractionsApi.extractError(JSONObject("""{"event_type":"interaction.complete"}""")))
    }
}
