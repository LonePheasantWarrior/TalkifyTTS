package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryConfigValidatorTest {

    private val validId = "123e4567-e89b-12d3-a456-426614174000"

    private fun result(endpoint: String, id: String = validId) =
        TelemetryConfigValidator.validate(endpoint, id)

    @Test
    fun `合法HTTPS根路径与子路径`() {
        assertEquals(TelemetryConfigValidator.Result.VALID, result("https://telemetry.example.com"))
        assertEquals(TelemetryConfigValidator.Result.VALID, result("https://telemetry.example.com/umami"))
    }

    @Test
    fun `空host非法转义凭据query与fragment均拒绝`() {
        assertEquals(TelemetryConfigValidator.Result.INVALID, result("https:///"))
        assertEquals(TelemetryConfigValidator.Result.INVALID, result("https://example.com/%zz"))
        assertEquals(TelemetryConfigValidator.Result.INVALID, result("https://user:pass@example.com"))
        assertEquals(TelemetryConfigValidator.Result.INVALID, result("https://example.com?x=1"))
        assertEquals(TelemetryConfigValidator.Result.INVALID, result("https://example.com#frag"))
        assertEquals(TelemetryConfigValidator.Result.INVALID, result("http://example.com"))
        assertEquals(TelemetryConfigValidator.Result.INVALID, result("https://example.com:99999"))
    }

    @Test
    fun `错误UUID与缺项分类正确`() {
        assertEquals(TelemetryConfigValidator.Result.INVALID, result("https://example.com", "------------------------------------"))
        assertEquals(TelemetryConfigValidator.Result.INVALID, result("https://example.com", "123e4567-e89b-12d3-a456-42661417400"))
        assertEquals(TelemetryConfigValidator.Result.NONE, result(""))
        assertEquals(TelemetryConfigValidator.Result.NONE, result("https://example.com", ""))
    }
}
