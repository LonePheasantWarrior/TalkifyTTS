package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * TTS 引擎注册表
 *
 * 作为所有可用引擎的单一数据源（Single Source of Truth）
 * 集中管理引擎信息，便于扩展和维护
 */
object TtsEngineRegistry {
    private val engines: Map<String, TtsEngine> by lazy {
        EngineIds.entries.associate { engineId ->
            engineId.value to engineId.toTtsEngine()
        }
    }

    /**
     * 获取所有可用的引擎列表
     */
    val availableEngines: List<TtsEngine>
        get() = engines.values.toList()

    /**
     * 根据 ID 获取引擎
     *
     * @param id 引擎 ID
     * @return 引擎实例，未找到时返回 null
     */
    fun getEngine(id: String): TtsEngine? {
        return engines[id]
    }

    /**
     * 获取引擎，未找到时返回默认引擎
     *
     * @param id 引擎 ID，可能为 null
     * @return 引擎实例
     */
    fun getEngineOrDefault(id: String?): TtsEngine {
        if (id == null) return defaultEngine
        return engines[id] ?: defaultEngine
    }

    /**
     * 获取默认引擎
     */
    val defaultEngine: TtsEngine
        get() = EngineIds.Qwen3Tts.toTtsEngine()

    /**
     * 检查引擎是否已注册
     *
     * @param id 引擎 ID
     * @return 是否已注册
     */
    fun contains(id: String): Boolean {
        return engines.containsKey(id)
    }
}
