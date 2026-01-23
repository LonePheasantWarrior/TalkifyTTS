package com.github.lonepheasantwarrior.talkify.infrastructure.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

/**
 * 权限检查工具类
 *
 * 提供运行时权限检查功能
 * 遵循 Android 权限模型最佳实践
 */
object PermissionChecker {

    private const val TAG = "TalkifyPermission"

    /**
     * 检查是否具有联网权限
     *
     * @param context 上下文
     * @return 是否具有联网权限
     */
    fun hasInternetPermission(context: Context): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.INTERNET
        ) == PackageManager.PERMISSION_GRANTED
        TtsLogger.d(TAG) { "hasInternetPermission: $hasPermission" }
        return hasPermission
    }

    /**
     * 检查是否具有网络状态访问权限
     *
     * @param context 上下文
     * @return 是否具有网络状态访问权限
     */
    fun hasNetworkStatePermission(context: Context): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED
        TtsLogger.d(TAG) { "hasNetworkStatePermission: $hasPermission" }
        return hasPermission
    }

    /**
     * 检查是否具有 WiFi 状态访问权限
     *
     * @param context 上下文
     * @return 是否具有 WiFi 状态访问权限
     */
    fun hasWifiStatePermission(context: Context): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED
        TtsLogger.d(TAG) { "hasWifiStatePermission: $hasPermission" }
        return hasPermission
    }

    /**
     * 检查所有必需的网络相关权限
     *
     * @param context 上下文
     * @return 是否具有所有必需的网络权限
     */
    fun hasAllNetworkPermissions(context: Context): Boolean {
        val result = hasInternetPermission(context)
        TtsLogger.d(TAG) { "hasAllNetworkPermissions: $result" }
        return result
    }

    /**
     * 获取缺失的权限列表
     *
     * @param context 上下文
     * @return 缺失的权限列表
     */
    fun getMissingPermissions(context: Context): List<String> {
        TtsLogger.d(TAG) { "getMissingPermissions: 开始检查缺失权限..." }
        val missingPermissions = mutableListOf<String>()

        if (!hasInternetPermission(context)) {
            missingPermissions.add(android.Manifest.permission.INTERNET)
            TtsLogger.w(TAG) { "getMissingPermissions: 缺失权限 - INTERNET" }
        }

        TtsLogger.d(TAG) { "getMissingPermissions: 共缺失 ${missingPermissions.size} 个权限" }
        return missingPermissions
    }
}
