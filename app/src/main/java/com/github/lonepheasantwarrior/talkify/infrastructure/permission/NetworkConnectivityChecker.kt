package com.github.lonepheasantwarrior.talkify.infrastructure.permission

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.ContextCompat
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 网络连接检测工具类
 *
 * 检测应用是否能够实际网络连接
 * 处理 Android 16 的"允许网络访问"系统设置
 */
object NetworkConnectivityChecker {

    private const val TAG = "TalkifyNetwork"
    private const val TEST_HOST = "www.aliyun.com"
    private const val TEST_PORT = 443
    private const val TIMEOUT_MS = 5000L

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
     * 检查网络连接是否可用
     *
     * 检测实际网络连接能力，包括：
     * - WiFi 连接
     * - 蜂窝数据连接
     * - Android 16 的"允许网络访问"设置
     *
     * @param context 上下文
     * @return 网络连接是否可用
     */
    fun isNetworkAvailable(context: Context): Boolean {
        TtsLogger.d(TAG) { "isNetworkAvailable: 开始检查网络连接..." }
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork
        TtsLogger.d(TAG) { "isNetworkAvailable: activeNetwork = $network" }

        if (network == null) {
            TtsLogger.w(TAG) { "isNetworkAvailable: 无活动网络" }
            return false
        }

        val capabilities = connectivityManager.getNetworkCapabilities(network)
        TtsLogger.d(TAG) { "isNetworkAvailable: capabilities = $capabilities" }

        if (capabilities == null) {
            TtsLogger.w(TAG) { "isNetworkAvailable: 无法获取网络能力" }
            return false
        }

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        TtsLogger.d(TAG) { "isNetworkAvailable: hasInternet=$hasInternet, isValidated=$isValidated" }

        val result = hasInternet && isValidated
        TtsLogger.d(TAG) { "isNetworkAvailable: result = $result" }
        return result
    }

    /**
     * 检查是否可以通过网络访问互联网
     *
     * 这是最严格的检查，确认实际能够访问外部网络
     * 考虑到 Android 16 的"允许网络访问"开关限制
     *
     * @param context 上下文
     * @return 是否可以访问互联网
     */
    suspend fun canAccessInternet(context: Context): Boolean {
        TtsLogger.d(TAG) { "canAccessInternet: 开始检查网络访问能力..." }

        val hasPermission = hasInternetPermission(context)
        TtsLogger.d(TAG) { "canAccessInternet: hasPermission = $hasPermission" }

        if (!hasPermission) {
            TtsLogger.w(TAG) { "canAccessInternet: 无联网权限" }
            return false
        }

        val isAvailable = isNetworkAvailable(context)
        TtsLogger.d(TAG) { "canAccessInternet: isAvailable = $isAvailable" }

        if (!isAvailable) {
            TtsLogger.w(TAG) { "canAccessInternet: 网络连接不可用" }
            return false
        }

        val canActuallyConnect = testActualInternetConnection()
        TtsLogger.d(TAG) { "canAccessInternet: canActuallyConnect = $canActuallyConnect" }

        val result = canActuallyConnect
        TtsLogger.d(TAG) { "canAccessInternet: 最终结果 = $result" }
        return result
    }

    /**
     * 实际测试网络连接
     *
     * 尝试建立到外部主机的 Socket 连接
     * 这是检测 Android 16 "允许网络访问"开关的最可靠方法
     *
     * @return 是否可以实际建立网络连接
     */
    private suspend fun testActualInternetConnection(): Boolean {
        return withTimeoutOrNull(TIMEOUT_MS) {
            try {
                TtsLogger.d(TAG) { "testActualInternetConnection: 尝试连接到 $TEST_HOST:$TEST_PORT..." }
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(TEST_HOST, TEST_PORT), TIMEOUT_MS.toInt())
                    TtsLogger.d(TAG) { "testActualInternetConnection: 连接成功" }
                    true
                } finally {
                    socket.close()
                }
            } catch (e: Exception) {
                TtsLogger.w(TAG) { "testActualInternetConnection: 连接失败: ${e.message}" }
                false
            }
        } ?: false
    }

    /**
     * 获取网络不可用的原因
     *
     * @param context 上下文
     * @return 不可用原因描述
     */
    fun getNetworkUnavailableReason(context: Context): NetworkUnavailableReason {
        TtsLogger.d(TAG) { "getNetworkUnavailableReason: 开始检查网络不可用原因..." }

        val hasPermission = hasInternetPermission(context)
        TtsLogger.d(TAG) { "getNetworkUnavailableReason: hasPermission = $hasPermission" }

        if (!hasPermission) {
            TtsLogger.w(TAG) { "getNetworkUnavailableReason: 原因 = NO_PERMISSION" }
            return NetworkUnavailableReason.NO_PERMISSION
        }

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork
        TtsLogger.d(TAG) { "getNetworkUnavailableReason: activeNetwork = $network" }

        if (network == null) {
            TtsLogger.w(TAG) { "getNetworkUnavailableReason: 原因 = NO_NETWORK" }
            return NetworkUnavailableReason.NO_NETWORK
        }

        val capabilities = connectivityManager.getNetworkCapabilities(network)
        TtsLogger.d(TAG) { "getNetworkUnavailableReason: capabilities = $capabilities" }

        if (capabilities == null) {
            TtsLogger.w(TAG) { "getNetworkUnavailableReason: 原因 = NO_NETWORK" }
            return NetworkUnavailableReason.NO_NETWORK
        }

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        TtsLogger.d(TAG) { "getNetworkUnavailableReason: hasInternet = $hasInternet" }

        if (!hasInternet) {
            TtsLogger.w(TAG) { "getNetworkUnavailableReason: 原因 = BLOCKED_BY_SYSTEM" }
            return NetworkUnavailableReason.BLOCKED_BY_SYSTEM
        }

        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        TtsLogger.d(TAG) { "getNetworkUnavailableReason: isValidated = $isValidated" }

        if (!isValidated) {
            TtsLogger.w(TAG) { "getNetworkUnavailableReason: 原因 = NO_INTERNET_ACCESS" }
            return NetworkUnavailableReason.NO_INTERNET_ACCESS
        }

        TtsLogger.d(TAG) { "getNetworkUnavailableReason: 原因 = NONE (网络可用)" }
        return NetworkUnavailableReason.NONE
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
