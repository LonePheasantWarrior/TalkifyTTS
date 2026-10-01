package com.github.lonepheasantwarrior.talkify.service.provider

/**
 * WAV 文件头剥离工具
 *
 * 部分云端接口返回的音频流首个数据包可能携带 WAV 文件头，
 * 若不剥离，元数据会被当作波形数据播放，产生刺耳的"滋"声（首字破音）。
 * 纯函数实现，便于单元测试。
 */
object WavHeaderSanitizer {

    private const val WAV_HEADER_SIZE = 44

    /**
     * 检测并剥离音频数据前部的 WAV 文件头
     *
     * 通过 RIFF 与 WAVE 标识识别后，遍历 chunk 链定位 data 段（N11）——
     * 含 extra chunk（LIST/fact 等）的 WAV 头不止 44 字节，固定剥 44 会把
     * extra chunk 当作波形播放；未找到 data 段（含流式分片截断）时回退
     * 44 字节标准头假设，保持旧行为兜底。
     *
     * @param data 原始音频数据
     * @return 剥离 WAV 头后的 PCM 数据；无 WAV 头时原样返回同一实例
     */
    fun stripWavHeader(data: ByteArray): ByteArray {
        if (data.size < WAV_HEADER_SIZE) return data
        val isWav = data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
                data[8] == 'W'.code.toByte() && data[9] == 'A'.code.toByte() &&
                data[10] == 'V'.code.toByte() && data[11] == 'E'.code.toByte()
        if (!isWav) return data

        // chunk 链自偏移 12 起：id(4) + size(4, 小端) + payload（按 2 字节对齐）
        var offset = 12L
        while (offset + 8 <= data.size) {
            val chunkSize = readIntLe(data, offset + 4)
            if (isChunkId(data, offset, "data")) {
                val audioStart = (offset + 8).toInt()
                // 流式首包可能尚未收满整个 data 段：以实际可用长度为准。
                // size 为 0 或 0xFFFFFFFF（读作 -1，流式"未知长度"占位）时同样按
                // "头后全部即音频"处理——返回空数组会丢弃整包真实音频，
                // break 回退固定 44 字节则会在含 extra chunk 的头部残留杂音
                val len = if (chunkSize <= 0) {
                    data.size - audioStart
                } else {
                    minOf(chunkSize.toLong(), (data.size - audioStart).toLong()).toInt()
                }
                return data.copyOfRange(audioStart, audioStart + len)
            }
            if (chunkSize < 0) break // 非 data 块的异常长度（≥2GB）：结构不可信，走兜底
            offset += 8L + chunkSize + (chunkSize and 1)
        }
        return data.copyOfRange(WAV_HEADER_SIZE, data.size)
    }

    private fun isChunkId(data: ByteArray, offset: Long, id: String): Boolean {
        for (i in id.indices) {
            if (data[(offset + i).toInt()] != id[i].code.toByte()) return false
        }
        return true
    }

    private fun readIntLe(data: ByteArray, offset: Long): Int {
        val base = offset.toInt()
        return (data[base].toInt() and 0xFF) or
                ((data[base + 1].toInt() and 0xFF) shl 8) or
                ((data[base + 2].toInt() and 0xFF) shl 16) or
                ((data[base + 3].toInt() and 0xFF) shl 24)
    }
}
