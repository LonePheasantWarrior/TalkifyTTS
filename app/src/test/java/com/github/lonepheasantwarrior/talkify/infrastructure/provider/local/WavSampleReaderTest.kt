package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavSampleReaderTest {

    private fun buildWav(
        sampleRate: Int = 24000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        audioFormat: Int = 1,
        samples16: ShortArray = shortArrayOf(0, 16384, -16384, 32767, -32768),
        extraChunkBeforeFmt: Boolean = false
    ): ByteArray {
        val dataPayload = ByteArray(samples16.size * 2)
        ByteBuffer.wrap(dataPayload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples16)

        val fmtPayload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(audioFormat.toShort())
            .putShort(channels.toShort())
            .putInt(sampleRate)
            .putInt(sampleRate * channels * bitsPerSample / 8)
            .putShort((channels * bitsPerSample / 8).toShort())
            .putShort(bitsPerSample.toShort())
            .array()

        val listPayload = "INFOhello".toByteArray(Charsets.US_ASCII)

        var totalSize = 12
        if (extraChunkBeforeFmt) totalSize += 8 + listPayload.size + (listPayload.size and 1)
        totalSize += 8 + fmtPayload.size
        totalSize += 8 + dataPayload.size

        val out = ByteBuffer.allocate(8 + totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(totalSize)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        if (extraChunkBeforeFmt) {
            out.put("LIST".toByteArray(Charsets.US_ASCII))
            out.putInt(listPayload.size)
            out.put(listPayload)
            if (listPayload.size % 2 == 1) out.put(0)
        }
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(fmtPayload.size)
        out.put(fmtPayload)
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(dataPayload.size)
        out.put(dataPayload)
        return out.array()
    }

    @Test
    fun `parses mono pcm16 wav with correct samples and rate`() {
        val result = WavSampleReader.parse(buildWav(sampleRate = 24000))
        assertEquals(24000, result.sampleRate)
        assertEquals(5, result.samples.size)
        assertEquals(0f, result.samples[0], 1e-6f)
        assertEquals(16384f / 32768f, result.samples[1], 1e-6f)
        assertEquals(-16384f / 32768f, result.samples[2], 1e-6f)
        assertEquals(32767f / 32768f, result.samples[3], 1e-6f)
        assertEquals(-1f, result.samples[4], 1e-6f)
    }

    @Test
    fun `downmixes stereo to mono by averaging channels`() {
        val result = WavSampleReader.parse(
            buildWav(channels = 2, samples16 = shortArrayOf(32767, -32767, 0, 0))
        )
        assertEquals(2, result.samples.size)
        assertEquals(0f, result.samples[0], 1e-6f)
        assertEquals(0f, result.samples[1], 1e-6f)
    }

    @Test
    fun `skips unknown chunks before fmt`() {
        val result = WavSampleReader.parse(buildWav(extraChunkBeforeFmt = true))
        assertEquals(5, result.samples.size)
        assertEquals(24000, result.sampleRate)
    }

    @Test
    fun `rejects non pcm audio format`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavSampleReader.parse(buildWav(audioFormat = 3))
        }
    }

    @Test
    fun `rejects non 16 bit depth`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavSampleReader.parse(buildWav(bitsPerSample = 8))
        }
    }

    @Test
    fun `rejects non riff data`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavSampleReader.parse("plain text is not wav at all".toByteArray(Charsets.US_ASCII))
        }
    }

    @Test
    fun `rejects truncated data`() {
        val full = buildWav()
        assertThrows(IllegalArgumentException::class.java) {
            WavSampleReader.parse(full.copyOfRange(0, 20))
        }
    }
}
