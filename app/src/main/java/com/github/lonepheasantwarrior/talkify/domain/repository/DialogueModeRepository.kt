package com.github.lonepheasantwarrior.talkify.domain.repository

import com.github.lonepheasantwarrior.talkify.domain.model.DialogueModeConfig
import kotlinx.coroutines.flow.Flow

/**
 * 对话模式配置仓储接口
 *
 * 配置经 DataStore 持久化；TTS 服务在每次合成请求前读取快照，
 * UI 通过 [config] Flow 响应式刷新。
 */
interface DialogueModeRepository {

    /** 配置流（DataStore 变更时发射新值） */
    val config: Flow<DialogueModeConfig>

    /** 读取当前配置快照（挂起直到首次读取完成） */
    suspend fun getConfig(): DialogueModeConfig

    /** 保存完整配置 */
    suspend fun save(config: DialogueModeConfig)
}
