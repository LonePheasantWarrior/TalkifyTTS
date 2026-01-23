package com.github.lonepheasantwarrior.talkify.infrastructure.app.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
            Manifest.permission.INTERNET
        ) == PackageManager.PERMISSION_GRANTED
        TtsLogger.d(TAG) { "hasInternetPermission: $hasPermission" }
        return hasPermission
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
            missingPermissions.add(Manifest.permission.INTERNET)
            TtsLogger.w(TAG) { "getMissingPermissions: 缺失权限 - INTERNET" }
        }

        TtsLogger.d(TAG) { "getMissingPermissions: 共缺失 ${missingPermissions.size} 个权限" }
        return missingPermissions
    }
}
