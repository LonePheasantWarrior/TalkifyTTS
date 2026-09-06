package com.github.lonepheasantwarrior.talkify.service.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class HexCodecTest {

    @Test
    fun `decodes basic hex string`() {
        val bytes = HexCodec.decode("00ff10")
        assertEquals(3, bytes.size)
        assertEquals(0, bytes[0].toInt() and 0xFF)
        assertEquals(0xFF, bytes[1].toInt() and 0xFF)
        assertEquals(0x10, bytes[2].toInt() and 0xFF)
    }

    @Test
    fun `decodes uppercase hex string`() {
        val bytes = HexCodec.decode("0F1A")
        assertEquals(2, bytes.size)
        assertEquals(0x0F, bytes[0].toInt() and 0xFF)
        assertEquals(0x1A, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun `ignores whitespace inside hex string`() {
        val bytes = HexCodec.decode("00 FF\n01")
        assertEquals(3, bytes.size)
        assertEquals(0x00, bytes[0].toInt() and 0xFF)
        assertEquals(0xFF, bytes[1].toInt() and 0xFF)
        assertEquals(0x01, bytes[2].toInt() and 0xFF)
    }

    @Test
    fun `odd length hex string yields empty array`() {
        assertEquals(0, HexCodec.decode("abc").size)
    }

    @Test
    fun `invalid characters yield empty array`() {
        assertEquals(0, HexCodec.decode("zz11").size)
    }

    @Test
    fun `empty input yields empty array`() {
        assertEquals(0, HexCodec.decode("").size)
    }
}
