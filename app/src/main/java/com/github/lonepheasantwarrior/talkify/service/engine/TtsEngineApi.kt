package com.github.lonepheasantwarrior.talkify.service.engine

import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig

/**
 * TTS 引擎 API 接口
 *
 * 定义语音合成引擎必须实现的核心方法
 * 采用接口设计，解耦引擎实现与调用方逻辑
 * 支持多引擎接入，每种引擎实现此接口即可
 */
interface TtsEngineApi {

    /**
     * 获取引擎 ID
     *
     * @return 引擎唯一标识符
     */
    fun getEngineId(): String

    /**
     * 获取引擎显示名称
     *
     * @return 引擎显示名称
     */
    fun getEngineName(): String

    /**
     * 检查引擎是否已配置（API Key 等）
     *
     * @param config 引擎配置
     * @return 是否已配置
     */
    fun isConfigured(config: EngineConfig): Boolean

    /**
     * 合成语音
     *
     * @param text 要合成的文本
     * @param config 引擎配置
     * @param listener 合成结果监听器
     */
    fun synthesize(
        text: String,
        config: EngineConfig,
        listener: TtsSynthesisListener
    )

    /**
     * 停止当前合成
     */
    fun stop()

    /**
     * 释放引擎资源
     */
    fun release()
}

/**
 * TTS 合成结果监听器
 *
 * 用于接收语音合成的结果状态和音频数据
 */
interface TtsSynthesisListener {

    /**
     * 合成开始
     */
    fun onSynthesisStarted()

    /**
     * 接收音频数据
     *
     * @param audioData 音频数据
     * @param sampleRate 采样率
     * @param audioFormat 音频格式
     * @param channelCount 声道数
     */
    fun onAudioAvailable(
        audioData: ByteArray,
        sampleRate: Int,
        audioFormat: Int,
        channelCount: Int
    )

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
}
