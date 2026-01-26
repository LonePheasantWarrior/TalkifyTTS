package com.github.lonepheasantwarrior.talkify.domain.repository

/**
 * 应用配置仓储接口
 *
 * 定义应用级全局配置的存取方法
 * 与引擎配置分离，存储应用级别的全局状态
 */
interface AppConfigRepository {
    /**
     * 获取用户上次选择的引擎 ID
     *
     * 用于应用启动时恢复用户选择的引擎
     * @return 引擎 ID，未选择时返回 null
     */
    fun getSelectedEngineId(): String?

    /**
     * 保存用户选择的引擎 ID
     *
     * @param engineId 引擎 ID
     */
    fun saveSelectedEngineId(engineId: String)

    /**
     * 检查是否已选择过引擎
     *
     * @return 是否已选择过引擎
     */
    fun hasSelectedEngine(): Boolean

    /**
     * 检查用户是否选择跳过通知权限请求
     *
     * 用于应用启动时判断是否需要显示通知权限请求弹窗
     * @return 是否已选择跳过通知权限请求
     */
    fun hasSkippedNotificationPermission(): Boolean

    /**
     * 保存用户对通知权限请求的选择
     *
     * @param skipped true 表示用户选择"以后再说"，不再弹窗请求
     */
    fun setSkippedNotificationPermission(skipped: Boolean)
}
