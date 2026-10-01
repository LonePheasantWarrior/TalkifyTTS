package com.github.lonepheasantwarrior.talkify.service

import org.json.JSONObject

/**
 * TTS 服务错误码定义
 *
 * 定义 TTS 服务可能返回的错误类型
 * 与 Android TTS 错误码保持兼容
 */
object TtsErrorCode {

    const val SUCCESS = 0

    const val ERROR_NO_PROVIDER = 1001
    const val ERROR_PROVIDER_NOT_FOUND = 1002
    const val ERROR_PROVIDER_NOT_CONFIGURED = 1003
    const val ERROR_SYNTHESIS_FAILED = 1004
    const val ERROR_NETWORK_UNAVAILABLE = 1005
    const val ERROR_INVALID_REQUEST = 1006
    const val ERROR_PROVIDER_INIT_FAILED = 1007
    const val ERROR_CONFIG_NOT_FOUND = 1008
    const val ERROR_UNKNOWN = 1099

    const val ERROR_GENERIC = 1100
    const val ERROR_NETWORK_TIMEOUT = 1101
    const val ERROR_API_RATE_LIMITED = 1102
    const val ERROR_API_SERVER_ERROR = 1103
    const val ERROR_API_AUTH_FAILED = 1104
    const val ERROR_NOT_IMPLEMENTED = 1105

    fun getErrorMessage(errorCode: Int, detailMessage: String? = null): String {
        val baseMessage = when (errorCode) {
            ERROR_NO_PROVIDER -> "未找到可用的 TTS 供应商"
            ERROR_PROVIDER_NOT_FOUND -> "供应商不存在"
            ERROR_PROVIDER_NOT_CONFIGURED -> "请先配置 API Key"
            ERROR_SYNTHESIS_FAILED -> "语音合成失败"
            ERROR_NETWORK_UNAVAILABLE -> "网络不可用，请检查网络连接"
            ERROR_INVALID_REQUEST -> "无效的合成请求"
            ERROR_PROVIDER_INIT_FAILED -> "供应商初始化失败"
            ERROR_CONFIG_NOT_FOUND -> "未找到供应商配置"
            ERROR_UNKNOWN -> "发生未知错误"
            ERROR_GENERIC -> "操作失败，请稍后重试"
            ERROR_NETWORK_TIMEOUT -> "网络连接超时，请检查网络设置"
            ERROR_API_RATE_LIMITED -> "请求过于频繁，请稍后重试"
            ERROR_API_SERVER_ERROR -> "服务暂时不可用，请稍后重试"
            ERROR_API_AUTH_FAILED -> "认证失败，请检查 API Key 配置"
            ERROR_NOT_IMPLEMENTED -> "该供应商功能尚未实现"
            else -> "发生错误（错误码：$errorCode）"
        }
        if (detailMessage.isNullOrBlank()) {
            return baseMessage
        }

        var finalDetailMessage = detailMessage
        // 尝试解析 JSON 格式的错误信息
        if (detailMessage.trim().startsWith("{") && detailMessage.trim().endsWith("}")) {
            try {
                val json = JSONObject(detailMessage)
                val msg = json.optString("message")
                if (msg.isNotBlank()) {
                    finalDetailMessage = msg
                }
            } catch (e: Exception) {
                // 解析失败或不是标准 JSON，保留原样
            }
        }

        // 避免错误信息重复：如果 detailMessage 已经包含了 baseMessage，则不再重复拼接
        if (finalDetailMessage != null && finalDetailMessage.contains(baseMessage)) {
            return finalDetailMessage
        }

        return when (errorCode) {
            ERROR_SYNTHESIS_FAILED -> "语音合成失败: $finalDetailMessage"
            ERROR_UNKNOWN -> "发生未知错误: $finalDetailMessage"
            else -> "$baseMessage ($finalDetailMessage)"
        }
    }

