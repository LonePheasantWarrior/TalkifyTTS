package com.github.lonepheasantwarrior.talkify.service.provider

import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MP3 音频流解码工具。
 *
 * 供 AzureProvider 和 MiniMaxProvider 共用：管道输入流 → JLayer 逐帧解码 → PCM 16bit。
 * 纯回调设计，不依赖供应商与监听器接口，便于单元测试。
 */
object Mp3StreamDecoder {

    private const val TAG = "Mp3StreamDecoder"

    /**
     * 高性能 PCM 转换：利用 NIO ByteBuffer 直接内存块复制。
     *
     * 将 JLayer 解码后的 ShortArray 样本转换为小端序 16-bit PCM 字节数组。
     *
     * @param shortArray PCM 样本数组
     * @param length 有效样本数量
     * @return 小端序的 16-bit PCM 字节数组
     */
    fun shortArrayToByteArray(shortArray: ShortArray, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(shortArray, 0, length)
        return buffer.array()
    }

    /**
     * 解码 MP3 流并输出 PCM 音频数据
     *
     * 逐帧读取 MP3 数据流，使用 JLayer 解码为 PCM 16bit 并通过 [onAudio] 回调。
     * 流结束、取消或读取异常（含管道关闭）时返回。
     *
     * @param inputStream MP3 数据输入流（如 PipedInputStream）
     * @param isCancelled 取消探测，返回 true 时停止解码循环
     * @param onAudio 音频回调：PCM 字节、实际采样率（Hz）、声道数
     */
    fun decodeMp3Stream(
        inputStream: InputStream,
        isCancelled: () -> Boolean,
        onAudio: (pcmBytes: ByteArray, sampleRate: Int, channelCount: Int) -> Unit
    ) {
        val bitstream = Bitstream(inputStream)
        val decoder = Decoder()

        try {
            while (!isCancelled()) {
                val header: Header = bitstream.readFrame() ?: break

                val sampleRate = header.frequency()

                val sampleBuffer = decoder.decodeFrame(header, bitstream) as SampleBuffer
                val sampleCount = sampleBuffer.bufferLength

                if (sampleCount > 0) {
                    val pcmBytes = shortArrayToByteArray(sampleBuffer.buffer, sampleCount)
                    onAudio(pcmBytes, sampleRate, sampleBuffer.channelCount)
                }

                bitstream.closeFrame()
            }
        } catch (e: Exception) {
            TtsLogger.d("$TAG: decoding finished or interrupted: ${e.message}")
        } finally {
            try {
                bitstream.close()
            } catch (_: Exception) {
            }
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
        }
    }
}
