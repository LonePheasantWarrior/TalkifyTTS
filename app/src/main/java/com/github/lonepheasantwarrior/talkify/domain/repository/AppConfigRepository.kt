package com.github.lonepheasantwarrior.talkify.domain.repository

/**
 * 应用配置仓储接口
 *
 * 定义应用级全局配置的存取方法
 * 与供应商配置分离，存储应用级别的全局状态
 */
interface AppConfigRepository {
    /**
     * 获取用户上次选择的供应商 ID
     *
     * 用于应用启动时恢复用户选择的供应商
     * @return 供应商 ID，未选择时返回 null
     */
    fun getSelectedProviderId(): String?

    /**
     * 保存用户选择的供应商 ID
     *
     * @param providerId 供应商 ID
     */
    fun saveSelectedProviderId(providerId: String)

    /**
     * 检查是否已选择过供应商
     *
     * @return 是否已选择过供应商
     */
    fun hasSelectedProvider(): Boolean

    /**
     * 检查是否已经请求过通知权限
     */
    fun hasRequestedNotificationPermission(): Boolean

    /**
     * 设置是否已经请求过通知权限
     */
    fun setHasRequestedNotificationPermission(requested: Boolean)

    /**
     * 检查是否已经打开过关于页面
     */
    fun hasOpenedAboutPage(): Boolean

    /**
     * 设置关于页面已打开
     */
    fun setAboutPageOpened(opened: Boolean)

    /**
     * 匿名遥测是否已启用
     *
     * 默认关闭；仅在用户于"关于"页显式开启后上报 app_opened 等匿名事件（P1-12）
     */
    fun isTelemetryEnabled(): Boolean

    /**
     * 设置匿名遥测开关
     */
    fun setTelemetryEnabled(enabled: Boolean)

    /**
     * 用户是否已跳过电池优化引导（N12）
     *
     * 跳过后冷启动不再重弹电池优化弹窗；设置/关于页的手动入口不受影响
     */
    fun isBatteryOptimizationPromptDismissed(): Boolean

    /**
     * 记录用户跳过电池优化引导
     */
    fun setBatteryOptimizationPromptDismissed(dismissed: Boolean)
}
