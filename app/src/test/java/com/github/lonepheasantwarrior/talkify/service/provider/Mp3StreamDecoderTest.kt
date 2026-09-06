package com.github.lonepheasantwarrior.talkify.service.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class Mp3StreamDecoderTest {

    @Test
    fun `converts shorts to little endian pcm bytes`() {
        // 0x0102 → [0x02, 0x01]；-1 (0xFFFF) → [0xFF, 0xFF]
        val bytes = Mp3StreamDecoder.shortArrayToByteArray(shortArrayOf(0x0102, -1), 2)
        assertEquals(4, bytes.size)
        assertEquals(0x02, bytes[0].toInt() and 0xFF)
        assertEquals(0x01, bytes[1].toInt() and 0xFF)
        assertEquals(0xFF, bytes[2].toInt() and 0xFF)
        assertEquals(0xFF, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `uses only requested length`() {
        val bytes = Mp3StreamDecoder.shortArrayToByteArray(shortArrayOf(1, 2, 3), 2)
        assertEquals(4, bytes.size)
        assertEquals(1, bytes[0].toInt() and 0xFF)
        assertEquals(0, bytes[1].toInt() and 0xFF)
        assertEquals(2, bytes[2].toInt() and 0xFF)
        assertEquals(0, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `zero length yields empty array`() {
        assertEquals(0, Mp3StreamDecoder.shortArrayToByteArray(shortArrayOf(1, 2), 0).size)
    }

    @Test
    fun `round trips negative samples`() {
        val samples = shortArrayOf(-32768, 32767, -1, 1)
        val bytes = Mp3StreamDecoder.shortArrayToByteArray(samples, samples.size)
        assertEquals(8, bytes.size)
        for (i in samples.indices) {
            val reconstructed = ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 2].toInt() and 0xFF)
            assertEquals(samples[i].toInt(), reconstructed.toShort().toInt())
        }
    }
}
