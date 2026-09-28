package com.github.lonepheasantwarrior.talkify.service.provider.impl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class GeminiStreamEventsTest {

    // ---- 音频提取 ----

    @Test
    fun `extracts audio delta event`() {
        val json = JSONObject(
            """{"event_type":"step.delta","delta":{"type":"audio","data":"AQIDBA=="}}"""
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GeminiStreamEvents.extractAudio(json))
    }

    @Test
    fun `returns null for non audio delta`() {
        val json = JSONObject(
            """{"event_type":"step.delta","delta":{"type":"text","data":"aGVsbG8="}}"""
        )
        assertNull(GeminiStreamEvents.extractAudio(json))
    }

    @Test
    fun `returns null when delta missing`() {
        val json = JSONObject("""{"event_type":"step.finish"}""")
        assertNull(GeminiStreamEvents.extractAudio(json))
    }

    @Test
    fun `returns null for audio event with blank data`() {
        val json = JSONObject(
            """{"event_type":"step.delta","delta":{"type":"audio","data":""}}"""
        )
        assertNull(GeminiStreamEvents.extractAudio(json))
    }

    @Test
    fun `tolerates line breaks in base64 data`() {
        val json = JSONObject(
            """{"event_type":"step.delta","delta":{"type":"audio","data":"AQID\nBA=="}}"""
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GeminiStreamEvents.extractAudio(json))
    }

    // ---- 错误提取 ----

    @Test
    fun `extracts structured error message`() {
        val json = JSONObject(
            """{"error":{"code":429,"message":"quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
        )
        assertEquals("quota exceeded", GeminiStreamEvents.extractError(json))
    }

    @Test
    fun `structured error falls back to status`() {
        val json = JSONObject("""{"error":{"code":500,"status":"INTERNAL"}}""")
        assertEquals("语音合成失败: INTERNAL", GeminiStreamEvents.extractError(json))
    }

    @Test
    fun `extracts error event by event type`() {
        val json = JSONObject(
            """{"event_type":"interaction.error","message":"model not found"}"""
        )
        assertEquals("model not found", GeminiStreamEvents.extractError(json))
    }

    @Test
    fun `error event without message uses event type`() {
        val json = JSONObject("""{"event_type":"interaction.error"}""")
        assertEquals("语音合成失败: interaction.error", GeminiStreamEvents.extractError(json))
    }

    @Test
    fun `returns null for non error events`() {
        assertNull(
            GeminiStreamEvents.extractError(
                JSONObject("""{"event_type":"step.delta","delta":{"type":"audio","data":"AQ=="}}""")
            )
        )
        assertNull(GeminiStreamEvents.extractError(JSONObject("""{"event_type":"interaction.complete"}""")))
    }
}
