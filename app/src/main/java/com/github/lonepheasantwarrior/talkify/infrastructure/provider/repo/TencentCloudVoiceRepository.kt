package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.infrastructure.xml.VoiceXmlEntry

class TencentCloudVoiceRepository(
    context: Context
) : BaseXmlVoiceRepository(
    context = context,
    xmlResId = R.xml.tencent_tts_voices,
    expectedProviderId = ProviderIds.TencentCloud.providerId
) {

    override fun VoiceXmlEntry.toVoiceInfo(): VoiceInfo =
        VoiceInfo(
            voiceId = id,
            displayName = displayName,
            group = group,
            sampleRate = parseSampleRate(sampleRate)
        )

    private fun parseSampleRate(sampleRateStr: String): Int? {
        if (sampleRateStr.isBlank()) return null
        return try {
            val rates = sampleRateStr.split("/")
                .map { it.trim().lowercase() }
                .mapNotNull { rateStr ->
                    when {
                        rateStr.contains("8k") -> 8000
                        rateStr.contains("16k") -> 16000
                        rateStr.contains("24k") -> 24000
                        else -> null
                    }
                }
            rates.maxOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
