package com.github.lonepheasantwarrior.talkify.util

import android.media.AudioFormat
import android.media.AudioTrack
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TalkifyAudioPlayer internal constructor(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val audioFormat: Int,
    private val outputFactory: AudioOutputFactory
) {
    constructor(
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channelCount: Int = DEFAULT_CHANNEL_COUNT,
        audioFormat: Int = DEFAULT_AUDIO_FORMAT
    ) : this(sampleRate, channelCount, audioFormat, AndroidAudioOutputFactory)

    companion object {
        private const val TAG = "TalkifyAudioPlayer"

        const val DEFAULT_SAMPLE_RATE = 24000
        const val DEFAULT_CHANNEL_COUNT = 1
        const val DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 目标播放缓冲时长：流式合成靠它吸收网络抖动，避免缓冲耗尽 underrun */
        private const val PLAYBACK_BUFFER_MS = 600

        /** 起播前预缓冲时长：攒够一段再起播，避免首包过短导致开播即 underrun */
        private const val STARTUP_PREBUFFER_MS = 300

        private const val PROGRESS_CHECK_INTERVAL_MS = 50L

        /** 播放排空轮询的最小/最大休眠间隔（对齐 AOSP BlockingAudioTrack） */
        private const val MIN_POLL_SLEEP_MS = 20L
        private const val MAX_POLL_SLEEP_MS = 2500L

        /** 播放头持续无进展的看门狗上限（对齐 AOSP BlockingAudioTrack） */
        private const val MAX_NO_PROGRESS_MS = 2500L

        /** 短音频 stop() 冲刷后，在估算时长之外多等的余量，防止尾音被提前释放截断 */
        private const val SHORT_UTTERANCE_TAIL_MARGIN_MS = 100L

        /** 释放前等待在飞写入退出的上限（stop 已打断写入，正常为 0） */
        private const val RELEASE_WRITE_WAIT_MS = 500L
    }

    // 以下状态跨线程访问：@Volatile/原子量保证可见性；集合与预缓冲状态由 bufferLock 保护
    @Volatile
    private var audioOutput: AudioOutput? = null

    private val isPlaying = AtomicBoolean(false)

    private val isPlaybackStarted = AtomicBoolean(false)

    /** 会话代际：stop/release/错误终态后自增，旧写入协程据此放弃后续提交 */
    private val sessionGeneration = AtomicLong(0)

    /** 实际成功提交到 AudioTrack 的字节数（排空目标以它为准，而非入队字节数） */
    private val writtenAudioBytes = AtomicInteger(0)

    private val writeFailed = AtomicBoolean(false)

    /** 在飞底层写入计数：release 前等待它归零，避免释放仍有写入线程持有的 track */
    private val activeWrites = AtomicInteger(0)

    private val playerScope = CoroutineScope(Dispatchers.IO + Job())

    /** 单帧字节数（采样字节数 × 声道数），AudioTrack 以帧为单位消费 */
    private val bytesPerFrame = channelCount * bytesPerSample(audioFormat)

    /** 起播前需攒够的字节数；短音频由 [flushPendingAudio] 在合成完成时冲刷 */
    private val startupPrebufferBytes = (
        sampleRate.toLong() * bytesPerFrame * STARTUP_PREBUFFER_MS / 1000L
        ).toInt().coerceAtLeast(bytesPerFrame)

    /** 目标缓冲字节数：600ms 音频，24kHz/16bit/单声道约 28.8KB */
    private val targetBufferBytes = (
        sampleRate.toLong() * bytesPerFrame * PLAYBACK_BUFFER_MS / 1000L
        ).toInt()

    /**
     * 队列/状态锁：只保护队列与状态迁移，绝不包住阻塞写入。
     * stop() 因此不会被写入阻塞，可立即调用底层 stop() 打断写入。
     */
    private val bufferLock = Any()

    private val pendingAudio = ArrayList<ByteArray>()

    private var pendingAudioBytes = 0

    private var isPrebuffering = false

    /** play()/stop()/release 的短临界区锁：不与阻塞写入共用 */
    private val transportLock = Any()

    @Volatile
    private var playbackProgressJob: Job? = null

    // 上报协程遍历期间调用方可能增删监听器，使用写时复制容器避免并发修改异常
    private val progressListeners = CopyOnWriteArrayList<(Float, Long) -> Unit>()

    private var errorListener: ((String) -> Unit)? = null

    private var playbackCompleteListener: (() -> Unit)? = null

    fun createPlayer(): Boolean {
        releaseTrack()

        val output = try {
            outputFactory.create(
                sampleRate = sampleRate,
                channelCount = channelCount,
                audioFormat = audioFormat,
                desiredBufferSizeBytes = targetBufferBytes,
                bytesPerFrame = bytesPerFrame
            )
        } catch (e: Exception) {
            TtsLogger.e("Failed to create AudioTrack: ${e.message}", e, TAG)
            null
        }

        if (output == null) {
            val errorMsg = "Failed to create AudioTrack"
            TtsLogger.e(errorMsg, null, TAG)
            notifyError(errorMsg)
            return false
        }

        audioOutput = output
        writeFailed.set(false)
        writtenAudioBytes.set(0)
        TtsLogger.d(
            "AudioTrack created successfully. sampleRate=$sampleRate, channelCount=$channelCount, " +
                "audioFormat=$audioFormat, targetBufferBytes=$targetBufferBytes, " +
                "managesStartThreshold=${output.managesStartThreshold}"
        )
        return true
    }

    fun play(audioData: ByteArray): Boolean {
        return acceptAudio(audioData)
    }

    fun write(audioData: ByteArray): Int {
        if (audioData.isEmpty()) {
            return 0
        }
        if (!isPlaybackStarted.get()) {
            TtsLogger.w("write() called before play(), auto-starting playback")
        }
        return if (acceptAudio(audioData)) audioData.size else -1
    }

    /**
     * 接收一段音频：起播前进入预缓冲，起播后直接写入
     *
     * 阻塞写入在锁外执行；[stop] 只需短暂持有 [bufferLock] 清队列，再调用
     * [AudioOutput.stop] 打断写入，因此不会被大块写入或输出停滞卡住。
     */
    private fun acceptAudio(audioData: ByteArray): Boolean {
        if (audioData.isEmpty()) {
            val errorMsg = "Cannot play empty audio data"
            TtsLogger.e(errorMsg)
            notifyError(errorMsg)
            return false
        }

        // 错误终态不可复活：失败会话的残余音频必须拒绝，否则会重新起播，
        // 而排空逻辑又因 writeFailed 直接判失败，状态自相矛盾。
        // 只有 createPlayer() 显式重建才能开启新会话。
        if (writeFailed.get()) {
            TtsLogger.w("Rejecting audio after playback failure; rebuild player for a new session")
            return false
        }

        val output = ensureOutput() ?: return false
        val generation = sessionGeneration.get()

        var startTransport = false
        var chunks: List<ByteArray> = emptyList()

        synchronized(bufferLock) {
            if (writeFailed.get() || generation != sessionGeneration.get()) return false

            if (!isPlaybackStarted.get()) {
                isPlaybackStarted.set(true)
                isPlaying.set(true)
                isPrebuffering = true
            }

            if (isPrebuffering) {
                pendingAudio.add(audioData)
                pendingAudioBytes += audioData.size
                // 攒够启动水位后统一起播；短音频在 waitForPlaybackComplete
                // 时经 flushPendingAudio 冲刷，不会被吞掉
                if (pendingAudioBytes >= startupPrebufferBytes) {
                    isPrebuffering = false
                    startTransport = true
                    chunks = drainPendingLocked()
                }
            } else {
                chunks = listOf(audioData)
            }
        }

        if (startTransport && !startTransport(output, generation)) {
            return false
        }
        return writeChunks(output, chunks, generation)
    }

    /**
     * 冲刷预缓冲并立即起播
     *
     * 合成完成（或调用方确定不会再有音频）时调用：短音频不足启动水位，
     * 必须在此刻放行，否则 [waitForPlaybackComplete] 会一直等不到可排空的数据。
     */
    fun flushPendingAudio() {
        if (writeFailed.get()) return
        val output = audioOutput ?: return
        val generation = sessionGeneration.get()

        val chunks: List<ByteArray>
        synchronized(bufferLock) {
            if (writeFailed.get() || !isPrebuffering) return
            isPrebuffering = false
            chunks = drainPendingLocked()
        }

        if (chunks.isEmpty()) return
        if (!startTransport(output, generation)) return
        writeChunks(output, chunks, generation)
    }

    /** 起播：transportLock 只覆盖 play() 本身，不与写入/停止长时间互斥 */
    private fun startTransport(output: AudioOutput, generation: Long): Boolean {
        val error = try {
            synchronized(transportLock) {
                if (generation != sessionGeneration.get() || !isPlaying.get()) return false
                output.play()
            }
            null
        } catch (e: Exception) {
            e
        }

        if (error != null) {
            handleFailure(output, generation, "Failed to start playback: ${error.message}", error)
            return false
        }

        TtsLogger.d("Playback started")
        startProgressReporting()
        return true
    }

    /**
     * 写出若干段音频（锁外调用）
     *
     * 每段写入前校验会话代际：stop() 后旧写入即使被底层解除阻塞，也不得继续提交。
     * 失败进入错误终态并通知监听器；已成功写入的字节照常计数。
     */
    private fun writeChunks(output: AudioOutput, chunks: List<ByteArray>, generation: Long): Boolean {
        for (chunk in chunks) {
            when (val result = writeFully(output, chunk, generation)) {
                is WriteResult.Written -> writtenAudioBytes.addAndGet(result.bytes)
                WriteResult.Aborted -> return false
                is WriteResult.Failed -> {
                    handleFailure(output, generation, "Failed to write audio: error=${result.code}", null)
                    return false
                }
            }
        }
        return true
    }

    private fun writeFully(output: AudioOutput, audioData: ByteArray, generation: Long): WriteResult {
        var offset = 0
        while (offset < audioData.size) {
            // 先登记在飞写入再校验代际：releaseTrack 可安全等待 activeWrites 归零，
            // 而代际已作废的写入不会触碰底层 output
            activeWrites.incrementAndGet()
            try {
                if (generation != sessionGeneration.get()) return WriteResult.Aborted

                val written = try {
                    output.write(audioData, offset, audioData.size - offset)
                } catch (e: Exception) {
                    return if (generation != sessionGeneration.get()) {
                        WriteResult.Aborted
                    } else {
                        WriteResult.Failed(-1)
                    }
                }

                // 停止可能与阻塞写入并发：底层返回错误时先看会话是否已作废
                if (generation != sessionGeneration.get()) return WriteResult.Aborted
                if (written <= 0) return WriteResult.Failed(written)
                offset += written
            } finally {
                activeWrites.decrementAndGet()
            }
        }
        return WriteResult.Written(audioData.size)
    }

    /**
     * 失败终态：作废会话、打断写入、通知监听器（不持锁调用回调）
     *
     * 仅当前会话可进入：代际与输出实例双重校验。stop()/release() 先自增代际后，
     * 被解除阻塞的旧 play()/write() 才抛异常的竞态必须静默丢弃——否则旧场次会把
     * 错误回调发给已开始的新场次。失败置位与代际迁移在 [bufferLock] 内完成，
     * 与 stop()/releaseTrack() 的自增及 acceptAudio 的状态检查线性化。
     */
    private fun handleFailure(
        output: AudioOutput,
        generation: Long,
        message: String,
        cause: Throwable?
    ) {
        synchronized(bufferLock) {
            if (audioOutput !== output || generation != sessionGeneration.get()) {
                TtsLogger.d("Ignoring failure from stale playback session: $message")
                return
            }
            if (!writeFailed.compareAndSet(false, true)) return
            // 失败置位与代际迁移同锁完成：acceptAudio 的状态检查也在锁内，
            // 不会出现"代际已变、writeFailed 尚未置位"时挤入新音频的窗口
            sessionGeneration.incrementAndGet()
            isPlaying.set(false)
            isPlaybackStarted.set(false)
            pendingAudio.clear()
            pendingAudioBytes = 0
            isPrebuffering = false
        }
        try {
            output.stop()
        } catch (_: Exception) {
        }
        TtsLogger.e(message, cause)
        notifyError(message)
    }

    fun pause() {
        if (isPlaying.get()) {
            try {
                synchronized(transportLock) { audioOutput?.pause() }
                isPlaying.set(false)
                playbackProgressJob?.cancel()
                TtsLogger.d("Playback paused")
            } catch (e: Exception) {
                val errorMsg = "Failed to pause playback: ${e.message}"
                TtsLogger.e(errorMsg, e)
                notifyError(errorMsg)
            }
        }
    }

    fun resume() {
        if (!isPlaying.get() && audioOutput != null && isPlaybackStarted.get()) {
            try {
                synchronized(transportLock) { audioOutput?.play() }
                isPlaying.set(true)
                startProgressReporting()
                TtsLogger.d("Playback resumed")
            } catch (e: Exception) {
                isPlaying.set(false)
                val errorMsg = "Failed to resume playback: ${e.message}"
                TtsLogger.e(errorMsg, e)
                notifyError(errorMsg)
            }
        }
    }

    fun stop() {
        // 先作废会话，再打断写入：写入方即使在阻塞中也会在返回后放弃提交。
        // 代际迁移与 handleFailure 共用 bufferLock，二者不会交错
        val output = audioOutput
        synchronized(bufferLock) {
            sessionGeneration.incrementAndGet()
            pendingAudio.clear()
            pendingAudioBytes = 0
            isPrebuffering = false
            isPlaying.set(false)
            isPlaybackStarted.set(false)
        }
        synchronized(transportLock) {
            try {
                output?.stop()
            } catch (e: Exception) {
                TtsLogger.e("Failed to stop playback: ${e.message}", e)
            }
        }
        playbackProgressJob?.cancel()
        TtsLogger.d("Playback stopped")
    }

    /**
     * 复位播放状态并释放当前 AudioOutput（保留作用域与监听器，
     * 供 [createPlayer] 在重建播放器前调用）
     */
    private fun releaseTrack() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
        synchronized(bufferLock) {
            sessionGeneration.incrementAndGet()
            isPlaying.set(false)
            isPlaybackStarted.set(false)
            pendingAudio.clear()
            pendingAudioBytes = 0
            isPrebuffering = false
        }

        val output = synchronized(transportLock) {
            audioOutput.also { audioOutput = null }
        }
        if (output != null) {
            try {
                // 先 stop 解除可能阻塞中的写入，再等待写入退出，避免在写入线程仍持有 track 时释放
                output.stop()
            } catch (_: Exception) {
            }
            waitForActiveWrites()
            try {
                output.release()
                TtsLogger.d("AudioTrack released", tag = TAG)
            } catch (e: Exception) {
                TtsLogger.e("Error releasing AudioTrack: ${e.message}", e, TAG)
            }
        }
        writtenAudioBytes.set(0)
        writeFailed.set(false)
    }

    fun release() {
        releaseTrack()
        progressListeners.clear()
        // 终止常驻作用域（进度上报等协程），防止播放器实例释放后线程仍存活
        playerScope.cancel()
    }

    fun isCurrentlyPlaying(): Boolean {
        return isPlaying.get()
    }

    /**
     * 当前播放头帧位置（绝对帧数，自本 player 创建起单调递增）
     *
     * 供波形包络映射"此刻正在播放的音频段"；stop() 后 AudioTrack 播放头归零，
     * 调用方须以新会话重新计数
     */
    fun currentPlaybackHeadFrames(): Int {
        return try {
            audioOutput?.playbackHeadFrames ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun addProgressListener(listener: (Float, Long) -> Unit) {
        progressListeners.add(listener)
    }

    fun removeProgressListener(listener: (Float, Long) -> Unit) {
        progressListeners.remove(listener)
    }

    fun clearProgressListeners() {
        progressListeners.clear()
    }

    fun setErrorListener(listener: (String) -> Unit) {
        errorListener = listener
    }

    fun removeErrorListener() {
        errorListener = null
    }

    fun setPlaybackCompleteListener(listener: () -> Unit) {
        playbackCompleteListener = listener
    }

    fun removePlaybackCompleteListener() {
        playbackCompleteListener = null
    }

    fun waitForPlaybackComplete(timeoutSeconds: Int = 60, shouldStop: (() -> Boolean)? = null): Boolean {
        // 合成结束时短音频可能仍停留在预缓冲队列中：先放行
        flushPendingAudio()

        if (writeFailed.get()) {
            return false
        }

        val totalBytes = writtenAudioBytes.get()
        if (totalBytes <= 0) {
            return true
        }

        val output = audioOutput ?: return true
        val generation = sessionGeneration.get()

        if (shouldStop != null && shouldStop()) {
            TtsLogger.d("waitForPlaybackComplete: stop requested, aborting")
            isPlaying.set(false)
            isPlaybackStarted.set(false)
            return false
        }

        val targetFrames = totalBytes / bytesPerFrame
        val capacityBytes = try {
            output.bufferCapacityFrames * bytesPerFrame
        } catch (_: Exception) {
            0
        }
        val headFrames = try {
            output.playbackHeadFrames
        } catch (_: Exception) {
            0
        }

        // API 30 及以下无法调整起播阈值：整段音频短于缓冲容量且播放头未推进时，
        // AudioTrack 永远不会自行起播，必须 stop() 把已写入数据冲刷给 mixer，
        // 再按估算时长等待播完（AOSP BlockingAudioTrack 同款处理）
        if (!output.managesStartThreshold &&
            capacityBytes > 0 &&
            totalBytes < capacityBytes &&
            headFrames == 0
        ) {
            return waitForShortUtteranceDrain(output, generation, targetFrames, shouldStop)
        }

        return waitForPlaybackHead(output, generation, targetFrames, timeoutSeconds, shouldStop)
    }

    /**
     * 短音频排空（起播阈值不可管理的 API 30 及以下）
     *
     * stop() 后播放头与通知标记不可靠，按总帧数估算播放时长并等待；
     * stop 冲刷只是把已写入数据交给 mixer，音频仍会完整播放。
     */
    private fun waitForShortUtteranceDrain(
        output: AudioOutput,
        generation: Long,
        targetFrames: Int,
        shouldStop: (() -> Boolean)?
    ): Boolean {
        try {
            output.stop()
        } catch (e: Exception) {
            notifyPlaybackFailure(generation, "Failed to flush short utterance: ${e.message}")
            return false
        }

        val durationMs = targetFrames.toLong() * 1000L / sampleRate.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + durationMs + SHORT_UTTERANCE_TAIL_MARGIN_MS

        TtsLogger.d("Short utterance: flushing via stop(), estimated ${durationMs}ms")

        while (System.currentTimeMillis() < deadline) {
            if (generation != sessionGeneration.get()) return false
            if (shouldStop != null && shouldStop()) {
                TtsLogger.d("waitForShortUtteranceDrain: stop requested, aborting")
                return false
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            if (!sleepInterruptibly(minOf(remaining, MIN_POLL_SLEEP_MS))) return false
        }

        if (generation != sessionGeneration.get()) return false
        completePlayback()
        return true
    }

    /** 播放头轮询排空（起播阈值已受管，或音频不短于缓冲容量） */
    private fun waitForPlaybackHead(
        output: AudioOutput,
        generation: Long,
        targetFrames: Int,
        timeoutSeconds: Int,
        shouldStop: (() -> Boolean)?
    ): Boolean {
        val timeoutMs = timeoutSeconds * 1000L
        val startTime = System.currentTimeMillis()
        var previousPosition = -1
        var noProgressMs = 0L

        while (true) {
            if (generation != sessionGeneration.get()) return false
            if (writeFailed.get()) return false
            if (!isPlaying.get()) return false
            if (shouldStop != null && shouldStop()) {
                TtsLogger.d("waitForPlaybackComplete: stop requested, aborting")
                return false
            }

            if (System.currentTimeMillis() - startTime >= timeoutMs) {
                notifyPlaybackFailure(generation, "Playback did not complete within ${timeoutSeconds}s")
                return false
            }

            val positionFrames = try {
                output.playbackHeadFrames
            } catch (e: Exception) {
                notifyPlaybackFailure(generation, "Failed to read playback position: ${e.message}")
                return false
            }

            if (positionFrames >= targetFrames) {
                TtsLogger.d("waitForPlaybackComplete: playback completed at position $positionFrames frames")
                completePlayback()
                return true
            }

            val playState = try {
                output.playState
            } catch (_: Exception) {
                AudioTrack.PLAYSTATE_STOPPED
            }
            if (playState == AudioTrack.PLAYSTATE_STOPPED) {
                notifyPlaybackFailure(generation, "AudioTrack stopped before playback completed")
                return false
            }

            val remainingFrames = (targetFrames - positionFrames).coerceAtLeast(1).toLong()
            val sleepMs = (remainingFrames * 1000L / sampleRate.coerceAtLeast(1))
                .coerceIn(MIN_POLL_SLEEP_MS, MAX_POLL_SLEEP_MS)

            if (positionFrames <= previousPosition) {
                noProgressMs += sleepMs
                if (noProgressMs >= MAX_NO_PROGRESS_MS) {
                    notifyPlaybackFailure(
                        generation,
                        "AudioTrack made no progress for ${MAX_NO_PROGRESS_MS}ms"
                    )
                    return false
                }
            } else {
                noProgressMs = 0
            }
            previousPosition = positionFrames

            if (!sleepInterruptibly(sleepMs)) return false
        }
    }

    private fun completePlayback() {
        isPlaying.set(false)
        isPlaybackStarted.set(false)
        playbackCompleteListener?.invoke()
    }

    private fun notifyPlaybackFailure(generation: Long, message: String) {
        if (generation != sessionGeneration.get()) return
        isPlaying.set(false)
        isPlaybackStarted.set(false)
        TtsLogger.e(message)
        notifyError(message)
    }

    /** 等待在飞写入退出（stop 已解除阻塞，正常应在毫秒级归零） */
    private fun waitForActiveWrites() {
        val deadline = System.currentTimeMillis() + RELEASE_WRITE_WAIT_MS
        while (activeWrites.get() > 0 && System.currentTimeMillis() < deadline) {
            if (!sleepInterruptibly(5)) return
        }
        val remaining = activeWrites.get()
        if (remaining > 0) {
            TtsLogger.w("Releasing AudioTrack with $remaining active write(s)", tag = TAG)
        }
    }

    private fun sleepInterruptibly(milliseconds: Long): Boolean {
        return try {
            Thread.sleep(milliseconds)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun ensureOutput(): AudioOutput? {
        audioOutput?.let { return it }
        TtsLogger.d("AudioTrack not initialized, creating now...")
        if (!createPlayer()) {
            return null
        }
        return audioOutput
    }

    /** 须持有 [bufferLock]：取出并清空预缓冲队列 */
    private fun drainPendingLocked(): List<ByteArray> {
        if (pendingAudio.isEmpty()) return emptyList()
        val drained = ArrayList<ByteArray>(pendingAudio)
        pendingAudio.clear()
        pendingAudioBytes = 0
        return drained
    }

    private fun startProgressReporting() {
        playbackProgressJob?.cancel()
        playbackProgressJob = playerScope.launch {
            while (isActive && isPlaying.get() && audioOutput != null) {
                reportProgress()
                delay(PROGRESS_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun reportProgress() {
        val output = audioOutput ?: return
        val totalBytes = writtenAudioBytes.get()
        if (totalBytes <= 0) return

        val positionFrames = try {
            output.playbackHeadFrames
        } catch (e: Exception) {
            return
        }

        var positionBytes = positionFrames * bytesPerFrame
        if (positionBytes > totalBytes) {
            positionBytes = totalBytes
        }
        val progress = positionBytes.toFloat() / totalBytes.toFloat()
        var positionMs = positionFrames * 1000L / sampleRate
        if (positionMs < 0) {
            positionMs = 0L
        }

        val safeProgress: Float = if (progress < 0f) 0f else if (progress > 1f) 1f else progress
        val safePosition: Long = positionMs

        progressListeners.forEach { listener ->
            try {
                listener(safeProgress, safePosition)
            } catch (e: Exception) {
                TtsLogger.e("Error in progress listener: ${e.message}", e)
            }
        }
    }

    private fun notifyError(message: String) {
        errorListener?.invoke(message)
    }

    /** PCM 编码 → 每采样字节数（与 TtsPreviewPlayer.bytesPerSample 同一口径） */
    private fun bytesPerSample(format: Int): Int = when (format) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    private sealed interface WriteResult {
        data class Written(val bytes: Int) : WriteResult
        data object Aborted : WriteResult
        data class Failed(val code: Int) : WriteResult
    }
}
