package com.github.lonepheasantwarrior.talkify.service

/**
 * TTS 服务错误码定义
 *
 * 定义 TTS 服务可能返回的错误类型
 * 与 Android TTS 错误码保持兼容
 */
object TtsErrorCode {

    const val SUCCESS = 0

    const val ERROR_NO_ENGINE = 1001
    const val ERROR_ENGINE_NOT_FOUND = 1002
    const val ERROR_ENGINE_NOT_CONFIGURED = 1003
    const val ERROR_SYNTHESIS_FAILED = 1004
    const val ERROR_NETWORK_UNAVAILABLE = 1005
    const val ERROR_INVALID_REQUEST = 1006
    const val ERROR_ENGINE_INIT_FAILED = 1007
    const val ERROR_CONFIG_NOT_FOUND = 1008
    const val ERROR_UNKNOWN = 1099

    fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            ERROR_NO_ENGINE -> "未找到可用的 TTS 引擎"
            ERROR_ENGINE_NOT_FOUND -> "引擎不存在"
            ERROR_ENGINE_NOT_CONFIGURED -> "请先配置 API Key"
            ERROR_SYNTHESIS_FAILED -> "语音合成失败"
            ERROR_NETWORK_UNAVAILABLE -> "网络不可用，请检查网络连接"
            ERROR_INVALID_REQUEST -> "无效的合成请求"
            ERROR_ENGINE_INIT_FAILED -> "引擎初始化失败"
            ERROR_CONFIG_NOT_FOUND -> "未找到引擎配置"
            ERROR_UNKNOWN -> "发生未知错误"
            else -> "错误码: $errorCode"
        }
    }

    fun toAndroidError(errorCode: Int): Int {
        return when (errorCode) {
            ERROR_INVALID_REQUEST -> android.speech.tts.TextToSpeech.ERROR_INVALID_REQUEST
            ERROR_NETWORK_UNAVAILABLE -> android.speech.tts.TextToSpeech.ERROR_NETWORK
            ERROR_SYNTHESIS_FAILED -> android.speech.tts.TextToSpeech.ERROR_SYNTHESIS
            else -> android.speech.tts.TextToSpeech.ERROR_INVALID_REQUEST
        }
    }
}
