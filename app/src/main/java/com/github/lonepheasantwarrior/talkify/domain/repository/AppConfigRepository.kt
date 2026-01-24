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
     * 获取兼容模式开关状态
     *
     * 用于适配不遵守谷歌 TTS 调用规范的阅读工具
     * @return 是否开启兼容模式，默认返回 true（开启）
     */
    fun isCompatibilityModeEnabled(): Boolean

    /**
     * 保存兼容模式开关状态
     *
     * @param enabled 是否开启兼容模式
     */
    fun setCompatibilityModeEnabled(enabled: Boolean)
}
