package com.github.lonepheasantwarrior.talkify.service

import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.util.TalkifyAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音预览播放器
 *
 * 组合 [TtsProviderApi] 供应商实例与 [TalkifyAudioPlayer]，
 * 为应用内"语音预览"提供合成与本地播放能力。不是 Android Service。
 */
class TtsPreviewPlayer(
    private val providerId: String
) {
    companion object {
        const val STATE_IDLE = 0
        const val STATE_PLAYING = 1
        const val STATE_STOPPED = 2
        const val STATE_ERROR = 3
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentProvider: TtsProviderApi? = null

    @Volatile
    private var audioPlayer: TalkifyAudioPlayer? = null

    @Volatile
    private var isStopped = AtomicBoolean(false)

    @Volatile
    private var currentState = STATE_IDLE

    @Volatile
    private var lastErrorMessage: String? = null

    private var stateListener: ((Int, String?) -> Unit)? = null

    fun setStateListener(listener: (Int, String?) -> Unit) {
        stateListener = listener
    }

    fun speak(
        text: String,
        config: BaseProviderConfig,
        params: SynthesisParams = SynthesisParams(language = "Auto")
    ) {
        if (currentState == STATE_PLAYING) {
            stop()
        }

        isStopped.set(false)
        currentState = STATE_IDLE
        lastErrorMessage = null
        notifyStateChange()

        var provider = currentProvider
        if (provider == null) {
            provider = TtsProviderFactory.createProvider(providerId)
            if (provider == null) {
                TtsLogger.e("Failed to create provider: $providerId")
                onError("无法创建供应商：$providerId")
                return
            }
            currentProvider = provider
        }

        currentState = STATE_PLAYING
        notifyStateChange()

        serviceScope.launch {
            try {
                provider.synthesize(text, params, config, createListener())
            } catch (e: Exception) {
                TtsLogger.e("Synthesis failed: ${e.message}", e)
                onError("合成失败：${e.message}")
            }
        }
    }

    private fun createListener(): TtsSynthesisListener {
        return object : TtsSynthesisListener {
            override fun onSynthesisStarted() {
                TtsLogger.d("Synthesis started")
            }

            override fun onAudioAvailable(
                audioData: ByteArray,
                sampleRate: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                if (isStopped.get()) {
                    TtsLogger.d("Audio skipped due to stop")
                    return
                }

                try {
                    if (audioPlayer == null) {
                        audioPlayer = TalkifyAudioPlayer(
                            sampleRate = sampleRate,
                            channelCount = channelCount,
                            audioFormat = audioFormat
                        )
                        audioPlayer?.setErrorListener { errorMessage ->
                            TtsLogger.e("Audio player error: $errorMessage")
                            lastErrorMessage = errorMessage
                            stopPlayback()
                        }
                        val created = audioPlayer?.createPlayer()
                        if (created != true) {
                            throw IllegalStateException("Failed to create audio player")
                        }
                    }
                    audioPlayer?.play(audioData)
                } catch (e: Exception) {
                    TtsLogger.e("Audio playback error: ${e.message}", e)
                }
            }

            override fun onSynthesisCompleted() {
                TtsLogger.d("Synthesis completed")
                stopPlayback()
            }

            override fun onError(error: String) {
                TtsLogger.e("Synthesis error: $error")
                val errorCode = TtsErrorCode.inferErrorCodeFromMessage(error)
                lastErrorMessage = TtsErrorCode.getErrorMessage(errorCode, error)
                stopPlayback()
            }
        }
    }

    fun stop() {
        TtsLogger.d("Stopping playback")
        isStopped.set(true)
        audioPlayer?.stop()
        stopPlayback()
    }

    private fun stopPlayback() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                audioPlayer?.stop()
                audioPlayer?.release()
                audioPlayer = null
            } catch (e: Exception) {
                TtsLogger.e("Error stopping audio player: ${e.message}", e)
            }

            try {
                currentProvider?.stop()
            } catch (e: Exception) {
                TtsLogger.e("Error stopping provider: ${e.message}", e)
            }

            if (currentState != STATE_STOPPED) {
                currentState = if (lastErrorMessage != null) {
                    STATE_ERROR
                } else {
                    STATE_IDLE
                }
                notifyStateChange()
            }
        }
    }

    private fun onError(message: String) {
        lastErrorMessage = message
        currentState = STATE_ERROR
        notifyStateChange()
    }

    private fun notifyStateChange() {
        stateListener?.invoke(currentState, lastErrorMessage)
    }

    fun release() {
        TtsLogger.d("Releasing preview player")
        stop()
        try {
            currentProvider?.release()
        } catch (e: Exception) {
            TtsLogger.e("Error releasing provider: ${e.message}", e)
        }
        currentProvider = null
        serviceScope.cancel()
        currentState = STATE_IDLE
        lastErrorMessage = null
    }

    fun getState(): Int = currentState
}
