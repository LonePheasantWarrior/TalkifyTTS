package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.os.Bundle

/**
 * Android TTS 引擎 `INSTALL_TTS_DATA` 契约 Activity。
 *
 * Talkify 的音色数据随供应商配置在应用内按需获取（本地模型在应用内下载），
 * 无需跳转独立安装界面，故此实现为空壳，仅满足系统契约。
 *
 * Manifest 配置 `Theme.NoDisplay` + 立即 [finish]：不产生可见窗口，
 * 避免系统 TTS 设置页点击"安装语音数据"后停留在空白全屏页面。
 */
class TalkifyDownloadVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
