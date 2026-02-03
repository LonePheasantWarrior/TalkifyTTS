package com.github.lonepheasantwarrior.talkify.infrastructure.app.power

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电源优化辅助工具类
 *
 * 用于检查和请求忽略电池优化权限，确保应用在后台能稳定运行
 */
object PowerOptimizationHelper {

    /**
     * 检查是否已忽略电池优化（即拥有无限制后台运行权限）
     *
     * @param context 上下文
     * @return true 表示已忽略优化（无限制），false 表示受限
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    /**
     * 创建请求忽略电池优化的 Intent
     *
     * 用于跳转到系统设置页面让用户授权
     *
     * @param context 上下文
     * @return 跳转设置的 Intent
     */
    @SuppressLint("BatteryLife") // 我们是 TTS 工具应用，需要在后台长时间运行，符合白名单例外场景
    fun createRequestIgnoreBatteryOptimizationsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
