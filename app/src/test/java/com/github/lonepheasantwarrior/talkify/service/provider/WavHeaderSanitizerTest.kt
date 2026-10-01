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

    // ==================== chunk 遍历（N11） ====================

    /** 构造带 extra chunk（LIST/fmt ）的 WAV：data 段不在固定 44 字节处 */
    private fun buildWavWithExtraChunks(payload: ByteArray): ByteArray {
        fun bytes(s: String) = s.toByteArray().toList()
        fun intLe(v: Int) = listOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
        )
        val header = mutableListOf<Byte>()
        header += bytes("RIFF")
        header += intLe(4 + (8 + 4) + (8 + 16) + (8 + payload.size)) // 顶层 size（剥离器不读）
        header += bytes("WAVE")
        header += bytes("LIST"); header += intLe(4); header += bytes("INFO")
        header += bytes("fmt "); header += intLe(16); header += ByteArray(16).toList()
        header += bytes("data"); header += intLe(payload.size)
        return header.toByteArray() + payload
    }

    @Test
    fun `strips header with extra chunks before data segment`() {
        val payload = ByteArray(32) { (it * 3).toByte() }
        val data = buildWavWithExtraChunks(payload)
        // 头部实际 56 字节（44 字节固定剥离会残留 12 字节 extra chunk）
        assertEquals(payload.size + 56, data.size)
        assertArrayEquals(payload, WavHeaderSanitizer.stripWavHeader(data))
    }

    @Test
    fun `streaming first packet with partial data chunk strips available bytes`() {
        val payload = ByteArray(100) { it.toByte() }
        val full = buildWavWithExtraChunks(payload)
        // 模拟流式首包：仅携带 data 段前 10 字节
        val partial = full.copyOfRange(0, 56 + 10)
        val result = WavHeaderSanitizer.stripWavHeader(partial)
        assertEquals(10, result.size)
        assertArrayEquals(payload.copyOfRange(0, 10), result)
    }

    /** 构造 data 段 size 字段与实际载荷长度不一致的 WAV（模拟流式占位写法），头结构与上方 extra-chunk 布局一致 */
    private fun buildWavWithDeclaredDataSize(declaredSize: Int, payload: ByteArray): ByteArray {
        val data = buildWavWithExtraChunks(payload)
        // data chunk 的 size 字段位于偏移 52..55（12 RIFF + 4 size + 4 WAVE + 20 LIST + 24 fmt）
        listOf(0, 8, 16, 24).forEachIndexed { i, shift ->
            data[52 + i] = ((declaredSize shr shift) and 0xFF).toByte()
        }
        return data
    }

    @Test
    fun `data chunk size zero with trailing bytes returns all trailing audio`() {
        val payload = ByteArray(24) { (it + 1).toByte() }
        val data = buildWavWithDeclaredDataSize(declaredSize = 0, payload = payload)
        assertArrayEquals(payload, WavHeaderSanitizer.stripWavHeader(data))
    }

    @Test
    fun `data chunk size 0xFFFFFFFF streaming placeholder returns all trailing audio`() {
        val payload = ByteArray(24) { (it + 1).toByte() }
        // 0xFFFFFFFF 小端读作 -1：部分写入器对流式动态长度使用该占位
        val data = buildWavWithDeclaredDataSize(declaredSize = -1, payload = payload)
        assertArrayEquals(payload, WavHeaderSanitizer.stripWavHeader(data))
    }
}
