package com.github.lonepheasantwarrior.talkify.infrastructure.app.permission

import android.content.Context
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

/**
 * 网络连接检测工具类
 *
 * 提供统一的网络连接检测入口，整合权限检查和网络状态检查。
 *
 * 检测流程：
 * 1. 检查联网权限（由 PermissionChecker 负责）
 * 2. 检查网络可用性（由 ConnectivityMonitor 负责）
 * 3. 测试实际网络连接（检测 Android 16 "允许网络访问"开关）
 *
 * @see PermissionChecker
 * @see ConnectivityMonitor
 */
object NetworkConnectivityChecker {

    private const val TAG = "TalkifyNetwork"

    /**
     * 检查是否可以通过网络访问互联网
     *
     * 完整的网络访问能力检查，包括权限和实际连接能力。
     * 这是最严格的检查，考虑到 Android 16 的"允许网络访问"开关限制。
     *
     * @param context 上下文
     * @return 是否可以访问互联网
     */
    suspend fun canAccessInternet(context: Context): Boolean {
        TtsLogger.d(TAG) { "canAccessInternet: 开始检查..." }

        if (!PermissionChecker.hasInternetPermission(context)) {
            TtsLogger.w(TAG) { "canAccessInternet: 无联网权限" }
            return false
        }

        val result = ConnectivityMonitor.canAccessInternet(context)
        TtsLogger.d(TAG) { "canAccessInternet: 结果 = $result" }
        return result
    }

    /**
     * 获取网络不可用的原因
     *
     * @param context 上下文
     * @return 不可用原因描述
     */
    fun getNetworkUnavailableReason(context: Context): NetworkUnavailableReason {
        if (!PermissionChecker.hasInternetPermission(context)) {
            return NetworkUnavailableReason.NO_PERMISSION
        }

        val status = ConnectivityMonitor.getCurrentNetworkStatus(context)

        return when {
            !status.hasNetwork -> NetworkUnavailableReason.NO_NETWORK
            status.isBlockedBySystem -> NetworkUnavailableReason.BLOCKED_BY_SYSTEM
            !status.isValidated -> NetworkUnavailableReason.NO_INTERNET_ACCESS
            else -> NetworkUnavailableReason.NONE
        }
    }

    /**
     * 网络不可用原因枚举
     */
    enum class NetworkUnavailableReason {
        NONE,
        NO_PERMISSION,
        NO_NETWORK,
        BLOCKED_BY_SYSTEM,
        NO_INTERNET_ACCESS
    }
}
