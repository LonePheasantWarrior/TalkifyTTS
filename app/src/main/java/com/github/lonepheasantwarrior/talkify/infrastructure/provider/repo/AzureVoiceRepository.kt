package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.infrastructure.xml.VoiceXmlEntry

class AzureVoiceRepository(
    context: Context
) : BaseXmlVoiceRepository(
    context = context,
    xmlResId = R.xml.microsoft_tts_voices,
    expectedProviderId = ProviderIds.Azure.providerId
) {
    override fun VoiceXmlEntry.toVoiceInfo(): VoiceInfo =
        VoiceInfo(voiceId = id, displayName = displayName)
}
