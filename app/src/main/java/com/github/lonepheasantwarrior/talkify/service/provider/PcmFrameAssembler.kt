package com.github.lonepheasantwarrior.talkify.service.provider

/**
 * PCM 字节流帧对齐器
 *
 * 网络/HTTP 分块边界可能落在采样帧中间（16bit 单声道时一帧仅 2 字节），
 * 而 AudioTrack / SynthesisCallback 以帧为单位消费，写入尾部不足一帧的
 * 字节会被丢弃——其后所有样本随之错位，听感为持续的刺耳噪声。
 * 本类跨分块缓存不足一帧的尾字节，只向下游输出完整帧。
 *
 * 非线程安全：每个流式会话（每条 HTTP 响应）创建独立实例。
 */
internal class PcmFrameAssembler(private val frameSizeBytes: Int) {

    init {
        require(frameSizeBytes > 0) { "frameSizeBytes must be > 0" }
    }

    /** 尚未凑满一帧的尾字节；null 表示无余量 */
    private var carry: ByteArray? = null

    /**
     * 输入一段任意长度的 PCM 字节，返回其中可安全按帧消费的完整前缀。
     * 返回空数组表示本次输入尚不足以构成一帧，全部字节留待下次拼接。
     */
    fun feed(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pending = carry
        val combined = if (pending == null) data else pending + data
        val usable = combined.size - combined.size % frameSizeBytes
        carry = if (usable == combined.size) null else combined.copyOfRange(usable, combined.size)
        return when {
            usable == combined.size -> combined
            usable == 0 -> EMPTY
            else -> combined.copyOf(usable)
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
