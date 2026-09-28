package com.github.lonepheasantwarrior.talkify.service.provider

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 供应商网络代理设置（纯数据）
 *
 * 与具体供应商解耦：任何需要经代理访问云端 API 的供应商均可复用。
 *
 * @property isSocks true = SOCKS 代理，false = HTTP 代理
 * @property host 代理主机地址（IP 或域名）
 * @property port 代理端口（1-65535）
 */
data class ProxySetting(
    val isSocks: Boolean,
    val host: String,
    val port: Int
) {
    /** 转换为 OkHttp/JDK 网络栈使用的 [Proxy] 实例 */
    fun toJavaProxy(): Proxy = Proxy(
        if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP,
        InetSocketAddress(host, port)
    )
}

/**
 * 代理配置解析结果
 */
sealed interface ProxyParseResult {
    /** 未配置代理（主机为空），直连 */
    data object NotConfigured : ProxyParseResult

    /** 配置有效 */
    data class Valid(val setting: ProxySetting) : ProxyParseResult

    /** 配置无效，[reason] 为用户可读的错误消息 */
    data class Invalid(val reason: String) : ProxyParseResult
}

/**
 * 代理配置解析（纯函数，便于单元测试）
 *
 * 语义约定（以"代理协议"为总开关）：
 * - 协议为空或 "none"（无代理）→ 未配置代理，直连，主机/端口忽略
 * - 其他协议按 http 处理（"socks" 为 SOCKS）→ 主机必填，端口必须为
 *   1-65535 的数字，否则视为配置无效
 *
 * 协议取值与 GoogleConfig 的 PROTOCOL_NONE / PROTOCOL_HTTP / PROTOCOL_SOCKS
 * 常量对应（domain 层不反向依赖本模块，故此处以私有常量声明同名取值）
 */
internal object ProxySettings {

    fun parse(protocol: String, host: String, port: String): ProxyParseResult {
        val normalizedProtocol = protocol.trim().lowercase()
        if (normalizedProtocol.isBlank() || normalizedProtocol == PROTOCOL_NONE) {
            return ProxyParseResult.NotConfigured
        }

        if (host.isBlank()) {
            return ProxyParseResult.Invalid("代理主机不能为空：请填写代理服务器地址")
        }

        val portValue = port.trim().toIntOrNull()
        if (portValue == null || portValue !in MIN_PORT..MAX_PORT) {
            return ProxyParseResult.Invalid("代理端口无效：请填写 1-65535 之间的数字")
        }

        return ProxyParseResult.Valid(
            ProxySetting(
                isSocks = normalizedProtocol == PROTOCOL_SOCKS,
                host = host.trim(),
                port = portValue
            )
        )
    }

    private const val PROTOCOL_NONE = "none"
    private const val PROTOCOL_SOCKS = "socks"
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535
}
