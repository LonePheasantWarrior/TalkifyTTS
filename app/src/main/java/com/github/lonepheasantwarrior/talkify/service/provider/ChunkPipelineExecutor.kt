package com.github.lonepheasantwarrior.talkify.service.provider

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * 分块预取流水线执行器
 *
 * 解决 HTTP 流式供应商严格串行分块合成的块间空窗问题：
 * 串行模式下第 i+1 块请求必须等第 i 块完全结束才发出，每块之间插入一次
 * 网络往返延迟（RTT + 首包延迟），长文本朗读出现规律性停顿。
 *
 * 流水线策略：维护大小为 [window] 的滑动窗口——当前块音频仍在流式传输时，
 * 后续块的请求已并发发出，其音频暂存于块级缓冲；消费端严格按块序号 flush，
 * 保证任何情况下音频输出顺序 = 文本分块顺序（核心不变量）。
 *
 * 背压：每块缓冲为有限容量 Channel（[bufferCapacity] 条音频消息），
 * 预取过快时 fetch 协程在 send 处挂起，TCP 层自然反压服务端。
 * 内存上界约为 window × bufferCapacity × 单条音频消息大小（数 MB 量级）。
 *
 * 取消与错误：
 * - 宿主协程被取消（provider.stop()）时全部 in-flight 请求一并取消；
 * - 任一块 fetch 失败或被取消时，取消剩余 in-flight 请求并停止 flush，
 *   已失败块之后的内容不会输出（错误回调由 fetch 方自行触发）。
 *
 * @param window 滑动窗口大小：同时在服务端排队的块数
 * @param bufferCapacity 每块音频缓冲的消息条数上限
 */
internal class ChunkPipelineExecutor(
    private val window: Int = DEFAULT_WINDOW,
    private val bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY
) {

    init {
        require(window >= 1) { "window must be >= 1" }
        require(bufferCapacity >= 1) { "bufferCapacity must be >= 1" }
    }

    /**
     * 执行流水线合成
     *
     * @param chunkCount 分块总数
     * @param isCancelled 取消探测
     * @param fetch 拉取第 [index] 块：将音频逐条写入 [sink]（suspend，支持背压），
     *   返回该块是否成功；音频写入顺序即块内顺序
     * @param emit 按序 flush 一条音频（由调用方回调监听器）
     * @return 全部块成功返回 true；任一块失败/被取消返回 false
     */
    suspend fun execute(
        chunkCount: Int,
        isCancelled: () -> Boolean,
        fetch: suspend (index: Int, sink: suspend (ByteArray) -> Unit) -> Boolean,
        emit: suspend (audioData: ByteArray) -> Unit
    ): Boolean = coroutineScope {
        val buffers = Array(chunkCount) { Channel<ByteArray>(bufferCapacity) }
        val results = arrayOfNulls<Boolean>(chunkCount)
        var launchedUpTo = -1

        try {
            for (index in 0 until chunkCount) {
                if (isCancelled()) return@coroutineScope false

                // 滑动窗口：保证 [index, index + window) 范围内的块都已在飞
                while (launchedUpTo < minOf(index + window - 1, chunkCount - 1)) {
                    launchedUpTo++
                    val fetchIndex = launchedUpTo
                    launch {
                        try {
                            results[fetchIndex] = fetch(fetchIndex) { data -> buffers[fetchIndex].send(data) }
                        } finally {
                            // 取消/异常路径也必须 close，否则消费端永久挂起
                            buffers[fetchIndex].close()
                        }
                    }
                }

                // 按序消费第 index 块：阻塞至该块全部音频 flush 完毕
                for (data in buffers[index]) {
                    emit(data)
                }

                if (results[index] != true) {
                    return@coroutineScope false
                }
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            // 停止所有仍在飞的 fetch（失败/取消路径）
            if (results.any { it != true } || isCancelled()) {
                currentCoroutineContext().cancelChildren()
            }
        }
    }

    private companion object {
        /** 同时在服务端排队的块数：当前块传输时，后续 1 块已预取 */
        const val DEFAULT_WINDOW = 2

        /** 每块音频缓冲的消息条数上限（背压水位） */
        const val DEFAULT_BUFFER_CAPACITY = 64
    }
}
