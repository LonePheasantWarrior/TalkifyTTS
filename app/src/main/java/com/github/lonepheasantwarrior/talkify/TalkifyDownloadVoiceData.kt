package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.os.Bundle
import android.view.Window

class TalkifyDownloadVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        this.requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
    }
}