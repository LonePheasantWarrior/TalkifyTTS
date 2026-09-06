package com.github.lonepheasantwarrior.talkify

import android.content.Context

/**
 * 全局 Context 持有者
 *
 * 供供应商、本地模型管理等非 Android 组件层获取应用 Context，
 * 避免它们对 Activity/Service 的直接依赖。
 */
object TalkifyAppHolder {
    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    fun getContext(): Context? = appContext
}
