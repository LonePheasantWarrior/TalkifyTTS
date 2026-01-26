package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

class TalkifyCheckDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        TtsLogger.d("CHECK_TTS_DATA: there is TalkifyCheckDataActivity")
        super.onCreate(savedInstanceState)

        //支持的语言
        val supportedLanguages = arrayListOf(
            "zho-CHN", "zho-HKG", "zho-TWN", "eng-USA", "deu-DEU", "ita-ITA",
            "por-PRT", "spa-ESP", "jpn-JPN", "kor-KOR",
            "fra-FRA", "rus-RUS"
        )

        val returnData = Intent()

        // 1. 声明支持的语言
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, supportedLanguages)

        // 2. 声明不支持的语言（空）
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())

        // 返回 PASS
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)

        finish()
    }
}