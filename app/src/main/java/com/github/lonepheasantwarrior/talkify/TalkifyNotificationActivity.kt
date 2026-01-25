package com.github.lonepheasantwarrior.talkify

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 全屏通知弹窗 Activity
 *
 * 用于显示 heads-up 悬浮通知的全屏弹窗
 * 会在屏幕顶部显示重要系统通知
 */
class TalkifyNotificationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        finish()
    }
}
