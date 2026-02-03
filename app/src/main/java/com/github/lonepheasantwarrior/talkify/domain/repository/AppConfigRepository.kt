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
     * 检查是否已经请求过通知权限
     */
    fun hasRequestedNotificationPermission(): Boolean

    /**
     * 设置是否已经请求过通知权限
     */
    fun setHasRequestedNotificationPermission(requested: Boolean)
}
