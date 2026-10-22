package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * 遥测链路本地诊断计数器
 *
 * 只统计"发生了什么"，不保存 payload、令牌、URL 或个人数据。用于在用户主动查看时
 * 区分「用户关闭 / 构建未配置 / 远端停用 / 网络失败 / 服务端过滤 / 数据格式错误」，
 * 自身不做任何网络上报。
 */
object TelemetryDiagnostics {

    enum class DropReason(val key: String) {
        QUEUE_FULL("queue_full"),
        TTL_EXPIRED("ttl_expired"),
        POLICY_DISABLED("policy_disabled"),
        REMOTE_DISABLED("remote_disabled"),
        SCHEMA_REJECTED("schema_rejected"),
        SEND_FAILED("send_failed"),
    }

    private val enqueued = AtomicLong()
    private val sent = AtomicLong()
    private val dropped = LinkedHashMap<DropReason, AtomicLong>().apply {
        DropReason.entries.forEach { put(it, AtomicLong()) }
    }
    private val httpErrors = AtomicLong()
    private val filtered = AtomicLong()
    private val rejected = AtomicLong()
    private val remoteDisabledCount = AtomicLong()
    private val invalidTokenCount = AtomicLong()
    private val schemaRejected = AtomicLong()
    private val protocolErrors = AtomicLong()
    private val lastSuccessElapsedMs = AtomicLong(0)
    private val lastFailureCode = AtomicLong(0)

    internal fun recordEnqueued() {
        enqueued.incrementAndGet()
    }

    internal fun recordSent() {
        sent.incrementAndGet()
        lastSuccessElapsedMs.set(SystemClock.elapsedRealtime())
    }

    internal fun recordDrop(reason: DropReason) {
        dropped[reason]?.incrementAndGet()
        if (reason == DropReason.SCHEMA_REJECTED) schemaRejected.incrementAndGet()
    }

    internal fun recordHttpError(code: Int) {
        httpErrors.incrementAndGet()
        lastFailureCode.set(code.toLong())
    }

    internal fun recordFiltered() {
        filtered.incrementAndGet()
    }

    internal fun recordRejected() {
        rejected.incrementAndGet()
    }

    internal fun recordRemoteDisabled() {
        remoteDisabledCount.incrementAndGet()
    }

    internal fun recordInvalidToken() {
        invalidTokenCount.incrementAndGet()
    }

    /** 2xx 但响应不符合已确认部署契约（空体/HTML/畸形 JSON/字段类型错误/超限） */
    internal fun recordProtocolError() {
        protocolErrors.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        enqueued = enqueued.get(),
        sent = sent.get(),
        droppedQueueFull = dropped.getValue(DropReason.QUEUE_FULL).get(),
        droppedTtl = dropped.getValue(DropReason.TTL_EXPIRED).get(),
        droppedPolicy = dropped.getValue(DropReason.POLICY_DISABLED).get(),
        droppedRemoteDisabled = dropped.getValue(DropReason.REMOTE_DISABLED).get(),
        droppedSendFailed = dropped.getValue(DropReason.SEND_FAILED).get(),
        httpErrors = httpErrors.get(),
        filtered = filtered.get(),
        rejected = rejected.get(),
        remoteDisabled = remoteDisabledCount.get(),
        invalidToken = invalidTokenCount.get(),
        schemaRejected = schemaRejected.get(),
        protocolErrors = protocolErrors.get(),
        lastSuccessElapsedMs = lastSuccessElapsedMs.get(),
        lastHttpCode = lastFailureCode.get(),
    )

    /** 面向用户展示的本地诊断文本（不含任何 payload/令牌/URL） */
    fun format(): String {
        val s = snapshot()
        val (queueCount, queueBytes) = UmamiClient.queueSnapshot()
        val lastSuccess = if (s.lastSuccessElapsedMs == 0L) {
            "无"
        } else {
            val ageSec = (SystemClock.elapsedRealtime() - s.lastSuccessElapsedMs) / 1000
            "${ageSec}s 前"
        }
        return buildString {
            appendLine("遥测构建配置：${UmamiClient.configState.label}")
            appendLine("用户开关：${if (TelemetryController.isTelemetryEnabled()) "开启" else "关闭"}")
            appendLine("界面诊断：${if (TelemetryController.isDiagnosticsEnabled()) "开启" else "关闭"}")
            appendLine("远端状态：${if (UmamiClient.isRemoteDisabled()) "已停用" else "正常"}")
            appendLine("待发队列：$queueCount 条 / $queueBytes 字节（上限 256 条 / 512KiB）")
            appendLine("入队：${s.enqueued}，成功：${s.sent}，最近成功：$lastSuccess")
            appendLine("丢弃：队列满 ${s.droppedQueueFull}，过期 ${s.droppedTtl}，许可关闭 ${s.droppedPolicy}，远端停用 ${s.droppedRemoteDisabled}，发送失败 ${s.droppedSendFailed}")
            appendLine("HTTP 错误：${s.httpErrors}（最近 ${s.lastHttpCode}）")
            appendLine("服务端过滤：${s.filtered}，明确拒绝：${s.rejected}，远端停用响应：${s.remoteDisabled}，无效令牌：${s.invalidToken}")
            appendLine("协议错误：${s.protocolErrors}（响应不可确认，不计成功）")
            append("Schema 拒绝：${s.schemaRejected}")
        }
    }

    internal fun resetForTest() {
        enqueued.set(0)
        sent.set(0)
        dropped.values.forEach { it.set(0) }
        httpErrors.set(0)
        filtered.set(0)
        rejected.set(0)
        remoteDisabledCount.set(0)
        invalidTokenCount.set(0)
        schemaRejected.set(0)
        protocolErrors.set(0)
        lastSuccessElapsedMs.set(0)
        lastFailureCode.set(0)
    }

    data class Snapshot(
        val enqueued: Long,
        val sent: Long,
        val droppedQueueFull: Long,
        val droppedTtl: Long,
        val droppedPolicy: Long,
        val droppedRemoteDisabled: Long,
        val droppedSendFailed: Long,
        val httpErrors: Long,
        val filtered: Long,
        val rejected: Long,
        val remoteDisabled: Long,
        val invalidToken: Long,
        val schemaRejected: Long,
        val protocolErrors: Long,
        val lastSuccessElapsedMs: Long,
        val lastHttpCode: Long,
    )
}
