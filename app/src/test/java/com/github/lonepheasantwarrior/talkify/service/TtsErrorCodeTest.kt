package com.github.lonepheasantwarrior.talkify.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsErrorCodeTest {

    @Test
    fun getErrorMessage_shouldNotDuplicate_whenDetailContainsBaseMessage() {
        val errorCode = TtsErrorCode.ERROR_API_AUTH_FAILED
        val baseMessage = "认证失败，请检查 API Key 配置"
        val detailMessage = "认证失败，请检查 API Key 配置 (Invalid API-key provided.)"

        val result = TtsErrorCode.getErrorMessage(errorCode, detailMessage)

        assertEquals(detailMessage, result)
    }

    @Test
    fun getErrorMessage_shouldFormatCorrectly_whenDetailDoesNotContainBaseMessage() {
        val errorCode = TtsErrorCode.ERROR_API_AUTH_FAILED
        val detailMessage = "Invalid API-key provided."

        val result = TtsErrorCode.getErrorMessage(errorCode, detailMessage)
        // Expected: "认证失败，请检查 API Key 配置 (Invalid API-key provided.)"
        val expected = "认证失败，请检查 API Key 配置 (Invalid API-key provided.)"

        assertEquals(expected, result)
    }

    @Test
    fun getErrorMessage_shouldReturnBaseMessage_whenDetailIsNull() {
        val errorCode = TtsErrorCode.ERROR_API_AUTH_FAILED
        val baseMessage = "认证失败，请检查 API Key 配置"

        val result = TtsErrorCode.getErrorMessage(errorCode, null)

        assertEquals(baseMessage, result)
    }

    @Test
    fun inferErrorCodeFromMessage_shouldDetectAuthFailure() {
        val message = "Invalid API Key provided"
        val errorCode = TtsErrorCode.inferErrorCodeFromMessage(message)
        assertEquals(TtsErrorCode.ERROR_API_AUTH_FAILED, errorCode)
    }

    @Test
    fun inferErrorCodeFromMessage_shouldDetectNetworkTimeout() {
        val message = "Connection timeout"
        val errorCode = TtsErrorCode.inferErrorCodeFromMessage(message)
        assertEquals(TtsErrorCode.ERROR_NETWORK_TIMEOUT, errorCode)
    }
}
