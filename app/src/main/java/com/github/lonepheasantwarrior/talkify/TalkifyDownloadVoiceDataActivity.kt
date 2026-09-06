package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.os.Bundle
import android.view.Window

/**
 * Android TTS 引擎 `INSTALL_TTS_DATA` 契约 Activity。
 *
 * Talkify 的音色数据随供应商配置在应用内按需获取（本地模型在应用内下载），
 * 无需跳转独立安装界面，故此实现为空壳，仅满足系统契约。
 */
class TalkifyDownloadVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        this.requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
    }
}