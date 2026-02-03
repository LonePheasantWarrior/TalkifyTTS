package com.github.lonepheasantwarrior.talkify.infrastructure.app.permission

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 网络连接状态监控器
 *
 * 提供网络连接检测功能，包括：
 * - 网络可用性检测
 * - Android 16 系统级网络访问开关检测
 * - 实际网络连接能力测试
 *
 * 设计原则：
 * - 单一职责，只负责网络连接检测
 * - 不包含权限检查逻辑（由 PermissionChecker 负责）
 * - 提供 Flow 接口支持响应式监听
 */
object ConnectivityMonitor {

    private const val TAG = "TalkifyNetwork"

    private const val DEFAULT_TEST_HOST = "www.aliyun.com"
    private const val DEFAULT_TEST_PORT = 443
    private const val DEFAULT_TIMEOUT_MS = 1000L

    data class NetworkStatus(
        val hasNetwork: Boolean,
        val hasInternetCapability: Boolean,
        val isValidated: Boolean,
        val isBlockedBySystem: Boolean,
        val transportTypes: List<String>
    ) {
        companion object {
            fun unavailable() = NetworkStatus(
                hasNetwork = false,
                hasInternetCapability = false,
                isValidated = false,
                isBlockedBySystem = false,
                transportTypes = emptyList()
            )
        }
    }

    /**
     * 获取当前网络状态
     *
     * @param context 上下文
     * @return 网络状态
     */
    fun getCurrentNetworkStatus(context: Context): NetworkStatus {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
            ?: return NetworkStatus.unavailable()

        val network = connectivityManager.activeNetwork
        if (network == null) {
            return NetworkStatus.unavailable()
        }

        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null) {
            return NetworkStatus.unavailable()
        }

        val hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isBlockedBySystem = !hasInternetCapability

        val transportTypes = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transportTypes.add("WIFI")
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transportTypes.add("CELLULAR")
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transportTypes.add("ETHERNET")
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            transportTypes.add("VPN")
        }

        return NetworkStatus(
            hasNetwork = true,
            hasInternetCapability = hasInternetCapability,
            isValidated = isValidated,
            isBlockedBySystem = isBlockedBySystem,
            transportTypes = transportTypes
        )
    }

    /**
     * 检查是否实际可以访问互联网
     *
     * 这是最严格的检查，确认实际能够访问外部网络
     * 考虑到 Android 16 的"允许网络访问"开关限制
     *
     * @param context 上下文
     * @return 是否可以访问互联网
     */
    suspend fun canAccessInternet(context: Context): Boolean {
        val status = getCurrentNetworkStatus(context)

        if (!status.hasNetwork) {
            TtsLogger.w(TAG) { "canAccessInternet: 无网络" }
            return false
        }

        if (status.isBlockedBySystem) {
            TtsLogger.w(TAG) { "canAccessInternet: 被系统阻止" }
            return false
        }

        return testActualConnection()
    }

    /**
     * 测试实际网络连接
     *
     * 尝试建立到外部主机的 TCP 连接
     * 这是检测 Android 16 "允许网络访问"开关的最可靠方法
     *
     * @return 是否可以实际建立网络连接
     */
    suspend fun testActualConnection(): Boolean {
        return testConnection(
            host = DEFAULT_TEST_HOST,
            port = DEFAULT_TEST_PORT,
            timeoutMs = DEFAULT_TIMEOUT_MS
        )
    }

    /**
     * 测试网络连接（可配置参数）
     *
     * @param host 目标主机
     * @param port 目标端口
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否可以建立连接
     */
    suspend fun testConnection(
        host: String = DEFAULT_TEST_HOST,
        port: Int = DEFAULT_TEST_PORT,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        TtsLogger.d(TAG) { "testConnection: 尝试连接到 $host:$port，超时 ${timeoutMs}ms..." }

        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            TtsLogger.d(TAG) { "testConnection: 连接成功" }
            true
        } catch (e: ConnectException) {
            TtsLogger.w(TAG) { "testConnection: 连接被拒绝 - ${e.message}" }
            false
        } catch (e: SocketTimeoutException) {
            TtsLogger.w(TAG) { "testConnection: 连接超时 - ${e.message}" }
            false
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "testConnection: 连接失败 - ${e.message}" }
            false
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * 监听网络连接状态变化
     *
     * @param context 上下文
     * @return 网络状态 Flow
     */
    fun observeNetworkStatus(context: Context): Flow<NetworkStatus> = callbackFlow {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager

        if (connectivityManager == null) {
            trySend(NetworkStatus.unavailable())
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentNetworkStatus(context))
            }

            override fun onLost(network: Network) {
                trySend(NetworkStatus.unavailable())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(getCurrentNetworkStatus(context))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        trySend(getCurrentNetworkStatus(context))

        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
        }
    }

    /**
     * 网络诊断信息
     *
     * @param context 上下文
     * @return 诊断信息字符串
     */
    fun getDiagnosticsInfo(context: Context): String {
        val status = getCurrentNetworkStatus(context)
        val sb = StringBuilder()
        sb.appendLine("=== 网络诊断信息 ===")
        sb.appendLine("hasNetwork: ${status.hasNetwork}")
        sb.appendLine("hasInternetCapability: ${status.hasInternetCapability}")
        sb.appendLine("isValidated: ${status.isValidated}")
        sb.appendLine("isBlockedBySystem: ${status.isBlockedBySystem}")
        sb.appendLine("transportTypes: ${status.transportTypes.joinToString()}")
        return sb.toString()
    }
}
