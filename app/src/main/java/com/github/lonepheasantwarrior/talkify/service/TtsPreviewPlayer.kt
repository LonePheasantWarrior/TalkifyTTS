package com.github.lonepheasantwarrior.talkify.service

import android.media.AudioFormat
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppActionTracker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppActionTracker.PreviewAttempt
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.util.TalkifyAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

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

        /** 波形包络历史点数（50ms/点 ≈ 3.2s），与 VoiceWave 的 AGSL uniform 数组尺寸一致 */
        const val WAVE_POINTS = 64
        private const val WAVE_WINDOW_MS = 50L
        private const val WAVE_RING_CAPACITY = 256
        private const val WAVE_POLL_INTERVAL_MS = 33L
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

    /** 本次预览播放的遥测计时器（stopPlayback 时消费并清空，防止跨场次串报） */
    @Volatile
    private var playbackAttempt: PreviewAttempt? = null

    private var stateListener: ((Int, String?) -> Unit)? = null

    private var amplitudeListener: ((FloatArray) -> Unit)? = null

    // 波形包络：环形缓冲按绝对窗口序号寻址；写入=音频到达线程，读取=轮询协程
    private val waveLock = Any()
    private val waveRing = FloatArray(WAVE_RING_CAPACITY)
    private var waveWindowFrames = 0
    private var waveBytesPerFrame = 0
    private var waveAudioFormat = 0
    private var waveCarry: ByteArray? = null
    private var waveMaxWindowIndex = -1L
    private var lastWaveAmp = 0f
    private var wavePollJob: Job? = null

    fun setStateListener(listener: (Int, String?) -> Unit) {
        stateListener = listener
    }

    /** 订阅实时振幅历史（播放期间按 [WAVE_POLL_INTERVAL_MS] 推送，数组长度 [WAVE_POINTS]） */
    fun setAmplitudeListener(listener: ((FloatArray) -> Unit)?) {
        amplitudeListener = listener
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
        resetWaveState()

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

        playbackAttempt = AppActionTracker.beginPreviewPlayback(
            providerId, config.modelId, config.voiceId, text.length
        )

        currentState = STATE_PLAYING
        notifyStateChange()

        serviceScope.launch {
            try {
                provider.synthesize(text, params, config, createListener())
            } catch (e: Exception) {
                TtsLogger.e("Synthesis failed: ${e.message}", e)
                playbackAttempt?.markError(e.javaClass.simpleName)
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

                playbackAttempt?.markFirstAudio(sampleRate)

                try {
                    if (audioPlayer == null) {
                        waveAudioFormat = audioFormat
                        waveBytesPerFrame = bytesPerSample(audioFormat) * channelCount
                        waveWindowFrames =
                            (sampleRate.toLong() * WAVE_WINDOW_MS / 1000L).toInt().coerceAtLeast(1)
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
                    appendWaveEnvelope(audioData)
                    audioPlayer?.play(audioData)
                    ensureWavePoller()
                } catch (e: Exception) {
                    TtsLogger.e("Audio playback error: ${e.message}", e)
                }
            }

            override fun onSynthesisCompleted() {
                TtsLogger.d("Synthesis completed")
                // 合成完成 ≠ 播放完成：AudioTrack 缓冲中还有尾段未播的音频，
                // 直接 stop/release 会把结尾截掉（表现为"戛然而止"），
                // 先等缓冲排空（用户主动停止时经 shouldStop 立即退出）。
                // 捕获本场 attempt 局部引用，避免异步排空期间串到下一场次
                val attempt = playbackAttempt
                serviceScope.launch {
                    val player = audioPlayer
                    if (player != null) {
                        try {
                            player.waitForPlaybackComplete(
                                timeoutSeconds = 120,
                                shouldStop = { isStopped.get() }
                            )
                        } catch (e: Exception) {
                            TtsLogger.e("Wait playback drain error: ${e.message}", e)
                        }
                    }
                    // 用户已在此期间停止时 markStopped 先写终态，此处按首写生效语义为 no-op
                    attempt?.markSuccess()
                    stopPlayback()
                }
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
        playbackAttempt?.markStopped()
        audioPlayer?.stop()
        stopPlayback()
    }

    // --- 波形包络（真实 PCM 振幅） ---

    private fun resetWaveState() {
        synchronized(waveLock) { waveRing.fill(0f) }
        waveMaxWindowIndex = -1L
        waveCarry = null
        lastWaveAmp = 0f
    }

    private fun bytesPerSample(format: Int): Int = when (format) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    /** 把一段 PCM 细分为 [WAVE_WINDOW_MS] 窗口逐一计算 RMS，写入环形包络缓冲 */
    private fun appendWaveEnvelope(audioData: ByteArray) {
        val windowBytes = waveWindowFrames * waveBytesPerFrame
        if (windowBytes <= 0) return
        val carry = waveCarry
        val full = if (carry != null && carry.isNotEmpty()) carry + audioData else audioData
        val windows = full.size / windowBytes
        var prev = lastWaveAmp
        for (i in 0 until windows) {
            val amp = mapAmp(computeRmsWindow(full, i * windowBytes, windowBytes))
            // 快起缓落的时域平滑，抑制窗口间的毛刺
            prev = if (amp > prev) amp * 0.65f + prev * 0.35f else amp * 0.45f + prev * 0.55f
            val idx = ++waveMaxWindowIndex
            synchronized(waveLock) { waveRing[(idx % WAVE_RING_CAPACITY).toInt()] = prev }
        }
        lastWaveAmp = prev
        val remainder = full.size - windows * windowBytes
        waveCarry = if (remainder > 0) full.copyOfRange(windows * windowBytes, full.size) else null
    }

    private fun computeRmsWindow(data: ByteArray, offset: Int, length: Int): Float {
        return when (waveAudioFormat) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = ByteBuffer.wrap(data, offset, length)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var acc = 0.0
                for (i in 0 until fb.remaining()) {
                    val s = fb.get(i).toDouble()
                    acc += s * s
                }
                (sqrt(acc / fb.remaining().coerceAtLeast(1))).toFloat()
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                var acc = 0.0
                for (i in offset until offset + length) {
                    val s = (data[i].toInt() and 0xFF) - 128
                    acc += (s * s).toDouble()
                }
                (sqrt(acc / length.coerceAtLeast(1)) / 128.0).toFloat()
            }
            else -> {
                var acc = 0.0
                var count = 0
                var i = offset
                val end = offset + length
                while (i + 1 < end) {
                    val s = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8))
                        .toShort().toInt()
                    acc += (s * s).toDouble()
                    count++
                    i += 2
                }
                if (count == 0) 0f else (sqrt(acc / count) / 32768.0).toFloat()
            }
        }
    }

    /** 感知映射：语音 RMS 常见 0.01~0.25，增益后开方压缩，避免波形整体过矮 */
    private fun mapAmp(rms: Float): Float {
        if (rms <= 0f) return 0f
        return sqrt((rms * 6f).coerceAtMost(1f))
    }

    private fun ensureWavePoller() {
        if (wavePollJob?.isActive == true) return
        wavePollJob = serviceScope.launch {
            while (isActive && currentState == STATE_PLAYING) {
                publishWaveSnapshot()
                delay(WAVE_POLL_INTERVAL_MS)
            }
        }
    }

    /** 以播放头为右端点截取 [WAVE_POINTS] 个包络窗口，未写入/已耗尽的区间补零 */
    private fun publishWaveSnapshot() {
        val windowFrames = waveWindowFrames
        if (windowFrames <= 0) return
        val headWindow = audioPlayer?.currentPlaybackHeadFrames()?.let { it / windowFrames } ?: 0
        val out = FloatArray(WAVE_POINTS)
        synchronized(waveLock) {
            for (j in 0 until WAVE_POINTS) {
                val idx = headWindow.toLong() - (WAVE_POINTS - 1) + j
                if (idx in 0..waveMaxWindowIndex) {
                    out[j] = waveRing[(idx % WAVE_RING_CAPACITY).toInt()]
                }
            }
        }
        amplitudeListener?.invoke(out)
    }

    private fun stopPlayback() {
        // 同步消费本场 attempt：后续 speak() 会立即写入新场次，
        // 异步收尾协程只允许上报已捕获的局部引用
        val attempt = playbackAttempt
        playbackAttempt = null
        wavePollJob?.cancel()
        wavePollJob = null
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

            // 未经显式 mark 的终态（provider onError / 播放器错误路径）按错误收尾
            attempt?.let {
                val errMsg = lastErrorMessage
                if (errMsg != null) {
                    it.markError(TtsErrorCode.inferErrorCodeFromMessage(errMsg).toString())
                }
                it.report()
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
