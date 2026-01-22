package com.github.lonepheasantwarrior.talkify.service.engine.impl

import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.service.engine.AbstractTtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener

/**
 * 阿里云百炼 - 通义千问3语音合成引擎实现
 *
 * 继承 [AbstractTtsEngine]，实现 TTS 引擎接口
 * 目前为占位实现，语音合成功能待后续开发
 *
 * 引擎 ID：qwen3-tts
 * 服务提供商：阿里云百炼
 */
class Qwen3TtsEngine : AbstractTtsEngine() {

    companion object {
        const val ENGINE_ID = "qwen3-tts"
        const val ENGINE_NAME = "通义千问3语音合成"
    }

    override fun getEngineId(): String = ENGINE_ID

    override fun getEngineName(): String = ENGINE_NAME

    override fun synthesize(
        text: String,
        config: EngineConfig,
        listener: TtsSynthesisListener
    ) {
        checkNotReleased()
        listener.onError("通义千问3语音合成引擎功能待实现")
    }
}
