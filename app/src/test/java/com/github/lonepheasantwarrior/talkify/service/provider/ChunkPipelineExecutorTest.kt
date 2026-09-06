package com.github.lonepheasantwarrior.talkify.service.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPipelineExecutorTest {

    private fun executor(window: Int = 2, capacity: Int = 64) =
        ChunkPipelineExecutor(window = window, bufferCapacity = capacity)

    @Test
    fun `flush order strictly follows chunk order`() = runBlocking {
        val emitted = mutableListOf<Int>()
        val result = executor().execute(
            chunkCount = 3,
            isCancelled = { false },
            fetch = { index, sink ->
                for (piece in 0 until 3) {
                    sink(byteArrayOf(index.toByte(), piece.toByte()))
                }
                true
            },
            emit = { data -> emitted.add(data[0].toInt()) }
        )

        assertTrue(result)
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2), emitted)
    }

    @Test
    fun `failure stops emission of later chunks`() = runBlocking {
        val emitted = mutableListOf<Int>()
        val result = withTimeout(5000) {
            executor().execute(
                chunkCount = 4,
                isCancelled = { false },
                fetch = { index, sink ->
                    sink(byteArrayOf(index.toByte()))
                    index != 1 // 第 1 块（0 起）失败
                },
                emit = { data -> emitted.add(data[0].toInt()) }
            )
        }

        assertFalse(result)
        // 第 2、3 块的音频不得输出
        assertEquals(listOf(0, 1), emitted)
    }

    @Test
    fun `all failures propagate as false`() = runBlocking {
        val result = executor().execute(
            chunkCount = 1,
            isCancelled = { false },
            fetch = { _, _ -> false },
            emit = { }
        )
        assertFalse(result)
    }

    @Test
    fun `cancelled flag stops pipeline`() = runBlocking {
        var cancelled = false
        val emitted = mutableListOf<Int>()
        val job = launch {
            executor().execute(
                chunkCount = 100,
                isCancelled = { cancelled },
                fetch = { index, sink ->
                    sink(byteArrayOf(index.toByte()))
                    true
                },
                emit = { data ->
                    emitted.add(data[0].toInt())
                    if (data[0].toInt() == 2) cancelled = true
                }
            )
        }
        job.join()
        // 第 3 块输出后置取消标志，后续块不再输出
        assertTrue(emitted.size <= 4)
        assertFalse(emitted.contains(5))
    }

    @Test
    fun `backpressure suspends fast fetch until consumer drains`() = runBlocking {
        val emitted = mutableListOf<Int>()
        val result = withTimeout(5000) {
            // 缓冲容量 1 条：fetch 必须等消费端 drain 才能继续 send
            executor(window = 2, capacity = 1).execute(
                chunkCount = 3,
                isCancelled = { false },
                fetch = { index, sink ->
                    for (piece in 0 until 5) {
                        sink(byteArrayOf(index.toByte(), piece.toByte()))
                        yield()
                    }
                    true
                },
                emit = { data -> emitted.add(data[0].toInt()) }
            )
        }

        assertTrue(result)
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2), emitted)
    }

    @Test
    fun `fetch cancellation is treated as failure`() = runBlocking {
        val result = withTimeout(5000) {
            executor().execute(
                chunkCount = 3,
                isCancelled = { false },
                fetch = { _, sink ->
                    sink(byteArrayOf(0))
                    throw CancellationException("stop")
                },
                emit = { }
            )
        }
        // fetch 被取消（如 provider.stop()）→ 视为该块失败，不向上抛异常
        assertFalse(result)
    }
}
