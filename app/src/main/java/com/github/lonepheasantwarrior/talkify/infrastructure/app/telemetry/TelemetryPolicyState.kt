package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

/**
 * 一次采集/汇总/发送操作在创建时捕获的许可快照
 *
 * 只允许在**创建**业务对象时捕获，并在最终提交（入队、汇总、补报）时校验。
 * 关闭后再开启会产生新的 [epoch]，旧快照因此永久失效，不能把关闭期间产生的
 * 数据带进新会话。
 */
data class CollectionPermit(
    val enabled: Boolean,
    val epoch: Long,
)

/**
 * 遥测许可代际的纯逻辑状态（不含 Android 依赖，可单测）
 *
 * 所有读取/切换都在同一把锁内完成，保证 `capture` 得到的 enabled/epoch 是原子快照。
 */
internal class TelemetryPolicyState {

    private var enabled = false
    private var epoch = 0L

    @Synchronized
    fun initialize(enabled: Boolean) {
        this.enabled = enabled
        epoch = 0
    }

    /** 返回是否真的发生了切换；同一开关状态不推进代际 */
    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        if (this.enabled == enabled) return false
        this.enabled = enabled
        epoch++
        return true
    }

    @Synchronized
    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun epoch(): Long = epoch

    @Synchronized
    fun capture(): CollectionPermit = CollectionPermit(enabled, epoch)

    @Synchronized
    fun isCurrent(permit: CollectionPermit): Boolean =
        enabled && permit.enabled && permit.epoch == epoch

    /**
     * 在许可边界内执行提交动作
     *
     * 关闭会等待该动作结束（随后清理），因此不会出现“清理先完成、旧提交后写入”的复活。
     */
    @Synchronized
    fun <T> runIfCurrent(permit: CollectionPermit, block: () -> T): T? =
        if (isCurrent(permit)) block() else null
}
