package com.github.lonepheasantwarrior.talkify.service.provider

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WavHeaderSanitizerTest {

    private fun buildWavData(payloadSize: Int): ByteArray {
        val data = ByteArray(44 + payloadSize)
        "RIFF".toByteArray().copyInto(data, 0)
        "WAVE".toByteArray().copyInto(data, 8)
        for (i in 0 until payloadSize) {
            data[44 + i] = i.toByte()
        }
        return data
    }

    @Test
    fun `strips 44 byte wav header`() {
        val data = buildWavData(payloadSize = 10)
        val result = WavHeaderSanitizer.stripWavHeader(data)
        assertEquals(10, result.size)
        for (i in 0 until 10) {
            assertEquals(i.toByte(), result[i])
        }
    }

    @Test
    fun `header only data yields empty payload`() {
        val data = buildWavData(payloadSize = 0)
        assertEquals(0, WavHeaderSanitizer.stripWavHeader(data).size)
    }

    @Test
    fun `non wav data returns same instance`() {
        val data = "plain pcm bytes and more bytes padding padding".toByteArray()
        assertSame(data, WavHeaderSanitizer.stripWavHeader(data))
    }

    @Test
    fun `short data with riff prefix returns same instance`() {
        val data = ByteArray(20)
        "RIFF".toByteArray().copyInto(data, 0)
        "WAVE".toByteArray().copyInto(data, 8)
        assertSame(data, WavHeaderSanitizer.stripWavHeader(data))
    }

    @Test
    fun `rifx marker is not treated as wav`() {
        val data = ByteArray(100)
        "RIFX".toByteArray().copyInto(data, 0)
        "WAVE".toByteArray().copyInto(data, 8)
        assertSame(data, WavHeaderSanitizer.stripWavHeader(data))
    }

    @Test
    fun `payload content is preserved exactly`() {
        val payload = ByteArray(64) { (it * 7).toByte() }
        val data = buildWavData(payload.size)
        payload.copyInto(data, 44)
        assertArrayEquals(payload, WavHeaderSanitizer.stripWavHeader(data))
    }
}
