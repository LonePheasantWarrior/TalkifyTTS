package com.github.lonepheasantwarrior.talkify.service.engine

import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngineRegistry
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.impl.Qwen3TtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.impl.SeedTts2Engine

/**
 * TTS 引擎工厂
 *
 * 根据引擎 ID 创建对应的引擎实例
 * 采用工厂模式，解耦引擎实例化与使用方逻辑
 * 支持引擎热插拔，新增引擎只需在此注册
 *
 * 线程安全：使用双重检查锁定单例模式
 */
object TtsEngineFactory {

    @Volatile
    private var engines: Map<String, () -> TtsEngineApi>? = null

    private val lock = Any()

    /**
     * 根据引擎 ID 创建引擎实例
     *
     * @param engineId 引擎 ID
     * @return 引擎实例，未找到时返回 null
     */
    fun createEngine(engineId: String): TtsEngineApi? {
        val factory = getEngines()[engineId]
        if (factory == null) {
            TtsLogger.w("TtsEngineFactory: engine not found - $engineId")
            return null
        }
        return try {
            factory()
        } catch (e: Exception) {
            TtsLogger.e("TtsEngineFactory: failed to create engine - $engineId", e)
            null
        }
    }

    /**
     * 检查引擎是否已注册
     *
     * @param engineId 引擎 ID
     * @return 是否已注册
     */
    fun isRegistered(engineId: String): Boolean {
        return getEngines().containsKey(engineId)
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

    /**
     * 获取所有已注册的引擎 ID 列表
     *
     * @return 引擎 ID 列表
     */
    fun getRegisteredEngineIds(): List<String> {
        return getEngines().keys.toList()
    }

    /**
     * 获取引擎描述信息
     *
     * @param engineId 引擎 ID
     * @return 引擎描述，未找到返回 null
     */
    fun getEngineDescription(engineId: String): String? {
        if (!isRegistered(engineId)) {
            return null
        }
        val engine = createEngine(engineId)
        return engine?.getEngineName()
    }

    /**
     * 检查引擎是否已配置
     *
     * @param engineId 引擎 ID
     * @return 是否已配置
     */
    fun isEngineConfigured(engineId: String): Boolean {
        return isRegistered(engineId) && isValidEngineId(engineId)
    }

    /**
     * 获取所有注册的引擎信息
     *
     * @return 引擎信息列表
     */
    fun getAllEngineInfo(): List<Pair<String, String>> {
        return getEngines().map { (id, _) ->
            val engine = createEngine(id)
            id to (engine?.getEngineName() ?: id)
        }
    }

    /**
     * 注册新引擎（用于测试或动态注册）
     *
     * @param engineId 引擎 ID
     * @param factory 引擎工厂函数
     */
    fun registerEngine(engineId: String, factory: () -> TtsEngineApi) {
        synchronized(lock) {
            val currentEngines = getEngines().toMutableMap()
            currentEngines[engineId] = factory
            engines = currentEngines
            TtsLogger.i("TtsEngineFactory: registered engine - $engineId")
        }
    }

    /**
     * 注销引擎（用于测试）
     *
     * @param engineId 引擎 ID
     */
    fun unregisterEngine(engineId: String) {
        synchronized(lock) {
            val currentEngines = getEngines().toMutableMap()
            currentEngines.remove(engineId)
            engines = currentEngines
            TtsLogger.i("TtsEngineFactory: unregistered engine - $engineId")
        }
    }

    private fun getEngines(): Map<String, () -> TtsEngineApi> {
        return engines ?: synchronized(lock) {
            engines ?: initializeEngines().also { engines = it }
        }
    }

    private fun initializeEngines(): Map<String, () -> TtsEngineApi> {
        TtsLogger.d("TtsEngineFactory: initializing engines")
        return mapOf(
            Qwen3TtsEngine.ENGINE_ID to { Qwen3TtsEngine() },
            SeedTts2Engine.ENGINE_ID to { SeedTts2Engine() }
        ).also {
            TtsLogger.i("TtsEngineFactory: ${it.size} engines registered")
        }
    }
}
