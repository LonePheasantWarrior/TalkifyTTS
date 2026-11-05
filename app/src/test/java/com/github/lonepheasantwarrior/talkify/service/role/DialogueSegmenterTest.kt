package com.github.lonepheasantwarrior.talkify.service.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DialogueSegmenter] 单元测试（纯 JVM，无 Android 依赖）
 */
class DialogueSegmenterTest {

    // --- 基础切分 ---

    @Test
    fun `无引号全文为旁白`() {
        val result = DialogueSegmenter.segment("夜色渐深，雨停了。", readQuotes = false)
        assertEquals(1, result.size)
        assertEquals(SegmentKind.NARRATOR, result[0].kind)
        assertEquals("夜色渐深，雨停了。", result[0].text)
    }

    @Test
    fun `标准旁白对话旁白三段`() {
        val text = "他推开门，说：“我回来了。”然后坐下了。"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        assertEquals(3, result.size)
        assertEquals(SegmentKind.NARRATOR, result[0].kind)
        assertEquals("他推开门，说：", result[0].text)
        assertEquals(SegmentKind.DIALOGUE, result[1].kind)
        assertEquals("我回来了。", result[1].text)
        assertEquals(SegmentKind.NARRATOR, result[2].kind)
        assertEquals("然后坐下了。", result[2].text)
    }

    @Test
    fun `引号被去除且对话携带前文上下文`() {
        val text = "张三说：“今天天气不错。”"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        val dialogue = result.first { it.kind == SegmentKind.DIALOGUE }
        assertEquals("今天天气不错。", dialogue.text)
        assertEquals("张三说：", dialogue.contextBefore)
        assertNull(dialogue.speaker)
    }

    @Test
    fun `readQuotes 为 true 时保留引号`() {
        val text = "他说：“你好。”"
        val result = DialogueSegmenter.segment(text, readQuotes = true)

        val dialogue = result.first { it.kind == SegmentKind.DIALOGUE }
        assertEquals("“你好。”", dialogue.text)
    }

    // --- 多种引号 ---

    @Test
    fun `直角引号与单引号`() {
        val text = "她小声道：「别出声。」‘嗯。’他回答。"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        val kinds = result.map { it.kind }
        assertEquals(
            listOf(
                SegmentKind.NARRATOR, SegmentKind.DIALOGUE,
                SegmentKind.DIALOGUE, SegmentKind.NARRATOR
            ),
            kinds
        )
        assertEquals("别出声。", result[1].text)
        assertEquals("嗯。", result[2].text)
        assertEquals("他回答。", result[3].text)
    }

    @Test
    fun `英文直引号`() {
        val text = "He said, \"Hello world.\" Then he left."
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        assertEquals(3, result.size)
        assertEquals(SegmentKind.NARRATOR, result[0].kind)
        assertEquals("He said,", result[0].text)
        assertEquals(SegmentKind.DIALOGUE, result[1].kind)
        assertEquals("Hello world.", result[1].text)
        assertEquals(SegmentKind.NARRATOR, result[2].kind)
        assertEquals("Then he left.", result[2].text)
    }

    @Test
    fun `嵌套引号只认最外层`() {
        val text = "她说：“他说‘不行’，然后就走了。”"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        assertEquals(2, result.size)
        assertEquals(SegmentKind.DIALOGUE, result[1].kind)
        // 内层单引号字符保留
        assertEquals("他说‘不行’，然后就走了。", result[1].text)
    }

    @Test
    fun `相邻两段对话中间无旁白`() {
        val text = "“早上好。”“晚上好。”"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        assertEquals(2, result.size)
        assertTrue(result.all { it.kind == SegmentKind.DIALOGUE })
        assertEquals("早上好。", result[0].text)
        assertEquals("晚上好。", result[1].text)
    }

    // --- 未闭合引号 ---

    @Test
    fun `未闭合引号剩余文本并入对话`() {
        val text = "他小声说：“等等我"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        assertEquals(2, result.size)
        assertEquals(SegmentKind.NARRATOR, result[0].kind)
        assertEquals("他小声说：", result[0].text)
        assertEquals(SegmentKind.DIALOGUE, result[1].kind)
        assertEquals("等等我", result[1].text)
    }

    @Test
    fun `不配对的闭引号视为普通字符`() {
        val text = "这只是一个”符号。"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        assertEquals(1, result.size)
        assertEquals(SegmentKind.NARRATOR, result[0].kind)
        assertEquals("这只是一个”符号。", result[0].text)
    }

    // --- 显式说话人标记 ---

    @Test
    fun `显式标记指定说话人且不朗读标记`() {
        val text = "<<张三>>“我走了。”"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        assertEquals(1, result.size)
        assertEquals(SegmentKind.DIALOGUE, result[0].kind)
        assertEquals("我走了。", result[0].text)
        assertEquals("张三", result[0].speaker)
    }

    @Test
    fun `标记后的旁白再接对话时标记仍生效`() {
        val text = "<<张三>>轻声说：“我走了。”他关门离开。"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        assertEquals(3, result.size)
        assertEquals(SegmentKind.NARRATOR, result[0].kind)
        assertEquals("轻声说：", result[0].text)
        assertEquals("张三", result[1].speaker)
        assertEquals("我走了。", result[1].text)
        assertEquals(SegmentKind.NARRATOR, result[2].kind)
        assertNull(result[2].speaker)
    }

    @Test
    fun `标记只对其后第一段对话生效`() {
        val text = "<<张三>>“第一句。”这是旁白。“第二句。”"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        val dialogues = result.filter { it.kind == SegmentKind.DIALOGUE }
        assertEquals(2, dialogues.size)
        assertEquals("张三", dialogues[0].speaker)
        assertNull(dialogues[1].speaker)
    }

    // --- 边界 ---

    @Test
    fun `空白与纯标点文本返回空列表`() {
        assertTrue(DialogueSegmenter.segment("", readQuotes = false).isEmpty())
        assertTrue(DialogueSegmenter.segment("   ", readQuotes = false).isEmpty())
        assertTrue(DialogueSegmenter.segment("，。！？", readQuotes = false).isEmpty())
    }

    @Test
    fun `空引号对话被过滤`() {
        val text = "他说：“”然后走了。"
        val result = DialogueSegmenter.segment(text, readQuotes = false)

        assertEquals(2, result.size)
        assertEquals(SegmentKind.NARRATOR, result[0].kind)
        assertEquals(SegmentKind.NARRATOR, result[1].kind)
        assertEquals("然后走了。", result[1].text)
    }

    @Test
    fun `拼接所有段文本约等于去引号去标记的原文`() {
        val text = "张三说：“你好。”李四说：“再见。”"
        val result = DialogueSegmenter.segment(text, readQuotes = false)
        val joined = result.joinToString("") { it.text }
        assertEquals("张三说：你好。李四说：再见。", joined)
    }
}
