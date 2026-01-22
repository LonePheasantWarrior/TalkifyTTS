package com.github.lonepheasantwarrior.talkify.service.engine

import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

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

    protected open val tag: String
        get() = javaClass.simpleName

    override fun isConfigured(config: EngineConfig): Boolean {
        val result = config.apiKey.isNotBlank()
        TtsLogger.d("$tag: isConfigured = $result")
        return result
    }

    override fun stop() {
        TtsLogger.d("$tag: stop called")
    }

    override fun release() {
        TtsLogger.i("$tag: release called")
        isReleased = true
    }

    protected fun checkNotReleased() {
        if (isReleased) {
            val message = "Engine has been released"
            TtsLogger.e("$tag: $message")
            throw IllegalStateException(message)
        }
    }

    protected fun logDebug(message: String) {
        TtsLogger.d("$tag: $message")
    }

    protected fun logInfo(message: String) {
        TtsLogger.i("$tag: $message")
    }

    protected fun logWarning(message: String) {
        TtsLogger.w("$tag: $message")
    }

    protected fun logError(message: String, throwable: Throwable? = null) {
        TtsLogger.e("$tag: $message", throwable)
    }
}
