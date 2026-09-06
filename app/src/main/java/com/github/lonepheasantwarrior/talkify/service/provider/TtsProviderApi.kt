package com.github.lonepheasantwarrior.talkify.service.provider

import android.media.AudioFormat
import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig

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
 * @param language 语言类型（如 "Chinese", "English" 等，null 表示使用供应商默认值）
 */
data class SynthesisParams(
    val pitch: Float = 100.0f,
    val speechRate: Float = 100.0f,
    val volume: Float = 1.0f,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    val language: String? = null
)

/**
 * TTS 供应商 API 接口
 *
 * 定义语音合成供应商必须实现的核心方法
 * 采用接口设计，解耦供应商实现与调用方逻辑
 * 支持多供应商接入，每种供应商实现此接口即可
 */
interface TtsProviderApi {

    /**
     * 获取供应商 ID
     *
     * @return 供应商唯一标识符
     */
    fun getProviderId(): String

    /**
     * 获取供应商显示名称
     *
     * @return 供应商显示名称
     */
    fun getProviderName(): String

    /**
     * 检查供应商是否已配置（API Key 等）
     *
     * @param config 供应商配置
     * @return 是否已配置
     */
    fun isConfigured(config: BaseProviderConfig?): Boolean

    /**
     * 合成语音
     *
     * @param text 要合成的文本
     * @param params 合成参数（音调、音量、语速等）
     * @param config 供应商配置
     * @param listener 合成结果监听器
     */
    fun synthesize(
        text: String,
        params: SynthesisParams,
        config: BaseProviderConfig,
        listener: TtsSynthesisListener
    )

    /**
     * 停止当前合成
     */
    fun stop()

    /**
     * 释放供应商资源
     */
    fun release()

    /**
     * 获取供应商音频配置
     *
     * @return 音频配置，包含采样率、格式、通道数等
     */
    fun getAudioConfig(): AudioConfig

    /**
     * 获取供应商支持的语言
     *
     * @return 支持的语言代码集合
     */
    fun getSupportedLanguages(): Set<String>

    /**
     * 获取默认语言三元组 [language, country, variant]
     *
     * 对齐 Android `TextToSpeechService#onGetLanguage` 契约，返回值为三元组数组。
     */
    fun getDefaultLanguage(): Array<String>

    /**
     * 获取供应商支持的声音
     */
    fun getSupportedVoices(): List<Voice>

    /**
     * 获取供应商的默认声音ID
     */
    fun getDefaultVoiceId(lang: String?, country: String?, variant: String?, currentVoiceId: String?): String

    /**
     * 检查供应商是否支持目标声音ID（声音 ID 是否合法）
     */
    fun isVoiceIdCorrect(voiceId: String?): Boolean

    /**
     * 创建供应商的默认配置实例
     *
     * 用于创建该供应商类型的空配置对象
     * 供配置编辑界面动态创建正确的配置类型
     *
     * @return 供应商默认配置实例
     */
    fun createDefaultConfig(): BaseProviderConfig

    /**
     * 获取供应商默认 API 地址
     *
     * 用于配置编辑界面展示占位提示，以及在用户未自定义时提供回退值。
     * 若供应商不支持自定义 API 地址（如使用官方 SDK 内部端点），返回空字符串。
     *
     * @return 默认 API 地址，不支持时返回空字符串
     */
    fun getDefaultApiUrl(): String

    /**
     * 获取供应商默认模型 ID
     *
     * 用于配置编辑界面展示占位提示，以及在用户未自定义时提供回退值。
     * 若供应商不支持自定义模型 ID（如使用音色 ID 区分模型），返回空字符串。
     *
     * @return 默认模型 ID，不支持时返回空字符串
     */
    fun getDefaultModelId(): String

    /**
     * 获取配置项标签
     *
     * 用于 UI 层显示配置项的本地化标签
     * 每个供应商可以定义自己的配置项标签
     *
     * @param configKey 配置项键名
     * @param context Android Context 用于获取字符串资源
     * @return 配置项的本地化标签，若供应商不支持该配置项则返回 null
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
