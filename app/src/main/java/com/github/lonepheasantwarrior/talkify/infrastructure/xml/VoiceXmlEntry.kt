package com.github.lonepheasantwarrior.talkify.infrastructure.xml

data class VoiceXmlEntry(
    val id: String,
    val displayName: String,
    val description: String = "",
    val language: String = "",
    val scenario: String = "",
    val sampleRate: String = "",
    val emotion: String = "",
    val model: String = "",
    val group: String = "",
    val referenceFileName: String = "",
    val referenceText: String = ""
)
