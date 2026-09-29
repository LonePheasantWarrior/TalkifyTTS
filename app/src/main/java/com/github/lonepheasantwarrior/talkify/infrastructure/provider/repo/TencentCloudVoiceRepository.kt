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
            // 以整段 "数字+k" 精确匹配（如 8k/16k/24k/48k）；
            // 旧实现 contains("8k") 会把 "48k" 误判为 8000
            val rates = sampleRateStr.split("/")
                .mapNotNull { rateStr ->
                    Regex("(\\d+)k").find(rateStr.trim().lowercase())
                        ?.groupValues?.get(1)?.toIntOrNull()?.times(1000)
                }
            rates.maxOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
