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

    const val ERROR_GENERIC = 1100
    const val ERROR_NETWORK_TIMEOUT = 1101
    const val ERROR_API_RATE_LIMITED = 1102
    const val ERROR_API_SERVER_ERROR = 1103
    const val ERROR_API_AUTH_FAILED = 1104
    const val ERROR_NOT_IMPLEMENTED = 1105

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
            ERROR_GENERIC -> "操作失败，请稍后重试"
            ERROR_NETWORK_TIMEOUT -> "网络连接超时，请检查网络设置"
            ERROR_API_RATE_LIMITED -> "请求过于频繁，请稍后重试"
            ERROR_API_SERVER_ERROR -> "服务暂时不可用，请稍后重试"
            ERROR_API_AUTH_FAILED -> "认证失败，请检查 API Key 配置"
            ERROR_NOT_IMPLEMENTED -> "该引擎功能尚未实现"
            else -> "发生错误（错误码：$errorCode）"
        }
    }

    fun toAndroidError(errorCode: Int): Int {
        return when (errorCode) {
            ERROR_INVALID_REQUEST -> android.speech.tts.TextToSpeech.ERROR_INVALID_REQUEST
            ERROR_NETWORK_UNAVAILABLE -> android.speech.tts.TextToSpeech.ERROR_NETWORK
            ERROR_NETWORK_TIMEOUT -> android.speech.tts.TextToSpeech.ERROR_NETWORK
            ERROR_SYNTHESIS_FAILED -> android.speech.tts.TextToSpeech.ERROR_SYNTHESIS
            ERROR_API_SERVER_ERROR -> android.speech.tts.TextToSpeech.ERROR_SERVICE
            else -> android.speech.tts.TextToSpeech.ERROR_INVALID_REQUEST
        }
    }

    fun getSuggestion(errorCode: Int): String {
        return when (errorCode) {
            ERROR_ENGINE_NOT_CONFIGURED, ERROR_API_AUTH_FAILED -> "请前往应用设置页面配置正确的 API Key"
            ERROR_NETWORK_UNAVAILABLE, ERROR_NETWORK_TIMEOUT -> "请检查网络连接后重试"
            ERROR_API_RATE_LIMITED -> "请等待片刻后重试"
            ERROR_API_SERVER_ERROR -> "请稍后重试，或联系服务提供商"
            ERROR_ENGINE_NOT_FOUND, ERROR_CONFIG_NOT_FOUND -> "请重启应用或重新选择引擎"
            ERROR_NOT_IMPLEMENTED -> "该引擎正在开发中，请稍后再试"
            else -> "请稍后重试"
        }
    }
}
