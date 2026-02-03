package com.github.lonepheasantwarrior.talkify.service

import android.content.Context
import android.widget.Toast

/**
 * TTS 错误处理助手
 *
 * 提供用户友好的错误提示机制
 * 支持 Toast 显示和错误日志记录
 */
object TtsErrorHelper {

    private var lastErrorTime = 0L
    private const val ERROR_COOLDOWN_MS = 3000L

    /**
     * 显示用户友好的错误提示
     *
     * @param context 上下文
     * @param errorCode 错误码
     */
    fun showErrorToast(context: Context, errorCode: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastErrorTime < ERROR_COOLDOWN_MS) {
            return
        }
        lastErrorTime = currentTime

        val message = TtsErrorCode.getErrorMessage(errorCode)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        TtsLogger.e("TTS Error: $message (code: $errorCode)")
    }

    /**
     * 显示自定义错误提示
     *
     * @param context 上下文
     * @param message 错误消息
     */
    fun showCustomErrorToast(context: Context, message: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastErrorTime < ERROR_COOLDOWN_MS) {
            return
        }
        lastErrorTime = currentTime

        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        TtsLogger.e("TTS Custom Error: $message")
    }

    /**
     * 记录错误并返回友好的用户消息
     *
     * @param errorCode 错误码
     * @param throwable 异常（可选）
     * @return 用户友好的错误消息
     */
    fun handleError(errorCode: Int, throwable: Throwable? = null): String {
        val message = TtsErrorCode.getErrorMessage(errorCode)
        TtsLogger.e(message, throwable)
        return message
    }

    /**
     * 检查是否为配置错误
     */
    fun isConfigurationError(errorCode: Int): Boolean {
        return errorCode == TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED ||
                errorCode == TtsErrorCode.ERROR_CONFIG_NOT_FOUND
    }

    /**
     * 获取配置错误的建议操作
     */
    fun getConfigurationSuggestion(): String {
        return "请前往应用设置页面配置 API Key"
    }

    /**
     * 获取错误的建议操作
     */
    fun getSuggestion(errorCode: Int): String {
        return TtsErrorCode.getSuggestion(errorCode)
    }

    /**
     * 显示带有建议操作的错误提示
     *
     * @param context 上下文
     * @param errorCode 错误码
     */
    fun showErrorWithSuggestion(context: Context, errorCode: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastErrorTime < ERROR_COOLDOWN_MS) {
            return
        }
        lastErrorTime = currentTime

        val message = TtsErrorCode.getErrorMessage(errorCode)
        val suggestion = TtsErrorCode.getSuggestion(errorCode)
        val fullMessage = if (suggestion.isNotEmpty() && suggestion != "请稍后重试") {
            "$message\n$suggestion"
        } else {
            message
        }
        Toast.makeText(context, fullMessage, Toast.LENGTH_LONG).show()
        TtsLogger.e("TTS Error: $message (code: $errorCode), Suggestion: $suggestion")
    }
}
