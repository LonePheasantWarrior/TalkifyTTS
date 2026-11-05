package com.github.lonepheasantwarrior.talkify.testutil

import android.content.Context
import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.service.provider.VoiceOutputCapability
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 测试用可控供应商：记录每次请求的文本/音色覆盖，并按注入行为同步/异步回调。
 */
class FakeTtsProvider(
    private val behavior: (String, SynthesisParams, TtsSynthesisListener) -> Unit,
) : TtsProviderApi {

    data class Call(
        val text: String,
        val voiceOverride: String?,
        val params: SynthesisParams,
    )

    val calls = CopyOnWriteArrayList<Call>()

    @Volatile
    var stopped: Boolean = false

    @Volatile
    var released: Boolean = false

    @Volatile
    var configured: Boolean = true

    var capability: (String) -> VoiceOutputCapability = {
        VoiceOutputCapability.Known(AudioConfig(sampleRate = 24000))
    }

    var reportedAudioConfig: AudioConfig = AudioConfig(sampleRate = 24000)

    override fun getProviderId(): String = "fake"
    override fun getProviderName(): String = "Fake"

    override fun isConfigured(config: BaseProviderConfig?): Boolean = configured

    override fun synthesize(
        text: String,
        params: SynthesisParams,
        config: BaseProviderConfig,
        listener: TtsSynthesisListener,
    ) {
        calls += Call(text, params.voiceOverride, params)
        behavior(text, params, listener)
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
    }

    override fun getAudioConfig(): AudioConfig = reportedAudioConfig
    override fun getSupportedLanguages(): Set<String> = setOf("zho", "eng")
    override fun getDefaultLanguage(): Array<String> = arrayOf("zh", "CN", "")
    override fun getSupportedVoices(): List<Voice> = emptyList()
    override fun getDefaultVoiceId(
        lang: String?,
        country: String?,
        variant: String?,
        currentVoiceId: String?,
    ): String = currentVoiceId ?: "fake"

    override fun isVoiceIdCorrect(voiceId: String?): Boolean = !voiceId.isNullOrBlank()
    override fun createDefaultConfig(): BaseProviderConfig = FakeConfig()
    override fun getDefaultApiUrl(): String = ""
    override fun getDefaultModelId(): String = "fake-model"
    override fun getVoiceOutputCapability(
        config: BaseProviderConfig,
        voiceId: String,
    ): VoiceOutputCapability = capability(voiceId)

    override fun getConfigLabel(configKey: String, context: Context): String? = null

    data class FakeConfig(override val voiceId: String = "fake") : BaseProviderConfig(voiceId)
}
