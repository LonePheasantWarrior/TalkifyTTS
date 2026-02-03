package com.github.lonepheasantwarrior.talkify

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.ui.screens.MainScreen
import com.github.lonepheasantwarrior.talkify.ui.theme.TalkifyTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TalkifyMain"
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TtsLogger.i(TAG) { "MainActivity.onCreate: 应用启动" }

        // 设置音量键控制媒体音量，确保语音预览时音量键可以调节播放音量
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        enableEdgeToEdge()
        setContent {
            TalkifyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}