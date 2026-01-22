package com.github.lonepheasantwarrior.talkify.domain.repository

import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine

interface EngineConfigRepository {
    fun getConfig(engine: TtsEngine): EngineConfig
    fun saveConfig(engine: TtsEngine, config: EngineConfig)
    fun hasConfig(engine: TtsEngine): Boolean
}
