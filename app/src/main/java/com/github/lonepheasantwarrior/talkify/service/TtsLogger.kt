package com.github.lonepheasantwarrior.talkify.service

import android.util.Log

/**
 * TTS 服务日志工具
 *
 * 提供统一的日志打印功能，支持日志级别控制
 * 在 Release 构建时可选择性禁用日志
 */
object TtsLogger {

    private const val TAG = "TalkifyTTS"

    @Volatile
    private var isDebugEnabled = true

    fun setDebugEnabled(enabled: Boolean) {
        isDebugEnabled = enabled
    }

    fun d(message: String, tag: String = TAG) {
        if (isDebugEnabled) {
            Log.d(tag, message)
        }
    }

    fun i(message: String, tag: String = TAG) {
        Log.i(tag, message)
    }

    fun w(message: String, tag: String = TAG) {
        Log.w(tag, message)
    }

    fun e(message: String, throwable: Throwable? = null, tag: String = TAG) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    fun v(message: String, tag: String = TAG) {
        if (isDebugEnabled) {
            Log.v(tag, message)
        }
    }

    fun d(tag: String = TAG, message: () -> String) {
        if (isDebugEnabled) {
            Log.d(tag, message())
        }
    }

    fun i(tag: String = TAG, message: () -> String) {
        Log.i(tag, message())
    }

    fun w(tag: String = TAG, message: () -> String) {
        Log.w(tag, message())
    }

    fun e(tag: String = TAG, message: () -> String) {
        Log.e(tag, message())
    }
}
