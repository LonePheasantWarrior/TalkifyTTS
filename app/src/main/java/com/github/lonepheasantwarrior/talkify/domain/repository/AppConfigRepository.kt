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

    /** 是否启用合成音频增幅。默认关闭。 */
    fun isAudioGainEnabled(): Boolean

    /** 保存合成音频增幅开关。 */
    fun setAudioGainEnabled(enabled: Boolean)

    /** 获取音频增幅强度（dB）。 */
    fun getAudioGainDb(): Float

    /** 保存音频增幅强度（dB）。 */
    fun setAudioGainDb(gainDb: Float)
}
