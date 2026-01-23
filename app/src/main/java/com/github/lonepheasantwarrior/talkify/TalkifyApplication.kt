package com.github.lonepheasantwarrior.talkify

import android.app.Application
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

class TalkifyApplication : Application() {

    companion object {
        private const val TAG = "TalkifyApplication"
    }

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i(TAG) { "TalkifyApplication onCreate" }
        TalkifyExceptionHandler.initialize()
    }
}
