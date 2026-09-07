package com.github.lonepheasantwarrior.talkify.infrastructure.xml

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.annotation.XmlRes
import org.xmlpull.v1.XmlPullParser

object VoiceXmlParser {

    fun parse(context: Context, @XmlRes xmlResId: Int): List<VoiceXmlEntry> {
        val parser = context.resources.getXml(xmlResId)
        return parse(parser)
    }

    fun parse(parser: XmlResourceParser): List<VoiceXmlEntry> {
        parser.use { p ->
            val entries = mutableListOf<VoiceXmlEntry>()
            var eventType = p.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && p.name == "voice") {
                    entries.add(
                        VoiceXmlEntry(
                            id = p.getAttributeValue(null, "id") ?: "",
                            displayName = p.getAttributeValue(null, "displayName") ?: "",
                            description = p.getAttributeValue(null, "description") ?: "",
                            language = p.getAttributeValue(null, "language") ?: "",
                            scenario = p.getAttributeValue(null, "scenario") ?: "",
                            sampleRate = p.getAttributeValue(null, "sampleRate") ?: "",
                            emotion = p.getAttributeValue(null, "emotion") ?: "",
                            model = p.getAttributeValue(null, "model") ?: "",
                            group = p.getAttributeValue(null, "group") ?: "",
                            referenceFileName = p.getAttributeValue(null, "referenceFileName") ?: "",
                            referenceText = p.getAttributeValue(null, "referenceText") ?: ""
                        )
                    )
                }
                eventType = p.next()
            }
            return entries
        }
    }

    fun parseVoiceIds(context: Context, @XmlRes xmlResId: Int): List<String> {
        return parse(context, xmlResId).map { it.id }
    }
}
