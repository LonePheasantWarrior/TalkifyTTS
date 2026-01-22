package com.github.lonepheasantwarrior.talkify.domain.repository

import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine

/**
 * 引擎配置仓储接口
 *
 * 定义引擎配置存取的标准方法
 * 采用接口设计，解耦配置存储与业务逻辑
 * 支持多引擎配置隔离存储
 */
interface EngineConfigRepository {
    /**
     * 获取指定引擎的配置
     *
     * @param engine TTS 引擎
     * @return 引擎配置
     */
    fun getConfig(engine: TtsEngine): EngineConfig

    /**
     * 保存指定引擎的配置
     *
     * @param engine TTS 引擎
     * @param config 引擎配置
     */
    fun saveConfig(engine: TtsEngine, config: EngineConfig)

    /**
     * 检查指定引擎是否有已保存的配置
     *
     * @param engine TTS 引擎
     * @return 是否有已保存的配置
     */
    fun hasConfig(engine: TtsEngine): Boolean

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
}
