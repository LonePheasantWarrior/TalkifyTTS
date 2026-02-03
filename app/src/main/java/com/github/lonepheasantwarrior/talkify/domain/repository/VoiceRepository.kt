package com.github.lonepheasantwarrior.talkify.domain.repository

import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine

/**
 * 声音信息数据类
 *
 * @param voiceId 声音唯一标识符，用于 API 调用
 * @param displayName 声音中文显示名称，用于 UI 展示
 */
data class VoiceInfo(
    val voiceId: String,
    val displayName: String
)

/**
 * 声音仓储接口
 *
 * 定义获取可用声音列表的标准方法
 * 采用接口设计，便于后续接入更多 TTS 引擎服务
 */
interface VoiceRepository {
    /**
     * 根据引擎获取可用的声音列表
     *
     * @param engine TTS 引擎
     * @return 可用的声音列表
     */
    suspend fun getVoicesForEngine(engine: TtsEngine): List<VoiceInfo>
}
