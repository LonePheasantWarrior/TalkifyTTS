package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

class TalkifySampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        TtsLogger.d("GET_SAMPLE_TEXT: there is TalkifySampleTextActivity")
        super.onCreate(savedInstanceState)

        // 1. 获取系统请求的语言信息
        val language = intent.getStringExtra("language")

        // 2. 根据语言准备示例文本
        val sampleText = when (language) {
            "zho" -> "这是 Talkify 引擎的合成示例。"
            "eng" -> "This is a Talkify Engine synthesis example."
            else -> "Welcome to use Talkify Text to Speech engine."
        }

        val resultIntent = Intent()
        resultIntent.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sampleText)

        setResult(RESULT_OK, resultIntent)
        finish()
    }
}