package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class TalkifyCheckDataActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val languages = arrayListOf(
            "zho-CHN", "eng-USA", "deu-DEU", "ita-ITA",
            "por-PRT", "spa-ESP", "jpn-JPN", "kor-KOR",
            "fra-FRA", "rus-RUS"
        )

        val returnData = Intent()
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, languages)
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf<String>())

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)
        finish()
    }
}