package com.github.lonepheasantwarrior.talkify.domain.repository

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleState
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleStorageFailure
import kotlinx.coroutines.flow.Flow

/**
 * 多角色配置仓储。
 *
 * 关键纪律（见文档第 6 节）：
 * - UI 订阅 [state] 只用于展示；请求入口必须调用 [snapshot] 读取 DataStore
 *   当前值的不可变快照，不维护自行失效的进程内副本；
 * - [update] 在同一 DataStore 事务内基于当前值做读改写，禁止“读旧快照 → 整量覆盖”；
 * - 更新成功返回后 UI 才显示已生效；失败保留原配置。
 */
interface MultiRoleRepository {

    /** 配置流；损坏/未来版本时以 [MultiRoleState.failure] 暴露，不修改原文件。 */
    val state: Flow<MultiRoleState>

    /** 读取当前持久化快照（请求入口使用）。 */
    suspend fun snapshot(): MultiRoleState

    /** 原子读改写。 */
    suspend fun update(transform: (current: MultiRoleSettings) -> MultiRoleSettings): MultiRoleUpdateResult

    /**
     * 用户显式确认后的重置（用于损坏文件修复）。
     * 该操作会删除原文件；不得在加载失败时自动调用。
     */
    suspend fun reset(): MultiRoleUpdateResult
}

sealed interface MultiRoleUpdateResult {
    data class Success(val settings: MultiRoleSettings) : MultiRoleUpdateResult
    data class Failure(val failure: MultiRoleStorageFailure) : MultiRoleUpdateResult
}
