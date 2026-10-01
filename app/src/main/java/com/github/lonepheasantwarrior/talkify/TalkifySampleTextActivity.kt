package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class TalkifySampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 获取系统请求的语言信息
        val language = intent.getStringExtra("language")

        // 2. 根据语言准备示例文本（语种集合与服务层声明的支持语言对齐，N11）
        val sampleText = when (language) {
            "zho" -> "这是 Talkify 供应商的合成示例。"
            "eng" -> "This is a Talkify provider synthesis example."
            "jpn" -> "これは Talkify プロバイダーの合成サンプルです。"
            "kor" -> "이것은 Talkify 공급자의 합성 샘플입니다."
            "fra" -> "Voici un exemple de synthèse du fournisseur Talkify."
            "deu" -> "Dies ist ein Sprachsynthesebeispiel des Anbieters Talkify."
            "spa" -> "Este es un ejemplo de síntesis del proveedor Talkify."
            "ita" -> "Questo è un esempio di sintesi del fornitore Talkify."
            "por" -> "Este é um exemplo de síntese do provedor Talkify."
            "rus" -> "Это пример синтеза речи от провайдера Talkify."
            else -> "Welcome to use Talkify Text to Speech provider."
        }

        val resultIntent = Intent()
        resultIntent.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sampleText)

        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
