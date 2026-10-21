package com.github.lonepheasantwarrior.talkify.service.provider

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class PcmFrameAssemblerTest {

    @Test
    fun `aligned input passes through unchanged`() {
        val assembler = PcmFrameAssembler(frameSizeBytes = 2)
        val input = byteArrayOf(1, 2, 3, 4)

        assertSame(input, assembler.feed(input))
    }

    @Test
    fun `odd tail byte is carried to next chunk`() {
        val assembler = PcmFrameAssembler(frameSizeBytes = 2)

        assertArrayEquals(byteArrayOf(1, 2), assembler.feed(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), assembler.feed(byteArrayOf(4, 5, 6)))
    }

    @Test
    fun `single byte chunks are joined into complete frames`() {
        val assembler = PcmFrameAssembler(frameSizeBytes = 2)

        assertArrayEquals(ByteArray(0), assembler.feed(byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(1, 2), assembler.feed(byteArrayOf(2)))
        assertArrayEquals(ByteArray(0), assembler.feed(byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(3, 4), assembler.feed(byteArrayOf(4)))
    }

    @Test
    fun `stereo frame is aligned to four bytes`() {
        val assembler = PcmFrameAssembler(frameSizeBytes = 4)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), assembler.feed(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), assembler.feed(byteArrayOf(7, 8)))
    }

    @Test
    fun `empty feed does not consume pending bytes`() {
        val assembler = PcmFrameAssembler(frameSizeBytes = 2)

        assertArrayEquals(ByteArray(0), assembler.feed(byteArrayOf(9)))
        assertArrayEquals(ByteArray(0), assembler.feed(ByteArray(0)))
        assertArrayEquals(byteArrayOf(9, 10), assembler.feed(byteArrayOf(10)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non positive frame size is rejected`() {
        PcmFrameAssembler(frameSizeBytes = 0)
    }

    @Test
    fun `concatenated output equals original stream minus incomplete trailing frame`() {
        val frameSize = 2
        val original = ByteArray(10_000) { ((it * 31) % 251).toByte() }
        val assembler = PcmFrameAssembler(frameSize)
        val output = ByteArrayOutputStream()
        val random = Random(20261021)

        var offset = 0
        while (offset < original.size) {
            val size = minOf(original.size - offset, random.nextInt(1, 38))
            output.write(assembler.feed(original.copyOfRange(offset, offset + size)))
            offset += size
        }

        val expectedSize = original.size - original.size % frameSize
        assertArrayEquals(original.copyOf(expectedSize), output.toByteArray())
    }

    @Test
    fun `audio config computes frame size from format and channels`() {
        assertEquals(
            2,
            AudioConfig(sampleRate = 24000, audioFormat = AudioFormat.ENCODING_PCM_16BIT, channelCount = 1).frameSizeBytes
        )
        assertEquals(
            4,
            AudioConfig(sampleRate = 24000, audioFormat = AudioFormat.ENCODING_PCM_16BIT, channelCount = 2).frameSizeBytes
        )
        assertEquals(
            4,
            AudioConfig(sampleRate = 48000, audioFormat = AudioFormat.ENCODING_PCM_FLOAT, channelCount = 1).frameSizeBytes
        )
        assertEquals(
            1,
            AudioConfig(sampleRate = 16000, audioFormat = AudioFormat.ENCODING_PCM_8BIT, channelCount = 1).frameSizeBytes
        )
    }
}
