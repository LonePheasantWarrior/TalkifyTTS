package com.github.lonepheasantwarrior.talkify.service.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkSplitterTest {

    @Test
    fun `empty text returns empty list`() {
        assertTrue(TextChunkSplitter.split("", 10).isEmpty())
    }

    @Test
    fun `text within limit returns single chunk`() {
        assertEquals(listOf("你好世界。"), TextChunkSplitter.split("你好世界。", 10))
    }

    @Test
    fun `text exactly at limit returns single chunk`() {
        val text = "你好世界。"
        assertEquals(listOf(text), TextChunkSplitter.split(text, text.length))
    }

    @Test
    fun `splits at sentence end punctuation`() {
        val result = TextChunkSplitter.split("你好世界。你好世界。", 5)
        assertEquals(listOf("你好世界。", "你好世界。"), result)
    }

    @Test
    fun `splits at mid pause punctuation`() {
        val result = TextChunkSplitter.split("你好，世界，再见。", 4)
        assertEquals(listOf("你好，", "世界，", "再见。"), result)
    }

    @Test
    fun `splits long text without punctuation at hard boundary`() {
        val result = TextChunkSplitter.split("abcdefghij", 4)
        assertEquals(listOf("abcd", "efgh", "ij"), result)
    }

    @Test
    fun `prefers space boundary for english text`() {
        val result = TextChunkSplitter.split("ab cdefghij", 4)
        assertEquals(listOf("ab ", "cdef", "ghij"), result)
    }

    @Test
    fun `every chunk respects max length and chunks join to original`() {
        val text = "第一句有标点。第二句很长没有任何标点符号只能硬切分了。Third sentence with spaces in between words!"
        val result = TextChunkSplitter.split(text, 7)
        assertTrue(result.isNotEmpty())
        for (chunk in result) {
            assertTrue("chunk exceeds maxLength: $chunk", chunk.length <= 7)
            assertTrue("chunk is empty", chunk.isNotEmpty())
        }
        assertEquals(text, result.joinToString(""))
    }
}
