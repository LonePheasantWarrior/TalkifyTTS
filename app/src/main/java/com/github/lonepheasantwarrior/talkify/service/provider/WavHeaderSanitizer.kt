package com.github.lonepheasantwarrior.talkify.service.provider

/**
 * WAV 文件头剥离工具
 *
 * 部分云端接口返回的音频流首个数据包可能携带 44 字节标准 WAV 头，
 * 若不剥离，元数据会被当作波形数据播放，产生刺耳的"滋"声（首字破音）。
 * 纯函数实现，便于单元测试。
 */
object WavHeaderSanitizer {

    private const val WAV_HEADER_SIZE = 44

    /**
     * 检测并剥离音频数据前部的 WAV 文件头
     *
     * 通过 RIFF 与 WAVE 标识识别，标准 WAV 头固定为 44 字节。
     *
     * @param data 原始音频数据
     * @return 剥离 WAV 头后的 PCM 数据；无 WAV 头时原样返回同一实例
     */
    fun stripWavHeader(data: ByteArray): ByteArray {
        if (data.size >= WAV_HEADER_SIZE &&
            data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'A'.code.toByte() &&
            data[10] == 'V'.code.toByte() && data[11] == 'E'.code.toByte()
        ) {
            return data.copyOfRange(WAV_HEADER_SIZE, data.size)
        }
        return data
    }
}
