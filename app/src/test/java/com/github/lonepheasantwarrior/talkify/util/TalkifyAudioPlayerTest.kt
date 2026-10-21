package com.github.lonepheasantwarrior.talkify.util

import android.media.AudioFormat
import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class TalkifyAudioPlayerTest {

    private fun createPlayer(
        output: FakeAudioOutput,
        sampleRate: Int = 1000
    ): TalkifyAudioPlayer {
        return TalkifyAudioPlayer(
            sampleRate = sampleRate,
            channelCount = 1,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            outputFactory = { _, _, _, _, _ -> output }
        )
    }

    @Test
    fun `legacy short utterance is flushed via stop and drained by estimate`() {
        val output = FakeAudioOutput(
            managesStartThreshold = false,
            bufferCapacityFrames = 10_000,
            headProvider = { 0 }
        )
        val player = createPlayer(output)
        val errors = CopyOnWriteArrayList<String>()
        player.setErrorListener { errors.add(it) }

        // 100 字节 = 50 帧、50ms，低于预缓冲水位；flush 后仍短于缓冲容量
        assertTrue(player.play(ByteArray(100)))

        val startedAt = System.currentTimeMillis()
        assertTrue(player.waitForPlaybackComplete(timeoutSeconds = 2))
        val elapsed = System.currentTimeMillis() - startedAt

        // 必须经 stop() 冲刷给 mixer，并按 50ms 估算时长等待（+100ms 余量）
        assertEquals(1, output.stopCalls.get())
        assertTrue("elapsed=$elapsed", elapsed >= 100)
        assertTrue("unexpected error: $errors", errors.isEmpty())
        player.release()
    }

    @Test
    fun `managed start threshold polls playback head without forcing stop`() {
        val output = FakeAudioOutput(
            managesStartThreshold = true,
            bufferCapacityFrames = 10_000,
            headProvider = { self ->
                // 模拟播放头推进：每次读取前进 25 帧，目标为 50 帧
                val position = self.headReadCount.incrementAndGet() * 25
                position.coerceAtMost(50)
            }
        )
        val player = createPlayer(output)
        val errors = CopyOnWriteArrayList<String>()
        player.setErrorListener { errors.add(it) }

        assertTrue(player.play(ByteArray(100)))
        assertTrue(player.waitForPlaybackComplete(timeoutSeconds = 2))

        assertEquals(0, output.stopCalls.get())
        assertTrue("unexpected error: $errors", errors.isEmpty())
        player.release()
    }

    @Test
    fun `stop interrupts a blocking write without waiting for it`() {
        val output = FakeAudioOutput(
            managesStartThreshold = true,
            bufferCapacityFrames = 10_000,
            headProvider = { 0 }
        )
        output.blockWrites = true
        val player = createPlayer(output, sampleRate = 100)

        val playResult = AtomicBoolean(true)
        val worker = thread(name = "audio-writer-test") {
            playResult.set(player.play(ByteArray(1000)))
        }

        assertTrue("write did not start", output.writeStarted.await(2, TimeUnit.SECONDS))
        val stopStartedAt = System.currentTimeMillis()
        player.stop()
        val stopElapsed = System.currentTimeMillis() - stopStartedAt

        worker.join(2_000)
        assertFalse("writer thread still blocked", worker.isAlive)
        assertTrue("stop took ${stopElapsed}ms", stopElapsed < 1_000)
        assertFalse("play should abort after stop", playResult.get())
        player.release()
    }

    @Test
    fun `write failure aborts playback and reports error`() {
        val output = FakeAudioOutput(
            managesStartThreshold = true,
            bufferCapacityFrames = 10_000,
            headProvider = { 0 }
        )
        output.failWrites = true
        val player = createPlayer(output, sampleRate = 100)
        val errors = CopyOnWriteArrayList<String>()
        player.setErrorListener { errors.add(it) }

        assertFalse(player.play(ByteArray(1000)))
        assertTrue("expected error callback", errors.isNotEmpty())

        val startedAt = System.currentTimeMillis()
        assertFalse(player.waitForPlaybackComplete(timeoutSeconds = 5))
        assertTrue(
            "failed player must not wait for the timeout",
            System.currentTimeMillis() - startedAt < 1_000
        )
        player.release()
    }

    @Test
    fun `write returns negative when lower layer fails`() {
        val output = FakeAudioOutput(
            managesStartThreshold = true,
            bufferCapacityFrames = 10_000,
            headProvider = { 0 }
        )
        val player = createPlayer(output, sampleRate = 100)

        assertTrue(player.play(ByteArray(1000)))
        output.failWrites = true
        assertEquals(-1, player.write(ByteArray(10)))
        player.release()
    }

    @Test
    fun `audio after terminal failure is rejected until player is rebuilt`() {
        val output = FakeAudioOutput(
            managesStartThreshold = true,
            bufferCapacityFrames = 10_000,
            headProvider = { 0 }
        )
        output.failWrites = true
        val player = createPlayer(output, sampleRate = 100)
        val errors = CopyOnWriteArrayList<String>()
        player.setErrorListener { errors.add(it) }

        assertFalse(player.play(ByteArray(800)))
        assertEquals(1, errors.size)

        output.failWrites = false
        assertFalse("post-failure play must not revive playback", player.play(ByteArray(800)))
        assertEquals(-1, player.write(ByteArray(100)))
        assertEquals("rejection must not emit a duplicate error", 1, errors.size)
        assertFalse(player.isCurrentlyPlaying())
        assertFalse(player.waitForPlaybackComplete(timeoutSeconds = 1))

        // 只有显式重建播放器才能开启新会话
        assertTrue(player.createPlayer())
        assertTrue(player.play(ByteArray(800)))
        player.release()
    }

    @Test
    fun `start failure from a stale session does not notify error`() {
        val output = FakeAudioOutput(
            managesStartThreshold = true,
            bufferCapacityFrames = 10_000,
            headProvider = { 0 }
        )
        val player = createPlayer(output, sampleRate = 1000)
        val errors = CopyOnWriteArrayList<String>()
        player.setErrorListener { errors.add(it) }

        // 在同一线程重入 stop()（synchronized 可重入）确定性复现：
        // stop 先自增代际，随后阻塞中的 play() 才抛异常
        output.onPlay = {
            player.stop()
            throw IllegalStateException("play failed after stop")
        }

        // 800 字节 ≥ 300ms 预缓冲水位（sampleRate=1000 → 600 字节）
        assertFalse(player.play(ByteArray(800)))
        assertTrue("stale start failure must not notify: $errors", errors.isEmpty())
        player.release()
    }

    @Test
    fun `stop during short utterance drain aborts wait`() {
        val output = FakeAudioOutput(
            managesStartThreshold = false,
            bufferCapacityFrames = 10_000,
            headProvider = { 0 }
        )
        val player = createPlayer(output)
        assertTrue(player.play(ByteArray(100)))

        val result = AtomicBoolean(true)
        val worker = thread(name = "short-drain-test") {
            result.set(player.waitForPlaybackComplete(timeoutSeconds = 5))
        }

        // 短音频估算时长为 50ms + 100ms 余量，等待期间 stop 应能立即终止
        Thread.sleep(20)
        val stopStartedAt = System.currentTimeMillis()
        player.stop()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertFalse(result.get())
        assertTrue("stop took too long", System.currentTimeMillis() - stopStartedAt < 1_000)
        player.release()
    }

    private class FakeAudioOutput(
        override val managesStartThreshold: Boolean,
        override val bufferCapacityFrames: Int,
        private val headProvider: (FakeAudioOutput) -> Int
    ) : AudioOutput {

        @Volatile
        var failWrites = false

        @Volatile
        var blockWrites = false

        @Volatile
        var onPlay: (() -> Unit)? = null

        @Volatile
        private var stopped = false

        val headReadCount = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val writeStarted = CountDownLatch(1)
        private val releaseWrite = CountDownLatch(1)

        override val playbackHeadFrames: Int
            get() = headProvider(this)

        override val playState: Int
            get() = if (stopped) AudioTrack.PLAYSTATE_STOPPED else AudioTrack.PLAYSTATE_PLAYING

        override fun play() {
            onPlay?.invoke()
        }

        override fun pause() = Unit

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            if (blockWrites) {
                writeStarted.countDown()
                releaseWrite.await(5, TimeUnit.SECONDS)
            }
            return if (failWrites) -1 else size
        }

        override fun stop() {
            stopped = true
            stopCalls.incrementAndGet()
            releaseWrite.countDown()
        }

        override fun release() = Unit
    }
}
