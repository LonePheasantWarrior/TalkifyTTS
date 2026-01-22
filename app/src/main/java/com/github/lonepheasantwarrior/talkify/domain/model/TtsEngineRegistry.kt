package com.github.lonepheasantwarrior.talkify.domain.model

object TtsEngineRegistry {
    private val engines: Map<String, TtsEngine> by lazy {
        EngineIds.entries.associate { engineId ->
            engineId.value to engineId.toTtsEngine()
        }
    }

    val availableEngines: List<TtsEngine>
        get() = engines.values.toList()

    fun getEngine(id: String): TtsEngine? {
        return engines[id]
    }

    fun getEngineOrDefault(id: String?): TtsEngine {
        if (id == null) return defaultEngine
        return engines[id] ?: defaultEngine
    }

    val defaultEngine: TtsEngine
        get() = EngineIds.Qwen3Tts.toTtsEngine()

    fun contains(id: String): Boolean {
        return engines.containsKey(id)
    }
}
