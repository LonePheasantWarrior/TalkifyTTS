package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 更新检查结果密封类
 *
 * 使用密封类明确区分成功和失败状态
 * 便于 UI 层进行类型匹配并给出相应的用户提示
 */
sealed class UpdateCheckResult {

    /**
     * 发现新版本
     *
     * @param updateInfo 最新版本信息
     */
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateCheckResult()

    /**
     * 当前已是最新版本
     */
    data object NoUpdateAvailable : UpdateCheckResult()

    /**
     * 网络超时（国内网络无法访问 GitHub）
     *
     * 此时不应该给用户显示错误提示，静默放弃即可
     */
    data object NetworkTimeout : UpdateCheckResult()

    /**
     * 网络连接失败
     *
     * @param message 错误描述
     */
    data class NetworkError(val message: String) : UpdateCheckResult()

    /**
     * 服务端错误
     *
     * @param httpCode HTTP 状态码
     */
    data class ServerError(val httpCode: Int) : UpdateCheckResult()

    /**
     * 解析错误
     *
     * @param message 错误描述
     */
    data class ParseError(val message: String) : UpdateCheckResult()
}
