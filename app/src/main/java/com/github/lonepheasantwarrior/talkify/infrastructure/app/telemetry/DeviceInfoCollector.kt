package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import java.util.Locale

/**
 * 匿名设备信息收集器
 *
 * 在不访问任何隐私权限的前提下，收集设备基础信息，用于匿名遥测统计。
 *
 * **信息收集原则**：
 * - 所有信息均来自 Android 公开 API，**无需任何运行时权限**
 * - 不收集任何可唯一标识设备或用户的信息（无 IMEI、无 MAC、无 Advertising ID）
 * - 不请求地理位置权限，仅通过 SIM 卡网络运营商或系统语言获取**国家级**区域信息
 *
 * **Umami 传输层已随请求自动附带的信息（此处不再重复）**：
 * - OS 名称及版本、App 版本号（User-Agent 派生）、语言 (language)、屏幕分辨率 (screen)
 *
 * @see UmamiClient
 */
object DeviceInfoCollector {

    private const val TAG = "TalkifyTelemetry"

    /**
     * 收集当前设备的匿名信息
     *
     * @param context 应用上下文
     * @return 设备信息键值对，所有 value 均为 String 或 Int（满足遥测属性类型约定）
     */
    fun collect(context: Context): Map<String, Any> {
        val info = linkedMapOf<String, Any>()

        collectDeviceIdentity(info)
        collectScreenDensity(context, info)
        collectCountry(context, info)
        collectMemoryInfo(context, info)
        collectNetworkType(context, info)

        TtsLogger.i(TAG) { "设备信息收集完成: ${info.keys.joinToString(", ")}" }
        return info
    }

    // ==================== 设备身份 ====================

    private fun collectDeviceIdentity(info: MutableMap<String, Any>) {
        info["device_model"] = Build.MODEL
        info["device_brand"] = Build.BRAND
        info["manufacturer"] = Build.MANUFACTURER
        info["device_codename"] = Build.DEVICE

        // SoC 平台信息（用于排查特定芯片组的兼容性问题）
        if (Build.HARDWARE.isNotBlank() && Build.HARDWARE != "unknown") {
            info["soc_platform"] = Build.HARDWARE
        }
    }

    // ==================== 屏幕信息 ====================

    private fun collectScreenDensity(context: Context, info: MutableMap<String, Any>) {
        val dpi = context.resources.displayMetrics.densityDpi
        val bucket = when (dpi) {
            in 0..Resources_LDPI_MAX -> ResourcesBucket_LDPI
            in (Resources_LDPI_MAX + 1)..Resources_MDPI_MAX -> ResourcesBucket_MDPI
            in (Resources_MDPI_MAX + 1)..Resources_HDPI_MAX -> ResourcesBucket_HDPI
            in (Resources_HDPI_MAX + 1)..Resources_XHDPI_MAX -> ResourcesBucket_XHDPI
            in (Resources_XHDPI_MAX + 1)..Resources_XXHDPI_MAX -> ResourcesBucket_XXHDPI
            else -> ResourcesBucket_XXXHDPI
        }
        info["screen_density"] = bucket
    }

    // ==================== 区域信息 ====================

    /**
     * 获取用户所在国家/地区代码（ISO 3166-1 alpha-2）
     *
     * 优先级：SIM 卡注册网络国家 > 系统语言设置的国家
     * 两种方式均无需任何权限，且仅精确到国家级，不涉及精细定位
     */
    private fun collectCountry(context: Context, info: MutableMap<String, Any>) {
        val country = getNetworkCountry(context) ?: getLocaleCountry()
        if (!country.isNullOrBlank()) {
            info["country"] = country.uppercase()
        }
    }

    /**
     * 从 SIM 卡注册网络的运营商信息中获取国家代码
     * 无需任何权限（API 30+）
     */
    private fun getNetworkCountry(context: Context): String? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkCountryIso
                ?.takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "获取网络运营商国家代码失败: ${e.message}" }
            null
        }
    }

    /**
     * 从系统 Locale 获取国家代码作为降级方案
     */
    private fun getLocaleCountry(): String? {
        val country = Locale.getDefault().country
        return country.takeUnless { it.isBlank() }
    }

    // ==================== 内存信息 ====================

    private fun collectMemoryInfo(context: Context, info: MutableMap<String, Any>) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)

            info["total_ram_mb"] = (memInfo.totalMem / ONE_MB).toInt()

            // ActivityManager.isLowRamDevice() - 出厂时即标定为低内存设备
            if (am.isLowRamDevice) {
                info["is_low_ram_device"] = 1
            }
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "获取内存信息失败: ${e.message}" }
        }
    }

    // ==================== 网络信息 ====================

    /**
     * 获取当前活跃网络类型
     * 仅获取网络连接类别（WiFi/蜂窝/以太网），不获取 IP 地址或网络名称
     * API 30+ 无需任何权限
     */
    private fun collectNetworkType(context: Context, info: MutableMap<String, Any>) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val capabilities = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return

            val networkType = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> null
            }
            if (networkType != null) {
                info["network_type"] = networkType
            }
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "获取网络类型失败: ${e.message}" }
        }
    }

    // ==================== 常量 ====================

    private const val ONE_MB = 1024L * 1024L

    // Screen density bucket thresholds
    private const val Resources_LDPI_MAX = 120
    private const val Resources_MDPI_MAX = 160
    private const val Resources_HDPI_MAX = 240
    private const val Resources_XHDPI_MAX = 320
    private const val Resources_XXHDPI_MAX = 480

    // Screen density bucket labels
    private const val ResourcesBucket_LDPI = "ldpi"
    private const val ResourcesBucket_MDPI = "mdpi"
    private const val ResourcesBucket_HDPI = "hdpi"
    private const val ResourcesBucket_XHDPI = "xhdpi"
    private const val ResourcesBucket_XXHDPI = "xxhdpi"
    private const val ResourcesBucket_XXXHDPI = "xxxhdpi"
}
