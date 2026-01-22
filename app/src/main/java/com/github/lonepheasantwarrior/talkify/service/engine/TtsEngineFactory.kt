package com.github.lonepheasantwarrior.talkify.service.engine

import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngineRegistry
import com.github.lonepheasantwarrior.talkify.service.engine.impl.Qwen3TtsEngine

/**
 * TTS 引擎工厂
 *
 * 根据引擎 ID 创建对应的引擎实例
 * 采用工厂模式，解耦引擎实例化与使用方逻辑
 * 支持引擎热插拔，新增引擎只需在此注册
 */
object TtsEngineFactory {

    private val engines: Map<String, () -> TtsEngineApi> by lazy {
        mapOf(
            Qwen3TtsEngine.ENGINE_ID to { Qwen3TtsEngine() }
        )
    }

    /**
     * 根据引擎 ID 创建引擎实例
     *
     * @param engineId 引擎 ID
     * @return 引擎实例，未找到时返回 null
     */
    fun createEngine(engineId: String): TtsEngineApi? {
        val factory = engines[engineId] ?: return null
        return factory()
    }

    /**
     * 根据引擎 ID 检查引擎是否已注册
     *
     * @param engineId 引擎 ID
     * @return 是否已注册
     */
    fun isRegistered(engineId: String): Boolean {
        return engines.containsKey(engineId)
    }

    /**
     * 获取所有已注册的引擎 ID 列表
     *
     * @return 引擎 ID 列表
     */
    fun getRegisteredEngineIds(): List<String> {
        return engines.keys.toList()
    }

    /**
     * 检查引擎 ID 是否有效（存在于注册表）
     *
     * @param engineId 引擎 ID
     * @return 是否有效
     */
    fun isValidEngineId(engineId: String): Boolean {
        return TtsEngineRegistry.contains(engineId)
    }
}
