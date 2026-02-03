package com.github.lonepheasantwarrior.talkify.service.engine

/**
 * TTS 流式结果处理器
 *
 * 用于处理流式音频合成的各个阶段
 * 解耦具体 SDK 实现与音频处理逻辑
 *
 * @param AudioType 音频数据类型
 */
interface TtsStreamHandler<AudioType> {

    /**
     * 收到音频数据块
     *
     * @param audioData 音频数据
     */
    fun onAudioChunk(audioData: AudioType)

    /**
     * 合成开始
     */
    fun onSynthesisStarted()

    /**
     * 合成完成
     */
    fun onSynthesisCompleted()

    /**
     * 合成出错
     *
     * @param error 错误信息
     */
    fun onError(error: String)

    /**
     * 合成被取消
     */
    fun onCancelled()
}

/**
 * 字节数组流式处理器
 *
 * [TtsStreamHandler] 的 [ByteArray] 具体实现
 */
interface ByteStreamHandler : TtsStreamHandler<ByteArray>

/**
 * 空结果处理器
 *
 * 用于不需要处理音频的场景
 */
object NoOpStreamHandler : ByteStreamHandler {
    override fun onAudioChunk(audioData: ByteArray) {}
    override fun onSynthesisStarted() {}
    override fun onSynthesisCompleted() {}
    override fun onError(error: String) {}
    override fun onCancelled() {}
}
