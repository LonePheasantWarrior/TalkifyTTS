package com.github.lonepheasantwarrior.talkify.service

import android.media.AudioFormat
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppActionTracker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppActionTracker.PreviewAttempt
import com.github.lonepheasantwarrior.talkify.service.TtsPreviewPlayer.Companion.WAVE_POINTS
import com.github.lonepheasantwarrior.talkify.service.TtsPreviewPlayer.Companion.WAVE_POLL_INTERVAL_MS
import com.github.lonepheasantwarrior.talkify.service.TtsPreviewPlayer.Companion.WAVE_WINDOW_MS
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

    /**
     * stopPlayback 已摘除、等待异步收尾的场次数据（N15）
     *
     * stopPlayback 把播放器与 attempt 转入本字段后经协程收尾；release() 必须先
     * 同步冲刷本字段再取消 scope——否则"已 launch、未开始执行"的收尾协程随
     * cancel 永不执行，AudioTrack native 资源与场次遥测一并丢失
     */
    @Volatile
    private var pendingTeardown: PendingTeardown? = null

    private class PendingTeardown(val player: TalkifyAudioPlayer?, val attempt: PreviewAttempt?)

    /**
     * 场次代数：每次 speak() 递增。
     * 异步收尾协程只允许影响自己发起时的场次，防止快速 stop→speak 后
     * 旧收尾协程覆盖新场次的状态。
     */
    @Volatile
    private var sessionGeneration = 0L

    private var stateListener: ((Int, String?) -> Unit)? = null

    private var amplitudeListener: ((FloatArray) -> Unit)? = null

    // 波形包络：环形缓冲按绝对窗口序号寻址；写入=音频到达线程，读取=轮询协程
    private val waveLock = Any()
    private val waveRing = FloatArray(WAVE_RING_CAPACITY)

    // 波形窗口参数由音频到达线程写、轮询协程读，须保证可见性
    @Volatile
    private var waveWindowFrames = 0
    @Volatile
    private var waveBytesPerFrame = 0
    @Volatile
    private var waveAudioFormat = 0
    @Volatile
    private var waveCarry: ByteArray? = null

    /** 环形缓冲写入位号，仅在 [waveLock] 内读写 */
    private var waveMaxWindowIndex = -1L
    @Volatile
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

        // 新场次代数：此后旧场次的异步收尾协程不再影响共享状态
        sessionGeneration++
        isStopped.set(false)
        lastErrorMessage = null
        transition(STATE_IDLE)
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

        // 音频焦点：预览是本应用自己播放的音频，不请求焦点会与后台音乐/播客
        // 直接混音（P2-I14）。焦点被系统（如通话中）拒绝时放弃本次预览。
        if (!requestAudioFocus()) {
            TtsLogger.w("Audio focus denied, aborting preview")
            onError("无法获取音频焦点，请稍后重试")
            return
        }

        playbackAttempt = AppActionTracker.beginPreviewPlayback(
            providerId, config.modelId, config.voiceId, text.length
        )

        transition(STATE_PLAYING)

        // 会话监听器必须在调用线程同步创建（N5）：代数捕获与上方自增同线程
        // 天然有序。若延迟到 IO 协程体内创建，派发间隙的新 speak() 会使旧协程
        // 捕获到新代数，旧场次回调将穿过代数校验污染新场次
        val listener = createListener()
        val generation = sessionGeneration

        serviceScope.launch {
            try {
                provider.synthesize(text, params, config, listener)
            } catch (e: Exception) {
                TtsLogger.e("Synthesis failed: ${e.message}", e)
                if (generation == sessionGeneration) {
                    playbackAttempt?.markError(e.javaClass.simpleName)
                    onError("合成失败：${e.message}")
                }
            }
        }
    }

    private fun createListener(): TtsSynthesisListener {
        // 捕获创建时的场次代数：上一场次的滞后回调（取消前已在飞）一律丢弃，
        // 防止旧场次向新场次的播放器写入音频或触发状态收尾
        val generation = sessionGeneration
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
                if (generation != sessionGeneration || isStopped.get()) {
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
                if (generation != sessionGeneration) return
                TtsLogger.d("Synthesis completed")
                // 合成完成 ≠ 播放完成：AudioTrack 缓冲中还有尾段未播的音频，
                // 直接 stop/release 会把结尾截掉（表现为"戛然而止"），
                // 先等缓冲排空（用户主动停止时经 shouldStop 立即退出）。
                // 捕获本场 attempt 局部引用，避免异步排空期间串到下一场次
                val attempt = playbackAttempt
                serviceScope.launch {
                    if (generation != sessionGeneration) return@launch
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
                if (generation != sessionGeneration) return
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
            // 位号自增与写入须同锁：读取方（轮询协程）在 waveLock 内寻址
            synchronized(waveLock) {
                val idx = ++waveMaxWindowIndex
                waveRing[(idx % WAVE_RING_CAPACITY).toInt()] = prev
            }
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
        abandonAudioFocus()
        // 捕获本场播放器/供应商的局部引用：清理只作用于本场对象，
        // 快速 stop→speak 后新场次创建的播放器不会被旧协程误杀
        val player = audioPlayer
        val provider = currentProvider
        val generation = sessionGeneration
        // 同步清空共享引用：新场次据此创建全新播放器，旧实例经 pendingTeardown 收尾
        audioPlayer = null
        val teardown = PendingTeardown(player, attempt)
        pendingTeardown = teardown
        if (serviceScope.isActive) {
            serviceScope.launch(Dispatchers.IO) {
                runTeardown(teardown, generation, provider)
                if (pendingTeardown === teardown) {
                    pendingTeardown = null
                }
            }
        } else {
            // scope 已被 release() 取消：协程经 launch 必被永久跳过，直接同步收尾，
            // 防止 AudioTrack 泄漏（N15 最后防线；runTeardown 幂等，与 release 的
            // 冲刷双跑无副作用）
            runTeardown(teardown, generation, provider)
        }
    }

    /**
     * 场次收尾：释放播放器 + 停止供应商 + attempt 上报 + 状态落地
     *
     * 供 stopPlayback 的异步协程与 release() 的同步冲刷共用；全部操作
     * 幂等（player.stop/release 幂等、attempt.report 幂等、状态落地有代数守卫），
     * 双路径并发执行无副作用
     */
    private fun runTeardown(teardown: PendingTeardown, generation: Long, provider: TtsProviderApi?) {
        try {
            teardown.player?.stop()
            teardown.player?.release()
        } catch (e: Exception) {
            TtsLogger.e("Error stopping audio player: ${e.message}", e)
        }

        // provider 实例跨场次复用：新场次开始后不得 stop——那会误杀新场次
        // 的在飞合成（供应商入口已取消旧会话，无需此处代劳）（P2-I13①）
        if (generation == sessionGeneration) {
            try {
                provider?.stop()
            } catch (e: Exception) {
                TtsLogger.e("Error stopping provider: ${e.message}", e)
            }
        }

        // 未经显式 mark 的终态（provider onError / 播放器错误路径）按错误收尾
        teardown.attempt?.let {
            val errMsg = lastErrorMessage
            if (errMsg != null) {
                it.markError(TtsErrorCode.inferErrorCodeFromMessage(errMsg).toString())
            }
            it.report()
        }

        if (generation == sessionGeneration) {
            transition(if (lastErrorMessage != null) STATE_ERROR else STATE_IDLE)
        }
    }

    // ==================== 音频焦点（P2-I14） ====================

    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private fun audioManager(): android.media.AudioManager? {
        val context = com.github.lonepheasantwarrior.talkify.TalkifyAppHolder.getContext()
            ?: return null
        return context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    }

    /** 请求瞬时独占焦点；被系统拒绝（如通话中）返回 false */
    private fun requestAudioFocus(): Boolean {
        val manager = audioManager() ?: return true  // 上下文不可用时不阻断预览
        val request = audioFocusRequest ?: android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) {
                    stop()
                }
            }
            .build()
            .also { audioFocusRequest = it }
        return manager.requestAudioFocus(request) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        audioManager()?.abandonAudioFocusRequest(request)
    }

    private fun onError(message: String) {
        lastErrorMessage = message
        transition(STATE_ERROR)
    }

    private fun notifyStateChange() {
        stateListener?.invoke(currentState, lastErrorMessage)
    }

    /**
     * 状态迁移单点（N5 状态机收敛）：活动期所有状态写入经此并同步广播；
     * release() 终态（scope 已取消）为唯一例外，直接写 [currentState]
     */
    private fun transition(to: Int) {
        currentState = to
        notifyStateChange()
    }

    fun release() {
        TtsLogger.d("Releasing preview player")
        // release 不复用 stop()：stop 的播放器释放在异步协程中执行，随后的
        // serviceScope.cancel() 可能使清理协程永不执行——播放中的 AudioTrack
        // native 资源泄漏（P2-I13②）。此处全部同步收尾。
        isStopped.set(true)
        sessionGeneration++          // 作废在飞收尾协程的状态写入
        abandonAudioFocus()
        wavePollJob?.cancel()

        // N15：先同步冲刷 stopPlayback 遗留的待收尾数据，再取消 scope——
        // 在飞收尾协程一旦尚未开始执行便会被 cancel 永久跳过。player.stop/release
        // 与 attempt.report 均幂等，与并发的收尾协程双跑无副作用
        flushPendingTeardown()

        val player = audioPlayer
        audioPlayer = null
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {
            TtsLogger.e("Error releasing audio player: ${e.message}", e)
        }

        val provider = currentProvider
        currentProvider = null
        try {
            provider?.stop()
        } catch (e: Exception) {
            TtsLogger.e("Error stopping provider: ${e.message}", e)
        }
        try {
            provider?.release()
        } catch (e: Exception) {
            TtsLogger.e("Error releasing provider: ${e.message}", e)
        }

        // 遥测 attempt 释放前同步上报（release 丢弃 attempt 是已知的独立缺陷，
        // 此处顺手修复）
        playbackAttempt?.let { attempt ->
            val errMsg = lastErrorMessage
            if (errMsg != null) {
                attempt.markError(TtsErrorCode.inferErrorCodeFromMessage(errMsg).toString())
            }
            attempt.report()
        }
        playbackAttempt = null

        serviceScope.cancel()

        // N15 补强：冲刷点与 cancel 之间并发到达的 stopPlayback（如音频线程错误监听
        // 触发）遗留的待收尾件，其收尾协程已随 cancel 被跳过——cancel 后复冲兜底
        flushPendingTeardown()

        // 终态直写：scope 已取消，不再广播（transition 的唯一例外，见其 KDoc）
        currentState = STATE_IDLE
        lastErrorMessage = null
    }

    /** 同步冲刷并清空待收尾件（release 的 cancel 前后两次冲刷共用；全部操作幂等） */
    private fun flushPendingTeardown() {
        val pending = pendingTeardown
        pendingTeardown = null
        try {
            pending?.player?.stop()
            pending?.player?.release()
        } catch (e: Exception) {
            TtsLogger.e("Error releasing audio player: ${e.message}", e)
        }
        pending?.attempt?.let { attempt ->
            val errMsg = lastErrorMessage
            if (errMsg != null) {
                attempt.markError(TtsErrorCode.inferErrorCodeFromMessage(errMsg).toString())
            }
            attempt.report()
        }
    }

    fun getState(): Int = currentState
}
