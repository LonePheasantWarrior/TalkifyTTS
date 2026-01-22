package com.github.lonepheasantwarrior.talkify.domain.model

sealed class EngineIds {
    data object Qwen3Tts : EngineIds() {
        override val value: String = "qwen3-tts"
        override val displayName: String = "通义千问3语音合成"
        override val provider: String = "阿里云百炼"
    }

    abstract val value: String
    abstract val displayName: String
    abstract val provider: String

    companion object {
        val entries: List<EngineIds> by lazy {
            listOf(Qwen3Tts)
        }
    }
}

fun EngineIds.toTtsEngine(): TtsEngine {
    return TtsEngine(
        id = this.value,
        name = this.displayName,
        provider = this.provider
    )
}
