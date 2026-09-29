package com.github.lonepheasantwarrior.talkify.service.provider.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONObject

class OpenAIRequestBuilderTest {

    @Test
    fun `builds minimal request without instructions`() {
        val body = OpenAIRequestBuilder.build("gpt-4o-mini-tts", "你好世界", "alloy")

        assertEquals("gpt-4o-mini-tts", body.getString("model"))
        assertEquals("你好世界", body.getString("input"))
        assertEquals("alloy", body.getString("voice"))
        assertEquals(OpenAIRequestBuilder.RESPONSE_FORMAT_PCM, body.getString("response_format"))
        assertFalse(body.has("instructions"))
    }

    @Test
    fun `carries instructions when provided`() {
        val body = OpenAIRequestBuilder.build("gpt-4o-mini-tts", "hi", "coral", "speaking slowly")

        assertEquals("speaking slowly", body.getString("instructions"))
    }

    @Test
    fun `omits instructions when blank`() {
        val body = OpenAIRequestBuilder.build("gpt-4o-mini-tts", "hi", "coral", "   ")

        assertFalse(body.has("instructions"))
    }

    @Test
    fun `pcm is the fixed response format`() {
        val body = OpenAIRequestBuilder.build("tts-1", "hi", "alloy", null)

        assertEquals("tts-1", body.getString("model"))
        assertEquals("hi", body.getString("input"))
        assertEquals("alloy", body.getString("voice"))
        assertEquals("pcm", body.getString("response_format"))
        // 除上述字段外不应有多余字段（JSONObject 不重写 equals，逐字段断言）
        val keys = mutableListOf<String>()
        val iterator = body.keys()
        while (iterator.hasNext()) keys.add(iterator.next())
        assertEquals(setOf("model", "input", "voice", "response_format"), keys.toSet())
    }
}
