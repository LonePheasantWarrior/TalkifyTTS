package com.github.lonepheasantwarrior.talkify.service.provider

import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.GoogleConfig
import com.github.lonepheasantwarrior.talkify.domain.model.OpenAIConfig
import com.github.lonepheasantwarrior.talkify.domain.model.VolcengineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.XiaomiConfig
import com.github.lonepheasantwarrior.talkify.service.provider.impl.GoogleProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.OpenAIProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.VolcengineProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.XiaomiProvider
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求级音色覆盖的供应商契约测试：真实调用各供应商的请求构建点，
 * 解析实际请求体，而不是只测公共参数。
 */
class VoiceOverrideContractTest {

    private fun buildRequest(
        provider: Any,
        text: String,
        config: BaseProviderConfig,
        params: SynthesisParams,
    ): Request {
        val method = provider.javaClass.getDeclaredMethod(
            "buildHttpRequest",
            String::class.java,
            BaseProviderConfig::class.java,
            SynthesisParams::class.java,
        )
        method.isAccessible = true
        return method.invoke(provider, text, config, params) as Request
    }

    private fun bodyJson(request: Request): JSONObject {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    @Test
    fun `xiaomi request uses override voice`() {
        val provider = XiaomiProvider()
        val config = XiaomiConfig(apiKey = "k", voiceId = "config-voice")
        val request = buildRequest(
            provider, "你好", config, SynthesisParams(voiceOverride = "role-voice")
        )
        assertEquals(
            "role-voice",
            bodyJson(request).getJSONObject("audio").getString("voice")
        )
    }

    @Test
    fun `volcengine request uses override voice`() {
        val provider = VolcengineProvider()
        val config = VolcengineConfig(apiKey = "k", voiceId = "config-voice")
        val request = buildRequest(
            provider, "你好", config, SynthesisParams(voiceOverride = "role-voice")
        )
        assertEquals(
            "role-voice",
            bodyJson(request).getJSONObject("req_params").getString("speaker")
        )
    }

    @Test
    fun `google interactions request uses override voice`() {
        val provider = GoogleProvider()
        val config = GoogleConfig(apiKey = "k", voiceId = "config-voice")
        val request = buildRequest(
            provider, "hello", config, SynthesisParams(voiceOverride = "role-voice")
        )
        val voice = bodyJson(request)
            .getJSONObject("generation_config")
            .getJSONArray("speech_config")
            .getJSONObject(0)
            .getString("voice")
        assertEquals("role-voice", voice)
    }

    @Test
    fun `openai override takes precedence over custom voice id`() {
        val provider = OpenAIProvider()
        val config = OpenAIConfig(
            apiKey = "k",
            voiceId = "alloy",
            customVoiceId = "custom-voice",
        )
        val request = buildRequest(
            provider, "hello", config, SynthesisParams(voiceOverride = "role-voice")
        )
        assertEquals("role-voice", bodyJson(request).getString("voice"))

        // 普通单音色仍维持“自定义声音优先”的既有优先级
        val normal = buildRequest(provider, "hello", config, SynthesisParams())
        assertEquals("custom-voice", bodyJson(normal).getString("voice"))
    }

    @Test
    fun `openai effective voice reflects override for telemetry`() {
        val provider = OpenAIProvider()
        val config = OpenAIConfig(apiKey = "k", voiceId = "alloy", customVoiceId = "custom")
        assertEquals("role", provider.getEffectiveVoiceId(config, "role"))
        assertEquals("custom", provider.getEffectiveVoiceId(config))
    }

    @Test
    fun `openai capability is known for non blank voice`() {
        val provider = OpenAIProvider()
        val capability = provider.getVoiceOutputCapability(OpenAIConfig(apiKey = "k"), "any")
        assertTrue(capability is VoiceOutputCapability.Known)
    }

    @Test
    fun `azure capability is unsupported because mp3 format is dynamic`() {
        val provider = com.github.lonepheasantwarrior.talkify.service.provider.impl.AzureProvider()
        val capability = provider.getVoiceOutputCapability(
            com.github.lonepheasantwarrior.talkify.domain.model.AzureConfig(),
            "zh-CN-XiaoxiaoNeural",
        )
        assertTrue(capability is VoiceOutputCapability.Unsupported)
    }
}
