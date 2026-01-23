package com.github.lonepheasantwarrior.talkify.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineApi
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class TalkifyTtsDemoService(
    private val engineId: String
) {
    companion object {
        private const val TAG = "TalkifyTtsDemoService"

        const val STATE_IDLE = 0
        const val STATE_PLAYING = 1
        const val STATE_STOPPED = 2
        const val STATE_ERROR = 3
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentEngine: TtsEngineApi? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

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

    fun getLastErrorMessage(): String? = lastErrorMessage

    fun speak(
        text: String,
        config: EngineConfig,
        params: SynthesisParams = SynthesisParams(language = "Auto")
    ) {
        if (currentState == STATE_PLAYING) {
            stop()
        }

        isStopped.set(false)
        currentState = STATE_IDLE
        lastErrorMessage = null
        notifyStateChange()

        val engine = TtsEngineFactory.createEngine(engineId)
        if (engine == null) {
            TtsLogger.e("Failed to create engine: $engineId")
            onError("无法创建引擎：$engineId")
            return
        }

        currentEngine = engine
        currentState = STATE_PLAYING
        notifyStateChange()

        val audioConfig = engine.getAudioConfig()
        TtsLogger.d("Starting synthesis: textLength=${text.length}, audioConfig=${audioConfig.getFormatDescription()}")

        serviceScope.launch {
            try {
                val channelMask = if (audioConfig.channelCount == 1) {
                    AudioFormat.CHANNEL_OUT_MONO
                } else {
                    AudioFormat.CHANNEL_OUT_STEREO
                }
                
                val bufferSize = AudioTrack.getMinBufferSize(
                    audioConfig.sampleRate,
                    channelMask,
                    audioConfig.audioFormat
                )

                if (bufferSize <= 0) {
                    throw IllegalArgumentException("Invalid buffer size: $bufferSize. Audio parameters may be unsupported.")
                }

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(audioConfig.sampleRate)
                            .setChannelMask(channelMask)
                            .setEncoding(audioConfig.audioFormat)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()

                engine.synthesize(text, params, config, createListener())

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
                    audioTrack?.write(audioData, 0, audioData.size)
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
                lastErrorMessage = error
                stopPlayback()
            }
        }
    }

    fun stop() {
        TtsLogger.d("Stopping playback")
        isStopped.set(true)
        stopPlayback()
    }

    private fun stopPlayback() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            } catch (e: Exception) {
                TtsLogger.e("Error stopping audio track: ${e.message}", e)
            }

            currentEngine?.release()
            currentEngine = null

            if (currentState != STATE_STOPPED) {
                if (lastErrorMessage != null) {
                    currentState = STATE_ERROR
                } else {
                    currentState = STATE_IDLE
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
        TtsLogger.d("Releasing service")
        stop()
        serviceScope.cancel()
        currentState = STATE_IDLE
        lastErrorMessage = null
    }

    fun getState(): Int = currentState
}
