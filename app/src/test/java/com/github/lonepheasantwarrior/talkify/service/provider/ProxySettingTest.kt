package com.github.lonepheasantwarrior.talkify.service.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySettingTest {

    // ---- 未配置（协议为"无"或空，直连） ----

    @Test
    fun `none protocol means not configured`() {
        assertEquals(ProxyParseResult.NotConfigured, ProxySettings.parse("none", "", ""))
        assertEquals(ProxyParseResult.NotConfigured, ProxySettings.parse("none", "127.0.0.1", "7890"))
        assertEquals(ProxyParseResult.NotConfigured, ProxySettings.parse("NONE", "127.0.0.1", "7890"))
        assertEquals(ProxyParseResult.NotConfigured, ProxySettings.parse("", "", ""))
        assertEquals(ProxyParseResult.NotConfigured, ProxySettings.parse("  ", "127.0.0.1", "7890"))
    }

    // ---- 无效配置 ----

    @Test
    fun `concrete protocol with blank host is invalid`() {
        assertTrue(ProxySettings.parse("http", "", "7890") is ProxyParseResult.Invalid)
        assertTrue(ProxySettings.parse("socks", "   ", "1080") is ProxyParseResult.Invalid)
    }

    @Test
    fun `blank host reports host error before port`() {
        val result = ProxySettings.parse("http", "", "abc")
        assertTrue(result is ProxyParseResult.Invalid)
    }

    @Test
    fun `non numeric port is invalid`() {
        val result = ProxySettings.parse("http", "127.0.0.1", "abc")
        assertTrue(result is ProxyParseResult.Invalid)
    }

    @Test
    fun `out of range port is invalid`() {
        assertTrue(ProxySettings.parse("http", "127.0.0.1", "0") is ProxyParseResult.Invalid)
        assertTrue(ProxySettings.parse("http", "127.0.0.1", "65536") is ProxyParseResult.Invalid)
        assertTrue(ProxySettings.parse("http", "127.0.0.1", "-1") is ProxyParseResult.Invalid)
    }

    @Test
    fun `blank port with host is invalid`() {
        assertTrue(ProxySettings.parse("http", "127.0.0.1", "") is ProxyParseResult.Invalid)
    }

    // ---- 有效配置 ----

    @Test
    fun `http proxy parsed`() {
        val result = ProxySettings.parse("http", "127.0.0.1", "7890")
        assertTrue(result is ProxyParseResult.Valid)
        assertEquals(false, (result as ProxyParseResult.Valid).setting.isSocks)
        assertEquals("127.0.0.1", result.setting.host)
        assertEquals(7890, result.setting.port)
    }

    @Test
    fun `unknown protocol falls back to http`() {
        val result = ProxySettings.parse("HTTPS", "192.168.1.1", "8080")
        assertTrue(result is ProxyParseResult.Valid)
        assertEquals(false, (result as ProxyParseResult.Valid).setting.isSocks)
    }

    @Test
    fun `socks proxy parsed case insensitive`() {
        val result = ProxySettings.parse("SOCKS", "192.168.1.1", "1080")
        assertTrue(result is ProxyParseResult.Valid)
        assertEquals(true, (result as ProxyParseResult.Valid).setting.isSocks)
    }

    @Test
    fun `host and port trimmed`() {
        val result = ProxySettings.parse(" http ", " proxy.example.com ", " 8080 ")
        assertTrue(result is ProxyParseResult.Valid)
        val setting = (result as ProxyParseResult.Valid).setting
        assertEquals("proxy.example.com", setting.host)
        assertEquals(8080, setting.port)
    }

    @Test
    fun `port boundary values accepted`() {
        assertTrue(ProxySettings.parse("http", "h", "1") is ProxyParseResult.Valid)
        assertTrue(ProxySettings.parse("http", "h", "65535") is ProxyParseResult.Valid)
    }

    // ---- Java Proxy 转换 ----

    @Test
    fun `to java proxy http type`() {
        val proxy = ProxySetting(isSocks = false, host = "127.0.0.1", port = 7890).toJavaProxy()
        assertEquals(java.net.Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as java.net.InetSocketAddress
        assertEquals("127.0.0.1", address.hostName)
        assertEquals(7890, address.port)
    }

    @Test
    fun `to java proxy socks type`() {
        val proxy = ProxySetting(isSocks = true, host = "127.0.0.1", port = 1080).toJavaProxy()
        assertEquals(java.net.Proxy.Type.SOCKS, proxy.type())
    }
}