    fun toAndroidError(errorCode: Int): Int {
        return when (errorCode) {
            // 防御：SUCCESS 落入 else 会被误映射为 ERROR_INVALID_REQUEST（P2-B12）
            SUCCESS -> SUCCESS
            ERROR_INVALID_REQUEST -> android.speech.tts.TextToSpeech.ERROR_INVALID_REQUEST
            ERROR_NETWORK_UNAVAILABLE -> android.speech.tts.TextToSpeech.ERROR_NETWORK
            ERROR_NETWORK_TIMEOUT -> android.speech.tts.TextToSpeech.ERROR_NETWORK
            ERROR_SYNTHESIS_FAILED -> android.speech.tts.TextToSpeech.ERROR_SYNTHESIS
            ERROR_API_SERVER_ERROR -> android.speech.tts.TextToSpeech.ERROR_SERVICE
            // 限流是暂时性故障：按可重试的网络错误上报，此前会误导客户端
            // 判定为自身请求参数问题（P2-B12）
            ERROR_API_RATE_LIMITED -> android.speech.tts.TextToSpeech.ERROR_NETWORK
            // 认证/配置类错误与客户端请求参数无关：按服务端错误上报（P2-B12）
            ERROR_API_AUTH_FAILED, ERROR_PROVIDER_NOT_CONFIGURED, ERROR_CONFIG_NOT_FOUND ->
                android.speech.tts.TextToSpeech.ERROR_SERVICE
            // N10：供应商侧故障（无供应商/未找到/初始化失败/未知/通用失败）此前
            // 一刀切落 ERROR_INVALID_REQUEST，"无供应商"会被 TTS 客户端误判为
            // 自身请求参数问题——统一按服务端错误上报
            ERROR_NO_PROVIDER, ERROR_PROVIDER_NOT_FOUND, ERROR_PROVIDER_INIT_FAILED,
            ERROR_UNKNOWN, ERROR_GENERIC, ERROR_NOT_IMPLEMENTED ->
                android.speech.tts.TextToSpeech.ERROR_SERVICE
            // 兜底仅覆盖未来新增的未知错误码：无更精确语义时按通用错误上报，
            // 不再臆断为客户端请求问题
            else -> android.speech.tts.TextToSpeech.ERROR
        }
    }

    fun getSuggestion(errorCode: Int): String {
        return when (errorCode) {
            ERROR_PROVIDER_NOT_CONFIGURED, ERROR_API_AUTH_FAILED -> "请前往应用设置页面配置正确的 API Key"
            ERROR_NETWORK_UNAVAILABLE, ERROR_NETWORK_TIMEOUT -> "请检查网络连接后重试"
            ERROR_API_RATE_LIMITED -> "请等待片刻后重试"
            ERROR_API_SERVER_ERROR -> "请稍后重试，或联系供应商"
            ERROR_PROVIDER_NOT_FOUND, ERROR_CONFIG_NOT_FOUND -> "请重启应用或重新选择供应商"
            ERROR_NOT_IMPLEMENTED -> "该供应商正在开发中，请稍后再试"
            else -> "请稍后重试"
        }
    }

    /**
     * 根据错误消息推断错误码
     *
     * 通过解析错误消息中的关键词来判断具体的错误类型
     * 支持认证失败、超时、网络错误、限流、服务器错误等
     *
     * @param errorMessage 错误消息文本
     * @return 对应的 TtsErrorCode 错误码
     */
    fun inferErrorCodeFromMessage(errorMessage: String): Int {
        return when {
            errorMessage.contains("API Key", ignoreCase = true) ||
                    errorMessage.contains("认证", ignoreCase = true) ||
                    errorMessage.contains("auth", ignoreCase = true) -> {
                ERROR_API_AUTH_FAILED
            }

            errorMessage.contains("超时", ignoreCase = true) ||
                    errorMessage.contains("timeout", ignoreCase = true) -> {
                ERROR_NETWORK_TIMEOUT
            }

            errorMessage.contains("网络", ignoreCase = true) ||
                    errorMessage.contains("连接", ignoreCase = true) ||
                    errorMessage.contains("network", ignoreCase = true) ||
                    errorMessage.contains("connect", ignoreCase = true) -> {
                ERROR_NETWORK_UNAVAILABLE
            }

            errorMessage.contains("频率", ignoreCase = true) ||
                    errorMessage.contains("rate limit", ignoreCase = true) ||
                    errorMessage.contains("429", ignoreCase = true) -> {
                ERROR_API_RATE_LIMITED
            }

            errorMessage.contains("服务器", ignoreCase = true) ||
                    errorMessage.contains("server", ignoreCase = true) ||
                    errorMessage.contains("500", ignoreCase = true) ||
                    errorMessage.contains("502", ignoreCase = true) ||
                    errorMessage.contains("503", ignoreCase = true) -> {
                ERROR_API_SERVER_ERROR
            }

            // 注意：此处不再检查 "API Key"——首个分支已覆盖该关键词并返回
            // ERROR_API_AUTH_FAILED，重复条件在本分支不可达（P3-3）
            errorMessage.contains("配置", ignoreCase = true) -> {
                ERROR_PROVIDER_NOT_CONFIGURED
            }

            else -> ERROR_SYNTHESIS_FAILED
        }
    }
}
