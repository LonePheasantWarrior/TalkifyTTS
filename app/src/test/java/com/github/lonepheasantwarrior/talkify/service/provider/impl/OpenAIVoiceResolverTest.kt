package com.github.lonepheasantwarrior.talkify.service.provider.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIVoiceResolverTest {

    private val presetVoices = listOf("alloy", "coral", "nova")

    @Test
    fun `custom voice id takes precedence`() {
        assertEquals(
            "fish-speech-v2",
            OpenAIVoiceResolver.resolve(
                customVoiceId = "fish-speech-v2",
                selectedVoiceId = "coral",
                presetVoiceIds = presetVoices,
                fallbackVoiceId = "alloy"
            )
        )
    }

    @Test
    fun `custom voice id is trimmed`() {
        assertEquals(
            "nova2",
            OpenAIVoiceResolver.resolve(
                customVoiceId = "  nova2  ",
                selectedVoiceId = "coral",
                presetVoiceIds = presetVoices,
                fallbackVoiceId = "alloy"
            )
        )
    }

    @Test
    fun `blank custom voice id falls back to selected voice`() {
        assertEquals(
            "coral",
            OpenAIVoiceResolver.resolve(
                customVoiceId = "   ",
                selectedVoiceId = "coral",
                presetVoiceIds = presetVoices,
                fallbackVoiceId = "alloy"
            )
        )
    }

    @Test
    fun `empty selected voice falls back to first preset voice`() {
        assertEquals(
            "alloy",
            OpenAIVoiceResolver.resolve(
                customVoiceId = "",
                selectedVoiceId = "",
                presetVoiceIds = presetVoices,
                fallbackVoiceId = "alloy"
            )
        )
    }

    @Test
    fun `empty preset list falls back to fallback voice id`() {
        assertEquals(
            "alloy",
            OpenAIVoiceResolver.resolve(
                customVoiceId = "",
                selectedVoiceId = "",
                presetVoiceIds = emptyList(),
                fallbackVoiceId = "alloy"
            )
        )
    }

    @Test
    fun `all sources empty resolves to empty string`() {
        assertEquals(
            "",
            OpenAIVoiceResolver.resolve(
                customVoiceId = "",
                selectedVoiceId = "",
                presetVoiceIds = emptyList(),
                fallbackVoiceId = ""
            )
        )
    }
}
