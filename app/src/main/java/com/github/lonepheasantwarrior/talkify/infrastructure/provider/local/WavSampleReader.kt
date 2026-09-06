package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM16 WAV 文件解码器
 *
 * 将 ZipVoice 参考音频（模型目录内的 wav 文件）解码为 sherpa-onnx
 * [com.k2fsa.sherpa.onnx.GenerationConfig] 所需的归一化浮点样本。
 * 支持标准 RIFF/WAVE 分块遍历（fmt/data 顺序不固定，允许夹带 LIST 等附加块）。
 * 多声道自动降混为单声道。
 */
object WavSampleReader {

    /**
     * WAV 解码结果
     *
     * @param samples 归一化浮点样本（mono，[-1.0, 1.0]）
     * @param sampleRate 采样率（Hz）
     */
    class WavSamples(val samples: FloatArray, val sampleRate: Int)

    /**
     * 从模型目录内的 wav 文件读取参考音频
     *
     * @throws IOException 文件不存在或格式不合法
     */
    fun read(file: File): WavSamples {
        if (!file.exists() || file.length() <= 0) {
            throw IOException("WAV file missing or empty: ${file.absolutePath}")
        }
        return parse(file.readBytes())
    }

    /**
     * 解析 PCM16 WAV 字节数组
     *
     * @throws IllegalArgumentException 非合法的 PCM16 WAV 数据
     */
    fun parse(bytes: ByteArray): WavSamples {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12) throw IllegalArgumentException("WAV data too short: ${bytes.size} bytes")
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            throw IllegalArgumentException("Not a RIFF container")
        }
        if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
            bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()
        ) {
            throw IllegalArgumentException("RIFF container is not WAVE")
        }

        var sampleRate = -1
        var channels = -1
        var pcmPayload: ByteArray? = null

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4).toInt() and 0xFFFFFFFF.toInt()
            val payloadStart = offset + 8
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16 || payloadStart + 16 > bytes.size) {
                        throw IllegalArgumentException("Invalid fmt chunk")
                    }
                    val audioFormat = readUShort(buffer, payloadStart)
                    channels = readUShort(buffer, payloadStart + 2)
                    sampleRate = buffer.getInt(payloadStart + 4)
                    val bitsPerSample = readUShort(buffer, payloadStart + 14)
                    if (audioFormat != 1) {
                        throw IllegalArgumentException("Unsupported WAV format $audioFormat, only PCM (1) is supported")
                    }
                    if (bitsPerSample != 16) {
                        throw IllegalArgumentException("Unsupported bit depth $bitsPerSample, only PCM16 is supported")
                    }
                    if (channels < 1) {
                        throw IllegalArgumentException("Invalid channel count: $channels")
                    }
                }
                "data" -> {
                    if (chunkSize <= 0) throw IllegalArgumentException("Empty data chunk")
                    val available = minOf(chunkSize.toLong(), (bytes.size - payloadStart).toLong()).toInt()
                    if (available <= 0) throw IllegalArgumentException("Truncated data chunk")
                    pcmPayload = bytes.copyOfRange(payloadStart, payloadStart + available)
                }
            }
            // 分块按 2 字节对齐，奇数长度块附带 1 字节填充
            offset = payloadStart + chunkSize + (chunkSize and 1)
        }

        val payload = pcmPayload ?: throw IllegalArgumentException("No data chunk found")
        if (sampleRate <= 0) throw IllegalArgumentException("Missing or invalid fmt chunk")

        return WavSamples(samples = decodePcm16(payload, channels), sampleRate = sampleRate)
    }

    private fun decodePcm16(payload: ByteArray, channels: Int): FloatArray {
        val frameCount = payload.size / 2 / channels
        val samples = FloatArray(frameCount)
        val frameBuffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frameCount) {
            var accumulator = 0
            for (channel in 0 until channels) {
                accumulator += frameBuffer.short
            }
            samples[frame] = (accumulator.toFloat() / channels) / 32768f
        }
        return samples
    }

    private fun readUShort(buffer: ByteBuffer, offset: Int): Int {
        val value = buffer.getShort(offset).toInt() and 0xFFFF
        return value
    }
}
