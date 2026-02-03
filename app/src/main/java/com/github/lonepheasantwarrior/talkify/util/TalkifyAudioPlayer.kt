package com.github.lonepheasantwarrior.talkify.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class TalkifyAudioPlayer(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channelCount: Int = DEFAULT_CHANNEL_COUNT,
    private val audioFormat: Int = DEFAULT_AUDIO_FORMAT
) {
    companion object {
        private const val TAG = "TalkifyAudioPlayer"

        const val DEFAULT_SAMPLE_RATE = 24000
        const val DEFAULT_CHANNEL_COUNT = 1
        const val DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val MIN_BUFFER_MULTIPLIER = 2
        private const val PROGRESS_CHECK_INTERVAL_MS = 50L
        private const val PLAYBACK_COMPLETE_CHECK_INTERVAL_MS = 20L

        private const val ATTRIBUTION_TAG = "TalkifyTtsService"
    }

    private var audioTrack: AudioTrack? = null

    private var isPlaying = AtomicBoolean(false)

    private var isPlaybackStarted = AtomicBoolean(false)

    private val playerScope = CoroutineScope(Dispatchers.IO + Job())

    private var totalAudioBytes: Int = 0

    private var playbackProgressJob: Job? = null

    private var progressListeners = mutableListOf<(Float, Long) -> Unit>()

    private var errorListener: ((String) -> Unit)? = null

    private var playbackCompleteListener: (() -> Unit)? = null

    fun configureAudioAttributes(
        usage: Int = AudioAttributes.USAGE_MEDIA,
        contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH
    ): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
    }

    fun configureAudioFormat(
        sampleRate: Int = this.sampleRate,
        channelMask: Int,
        encoding: Int = this.audioFormat
    ): AudioFormat {
        return AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(encoding)
            .build()
    }

    fun createPlayer(
        audioAttributes: AudioAttributes = configureAudioAttributes(),
        audioFormat: AudioFormat = configureAudioFormat(
            channelMask = if (channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
        )
    ): Boolean {
        release()

        val bufferSize = AudioTrack.getMinBufferSize(
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding
        )

        if (bufferSize <= 0) {
            val errorMsg = "Invalid buffer size: $bufferSize. Audio parameters may be unsupported."
            TtsLogger.e(errorMsg, null, TAG)
            notifyError(errorMsg)
            return false
        }

        return try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize * MIN_BUFFER_MULTIPLIER)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            TtsLogger.d("AudioTrack created successfully. sampleRate=$sampleRate, channelCount=$channelCount, audioFormat=$audioFormat")
            true
        } catch (e: Exception) {
            val errorMsg = "Failed to create AudioTrack: ${e.message}"
            TtsLogger.e(errorMsg, e, TAG)
            notifyError(errorMsg)
            false
        }
    }

    fun play(audioData: ByteArray): Boolean {
        if (audioData.isEmpty()) {
            val errorMsg = "Cannot play empty audio data"
            TtsLogger.e(errorMsg)
            notifyError(errorMsg)
            return false
        }

        var track = audioTrack
        if (track == null) {
            TtsLogger.d("AudioTrack not initialized, creating now...")
            if (!createPlayer()) {
                return false
            }
            track = audioTrack
        }

        return try {
            val wasPlaying = isPlaying.getAndSet(true)
            if (!wasPlaying) {
                isPlaybackStarted.set(true)
                totalAudioBytes = audioData.size
                track?.play()
                TtsLogger.d("Playback started")
                startProgressReporting()
            } else {
                totalAudioBytes += audioData.size
            }

            val writtenBytes = track?.write(audioData, 0, audioData.size) ?: -1
            if (writtenBytes > 0) {
                TtsLogger.v("Written $writtenBytes bytes to AudioTrack, total: $totalAudioBytes")
            }
            true
        } catch (e: Exception) {
            isPlaying.set(false)
            isPlaybackStarted.set(false)
            val errorMsg = "Failed to play audio: ${e.message}"
            TtsLogger.e(errorMsg, e)
            notifyError(errorMsg)
            false
        }
    }

    fun write(audioData: ByteArray): Int {
        if (audioData.isEmpty()) {
            return 0
        }

        if (!isPlaybackStarted.get()) {
            TtsLogger.w("write() called before play(), calling play() first")
            play(audioData)
            return audioData.size
        }

        return try {
            val writtenBytes = audioTrack?.write(audioData, 0, audioData.size) ?: -1
            if (writtenBytes > 0) {
                totalAudioBytes += writtenBytes
                TtsLogger.v("Streamed $writtenBytes bytes to AudioTrack")
            }
            writtenBytes
        } catch (e: Exception) {
            TtsLogger.e("Failed to write audio data: ${e.message}", e)
            -1
        }
    }

    fun pause() {
        if (isPlaying.get()) {
            try {
                audioTrack?.pause()
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
        if (!isPlaying.get() && audioTrack != null && isPlaybackStarted.get()) {
            try {
                isPlaying.set(true)
                audioTrack?.play()
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
        if (isPlaying.get()) {
            try {
                isPlaying.set(false)
                isPlaybackStarted.set(false)
                audioTrack?.stop()
                playbackProgressJob?.cancel()
                TtsLogger.d("Playback stopped")
            } catch (e: Exception) {
                val errorMsg = "Failed to stop playback: ${e.message}"
                TtsLogger.e(errorMsg, e)
                notifyError(errorMsg)
            }
        }
    }

    fun release() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
        isPlaying.set(false)
        isPlaybackStarted.set(false)

        try {
            audioTrack?.release()
            TtsLogger.d("AudioTrack released")
        } catch (e: Exception) {
            val errorMsg = "Error releasing AudioTrack: ${e.message}"
            TtsLogger.e(errorMsg, e)
        }
        audioTrack = null
        totalAudioBytes = 0
        progressListeners.clear()
    }

    fun isCurrentlyPlaying(): Boolean {
        return isPlaying.get()
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
        if (totalAudioBytes <= 0) {
            return true
        }

        if (shouldStop != null && shouldStop()) {
            TtsLogger.d("waitForPlaybackComplete: stop requested, aborting")
            isPlaying.set(false)
            isPlaybackStarted.set(false)
            return false
        }

        val bytesPerFrame = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
            channelCount * 2
        } else {
            channelCount
        }

        val targetFrames = totalAudioBytes / bytesPerFrame
        val timeoutMs = timeoutSeconds * 1000L
        val startTime = System.currentTimeMillis()

        return try {
            while (isPlaying.get() && audioTrack != null) {
                if (shouldStop != null && shouldStop()) {
                    TtsLogger.d("waitForPlaybackComplete: stop requested, aborting")
                    isPlaying.set(false)
                    isPlaybackStarted.set(false)
                    return false
                }

                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= timeoutMs) {
                    TtsLogger.w("waitForPlaybackComplete: timeout after ${timeoutSeconds}s")
                    return false
                }

                val positionFrames = try {
                    audioTrack?.playbackHeadPosition ?: 0
                } catch (e: Exception) {
                    0
                }

                if (positionFrames >= targetFrames) {
                    TtsLogger.d("waitForPlaybackComplete: playback completed at position $positionFrames frames")
                    isPlaying.set(false)
                    isPlaybackStarted.set(false)
                    playbackCompleteListener?.invoke()
                    return true
                }

                Thread.sleep(PLAYBACK_COMPLETE_CHECK_INTERVAL_MS)
            }
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun startProgressReporting() {
        playbackProgressJob?.cancel()
        playbackProgressJob = playerScope.launch {
            while (isActive && isPlaying.get() && audioTrack != null) {
                reportProgress()
                delay(PROGRESS_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun reportProgress() {
        val track = audioTrack ?: return
        if (totalAudioBytes <= 0) return

        val positionFrames = try {
            track.playbackHeadPosition
        } catch (e: Exception) {
            return
        }

        val bytesPerFrame = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
            channelCount * 2
        } else {
            channelCount
        }

        var positionBytes = positionFrames * bytesPerFrame
        if (positionBytes > totalAudioBytes) {
            positionBytes = totalAudioBytes
        }
        val progress = positionBytes.toFloat() / totalAudioBytes.toFloat()
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
}
