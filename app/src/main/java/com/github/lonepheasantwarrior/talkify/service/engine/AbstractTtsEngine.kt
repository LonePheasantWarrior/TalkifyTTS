package com.github.lonepheasantwarrior.talkify.service.engine

import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig

/**
 * TTS 引擎抽象基类
 *
 * 提供引擎共性功能的默认实现
 * 具体引擎只需继承并实现特定逻辑
 * 便于引擎扩展和代码复用
 */
abstract class AbstractTtsEngine : TtsEngineApi {

    protected var isReleased: Boolean = false
        private set

    override fun isConfigured(config: EngineConfig): Boolean {
        return config.apiKey.isNotBlank()
    }

    override fun stop() {
    }

    override fun release() {
        isReleased = true
    }

    protected fun checkNotReleased() {
        if (isReleased) {
            throw IllegalStateException("Engine has been released")
        }
    }
}
