package com.github.lonepheasantwarrior.talkify.service.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ParamMappersTest {

    // ---- Azure ----

    @Test
    fun `azure rate mapping`() {
        assertEquals("+0%", AzureParamMapper.convertRate(100f))
        assertEquals("+100%", AzureParamMapper.convertRate(200f))
        assertEquals("-50%", AzureParamMapper.convertRate(50f))
        assertEquals("+50%", AzureParamMapper.convertRate(150f))
    }

    @Test
    fun `azure volume mapping`() {
        assertEquals("+0%", AzureParamMapper.convertVolume(1.0f))
        assertEquals("-50%", AzureParamMapper.convertVolume(0.5f))
        assertEquals("-100%", AzureParamMapper.convertVolume(0f))
    }

    @Test
    fun `azure pitch mapping`() {
        assertEquals("+0Hz", AzureParamMapper.convertPitch(100f))
        assertEquals("+50Hz", AzureParamMapper.convertPitch(200f))
        assertEquals("-50Hz", AzureParamMapper.convertPitch(0f))
    }

    // ---- Volcengine ----

    @Test
    fun `volcengine speech rate mapping`() {
        assertEquals(-50, VolcengineParamMapper.convertSpeechRate(50f))
        assertEquals(0, VolcengineParamMapper.convertSpeechRate(100f))
        assertEquals(100, VolcengineParamMapper.convertSpeechRate(200f))
        assertEquals(50, VolcengineParamMapper.convertSpeechRate(150f))
        assertEquals(-25, VolcengineParamMapper.convertSpeechRate(75f))
    }

    @Test
    fun `volcengine loudness mapping`() {
        assertEquals(-50, VolcengineParamMapper.convertLoudnessRate(0.25f))
        assertEquals(100, VolcengineParamMapper.convertLoudnessRate(1.0f))
        assertEquals(0, VolcengineParamMapper.convertLoudnessRate(0.5f))
        assertEquals(50, VolcengineParamMapper.convertLoudnessRate(0.75f))
    }

    // ---- Tencent ----

    @Test
    fun `tencent speech rate discrete mapping`() {
        assertEquals(-2f, TencentParamMapper.convertSpeechRate(50f))
        assertEquals(-1f, TencentParamMapper.convertSpeechRate(80f))
        assertEquals(0f, TencentParamMapper.convertSpeechRate(120f))
        assertEquals(1f, TencentParamMapper.convertSpeechRate(150f))
        assertEquals(2f, TencentParamMapper.convertSpeechRate(200f))
        assertEquals(6f, TencentParamMapper.convertSpeechRate(201f))
    }

    @Test
    fun `tencent volume mapping`() {
        assertEquals(-10f, TencentParamMapper.convertVolume(0f))
        assertEquals(10f, TencentParamMapper.convertVolume(1f))
        assertEquals(0f, TencentParamMapper.convertVolume(0.5f))
        assertEquals(5f, TencentParamMapper.convertVolume(0.75f))
    }

    // ---- MiniMax ----

    @Test
    fun `minimax speech rate mapping`() {
        assertEquals(0.5f, MiniMaxParamMapper.convertSpeechRate(50f))
        assertEquals(2.0f, MiniMaxParamMapper.convertSpeechRate(200f))
        assertEquals(1.0f, MiniMaxParamMapper.convertSpeechRate(100f))
        assertEquals(1.5f, MiniMaxParamMapper.convertSpeechRate(150f))
        assertEquals(0.5f, MiniMaxParamMapper.convertSpeechRate(0f))
        assertEquals(2.0f, MiniMaxParamMapper.convertSpeechRate(250f))
    }

    @Test
    fun `minimax volume mapping`() {
        assertEquals(2.0f, MiniMaxParamMapper.convertVolume(1.0f))
        assertEquals(1.0f, MiniMaxParamMapper.convertVolume(0f))
        assertEquals(1.5f, MiniMaxParamMapper.convertVolume(0.5f))
    }
}
