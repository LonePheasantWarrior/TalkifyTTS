package com.github.lonepheasantwarrior.talkify.service.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderErrorParsersTest {

    // ---- Volcengine ----

    @Test
    fun `volcengine parses root level message`() {
        val body = """{"code":1001,"message":"quota exceeded"}"""
        assertEquals("quota exceeded", VolcengineErrorParser.parse(body))
    }

    @Test
    fun `volcengine parses legacy header message`() {
        val body = """{"header":{"code":1001,"message":"legacy msg"}}"""
        assertEquals("legacy msg", VolcengineErrorParser.parse(body))
    }

    @Test
    fun `volcengine maps known error code with empty message`() {
        val body = """{"header":{"code":45000001}}"""
        assertEquals(
            "认证失败：请检查 App ID 和 Access Key 是否正确 (code: 45000001)",
            VolcengineErrorParser.parse(body)
        )
    }

    @Test
    fun `volcengine falls back to generic message for invalid json`() {
        assertEquals("语音合成失败", VolcengineErrorParser.parse("not-a-json"))
    }

    @Test
    fun `volcengine unknown code includes original message`() {
        val body = """{"code":99999,"message":"boom"}"""
        assertEquals("boom", VolcengineErrorParser.parse(body))
    }

    // ---- MiniMax ----

    @Test
    fun `minimax maps known status codes`() {
        assertEquals("鉴权失败，请检查 API Key", MiniMaxErrorParser.parse(1004, ""))
        assertEquals("请求超时，请稍后重试", MiniMaxErrorParser.parse(1001, ""))
        assertEquals("触发限流，请稍后重试", MiniMaxErrorParser.parse(1002, ""))
    }

    @Test
    fun `minimax includes status msg for parameter errors`() {
        assertEquals("输入参数错误: bad voice", MiniMaxErrorParser.parse(2013, "bad voice"))
    }

    @Test
    fun `minimax unknown code falls back to generic message`() {
        assertEquals("语音合成失败: oops (code: 9999)", MiniMaxErrorParser.parse(9999, "oops"))
    }

    // ---- Tencent ----

    @Test
    fun `tencent null code uses original message`() {
        assertEquals("语音合成失败: 未知错误", TencentErrorMapper.friendlyErrorMessage(null, null))
        assertEquals("语音合成失败: net down", TencentErrorMapper.friendlyErrorMessage(null, "net down"))
    }

    @Test
    fun `tencent maps known error codes`() {
        assertEquals(
            "语音合成失败: 连接服务器失败 (错误码: -409)",
            TencentErrorMapper.friendlyErrorMessage(-409, "raw")
        )
        assertEquals(
            "语音合成失败: 资源包配额已用尽，请检查您的资源包 (错误码: 3022)",
            TencentErrorMapper.friendlyErrorMessage(3022, "raw")
        )
    }

    @Test
    fun `tencent unknown code passes through original message`() {
        assertEquals(
            "语音合成失败: boom (错误码: 12345)",
            TencentErrorMapper.friendlyErrorMessage(12345, "boom")
        )
    }
}
