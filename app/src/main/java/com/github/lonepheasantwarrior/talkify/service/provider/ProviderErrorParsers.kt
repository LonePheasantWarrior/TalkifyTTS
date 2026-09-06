package com.github.lonepheasantwarrior.talkify.service.provider

import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import org.json.JSONObject

/**
 * 各供应商 API 错误响应到用户可读中文消息的映射。
 *
 * 纯函数实现，便于单元测试。
 */
internal object VolcengineErrorParser {

    /**
     * 解析火山引擎错误响应体
     *
     * 优先取根节点/headers 节点的 message（Doubao 2.0 与 Legacy 两种结构），
     * 其次按错误码映射为友好提示。
     */
    fun parse(errorBody: String): String {
        return try {
            val json = JSONObject(errorBody)
            // 优先尝试直接从根节点获取 message (Doubao 2.0 structure)
            var message = json.optString("message", "")

            // 如果根节点没有，尝试从 header 获取 (Legacy structure)
            val header = json.optJSONObject("header")
            val code = header?.optInt("code", 0) ?: json.optInt("code", 0)

            if (message.isBlank()) {
                message = header?.optString("message", "") ?: ""
            }

            if (message.isNotBlank()) {
                return message
            }

            when (code) {
                45000030 -> "资源未授权：请在火山引擎控制台开通对应服务服务 (code: $code)"
                45000001 -> "认证失败：请检查 App ID 和 Access Key 是否正确 (code: $code)"
                45000002 -> "参数错误：$message (code: $code)"
                45000003 -> "请求过于频繁，请稍后重试 (code: $code)"
                45000004 -> "服务暂时不可用，请稍后重试 (code: $code)"
                45000005 -> "余额不足：请充值后再试 (code: $code)"
                else -> "语音合成失败：$message (code: $code)"
            }
        } catch (_: Exception) {
            TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED)
        }
    }
}

internal object MiniMaxErrorParser {

    /**
     * 解析 MiniMax API 错误码为中文错误消息
     *
     * 对应 MiniMax WebSocket API 的 base_resp.status_code 错误码表
     */
    fun parse(statusCode: Int, statusMsg: String): String {
        return when (statusCode) {
            1000 -> "未知错误: $statusMsg"
            1001 -> "请求超时，请稍后重试"
            1002 -> "触发限流，请稍后重试"
            1004 -> "鉴权失败，请检查 API Key"
            1039 -> "触发 TPM 限流，请稍后重试"
            1042 -> "非法字符超过 10%，请检查文本内容"
            2013 -> "输入参数错误: $statusMsg"
            2201 -> "超时断开连接"
            2202 -> "非法事件: $statusMsg"
            2203 -> "空文本，已跳过"
            2204 -> "超出字符限制，已跳过"
            2205 -> "请求超限"
            else -> "语音合成失败: $statusMsg (code: $statusCode)"
        }
    }
}

internal object TencentErrorParser {

    private val ERROR_CODE_MAP = mapOf(
        -400 to "客户端参数不能为空",
        -401 to "认证信息不能为空",
        -402 to "请求参数不能为空",
        -403 to "监听器不能为空",
        -404 to "应用ID不能为空",
        -405 to "密钥ID不能为空",
        -406 to "密钥Key不能为空",
        -407 to "启动合成器失败",
        -408 to "发送文本失败",
        -409 to "连接服务器失败",
        -410 to "状态错误",
        3022 to "资源包配额已用尽，请检查您的资源包"
    )

    /** 将腾讯云 SDK 错误码映射为用户可读的中文错误消息 */
    fun friendlyErrorMessage(code: Int?, originalMessage: String?): String {
        val codeValue = code ?: return "语音合成失败: ${originalMessage ?: "未知错误"}"

        val mappedMessage = ERROR_CODE_MAP[codeValue]
        return if (mappedMessage != null) {
            "语音合成失败: $mappedMessage (错误码: $codeValue)"
        } else {
            val message = originalMessage ?: "未知错误"
            "语音合成失败: $message (错误码: $codeValue)"
        }
    }
}
