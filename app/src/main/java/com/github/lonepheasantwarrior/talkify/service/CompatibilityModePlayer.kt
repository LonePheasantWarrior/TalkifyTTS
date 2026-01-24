package com.github.lonepheasantwarrior.talkify.service

import android.media.AudioFormat
import com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig
import com.github.lonepheasantwarrior.talkify.util.TalkifyAudioPlayer

/**
 * 兼容模式专用音频播放器
 *
 * 用于在兼容模式下同步播放 TTS 合成的音频数据，
 * 确保阅读软件在收到 onSynthesizeText 返回前，语音已经播放完毕。
 *
 * 核心机制：
 * - 使用 [TalkifyAudioPlayer] 进行音频播放
 * - 通过轮询播放头位置检测播放完成
 * - 支持超时控制，避免无限等待
 *
 * 使用流程：
 * 1. 实例化播放器，传入引擎音频配置
 * 2. 调用 [initialize()] 初始化播放器
 * 3. 对每个音频数据调用 [playAllAndWait]，该方法会阻塞直到播放完成
 * 4. 使用完毕后调用 [release()] 释放资源
 *
 * @param audioConfig 引擎音频配置，包含采样率、声道数、音频格式等信息
 */
class CompatibilityModePlayer(
    private val audioConfig: AudioConfig
) {
    /**
     * 内置音频播放器实例
     *
     * 用于实际执行音频播放操作
     */
    private var audioPlayer: TalkifyAudioPlayer? = null

    /**
     * 音频通道掩码
     *
     * 根据声道数转换为 AudioFormat 要求的通道掩码格式：
     * - 单声道（channelCount=1）→ CHANNEL_OUT_MONO
     * - 双声道（channelCount=2）→ CHANNEL_OUT_STEREO
     */
    private val channelMask: Int = if (audioConfig.channelCount == 1) {
        AudioFormat.CHANNEL_OUT_MONO
    } else {
        AudioFormat.CHANNEL_OUT_STEREO
    }

    /**
     * 初始化音频播放器
     *
     * 创建 [TalkifyAudioPlayer] 实例并配置音频属性和格式。
     *
     * @return 初始化是否成功
     */
    fun initialize(): Boolean {
        return try {
            audioPlayer = TalkifyAudioPlayer(
                sampleRate = audioConfig.sampleRate,
                channelCount = audioConfig.channelCount,
                audioFormat = audioConfig.audioFormat
            )

            val audioAttributes = audioPlayer!!.configureAudioAttributes()
            val audioFormat = audioPlayer!!.configureAudioFormat(
                channelMask = channelMask
            )

            audioPlayer!!.createPlayer(audioAttributes, audioFormat)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 播放音频并同步等待完成
     *
     * 此方法是兼容模式的核心，它会：
     * 1. 开始播放传入的音频数据
     * 2. 阻塞当前线程，通过轮询播放头位置检测播放进度
     * 3. 直到音频播放完毕或发生错误或超时
     * 4. 返回播放是否成功完成
     *
     * 同步等待机制确保阅读软件不会在语音播放完成前收到 onSynthesizeText 返回，
     * 从而避免阅读软件在语音未完成时就发送下一条合成请求。
     *
     * @param audioData 要播放的音频字节数据
     * @return 播放是否成功完成（超时或错误时返回 false）
     */
    fun playAllAndWait(audioData: ByteArray): Boolean {
        if (audioData.size == 0) {
            return true
        }

        val player = audioPlayer ?: return false

        val success = player.play(audioData)
        if (!success) {
            return false
        }

        return player.waitForPlaybackComplete(60)
    }

    /**
     * 释放播放器资源
     *
     * 停止当前播放并释放 [TalkifyAudioPlayer] 持有的系统资源。
     * 在服务销毁或停止时应调用此方法。
     */
    fun release() {
        try {
            audioPlayer?.stop()
            audioPlayer?.release()
        } catch (e: Exception) {
            TtsLogger.e("Error releasing compatibility player: ${e.message}", e)
        }
        audioPlayer = null
    }

    /**
     * 获取最近的播放错误
     *
     * @return 错误信息，如果无错误则返回 null
     */
    fun hasError(): String? = null
}
