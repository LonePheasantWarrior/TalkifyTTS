package com.github.lonepheasantwarrior.talkify.service.engine

import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig

/**
 * TTS 合成参数
 *
 * 封装来自 Android 系统的合成参数
 * 包括音调、音量、语速等语音属性
 *
 * @param pitch 音调，范围 [0, 200]，100 为默认值（正常音调）
 * @param speechRate 语速，范围 [0, 200]，100 为默认值（正常语速），值越大语速越快
 * @param volume 音量，范围 [0.0, 1.0]，1.0 为默认值（最大音量）
 * @param audioFormat 音频格式（如 AudioFormat.ENCODING_PCM_16BIT）
 * @param language 语言类型（如 "Chinese", "English" 等，null 表示使用引擎默认值）
 */
data class SynthesisParams(
    val pitch: Float = 100.0f,
    val speechRate: Float = 100.0f,
    val volume: Float = 1.0f,
    val audioFormat: Int = 2,
    val language: String? = null
)

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
    fun isConfigured(config: BaseEngineConfig?): Boolean

    /**
     * 合成语音
     *
     * @param text 要合成的文本
     * @param params 合成参数（音调、音量、语速等）
     * @param config 引擎配置
     * @param listener 合成结果监听器
     */
    fun synthesize(
        text: String,
        params: SynthesisParams,
        config: BaseEngineConfig,
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

    /**
     * 获取引擎音频配置
     *
     * @return 音频配置，包含采样率、格式、通道数等
     */
    fun getAudioConfig(): AudioConfig

    /**
     * 获取引擎支持的语言
     *
     * @return 支持的语言代码集合
     */
    fun getSupportedLanguages(): Set<String>

    /**
     * 获取引擎支持的默认语言
     */
    fun getDefaultLanguages(): Array<String>

    /**
     * 获取引擎支持的声音
     */
    fun getSupportedVoices(): List<Voice>

    /**
     * 获取引擎的默认声音ID
     */
    fun getDefaultVoiceId(lang: String?, country: String?, variant: String?, currentVoiceId: String?): String

    /**
     * 检查引擎是否支持目标声音ID（声音 ID 是否合法）
     */
    fun isVoiceIdCorrect(voiceId: String?): Boolean

    /**
     * 创建引擎的默认配置实例
     *
     * 用于创建该引擎类型的空配置对象
     * 供配置编辑界面动态创建正确的配置类型
     *
     * @return 引擎默认配置实例
     */
    fun createDefaultConfig(): BaseEngineConfig

    /**
     * 获取配置项标签
     *
     * 用于 UI 层显示配置项的本地化标签
     * 每个引擎可以定义自己的配置项标签
     *
     * @param configKey 配置项键名
     * @param context Android Context 用于获取字符串资源
     * @return 配置项的本地化标签，若引擎不支持该配置项则返回 null
     */
    fun getConfigLabel(configKey: String, context: android.content.Context): String?
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
