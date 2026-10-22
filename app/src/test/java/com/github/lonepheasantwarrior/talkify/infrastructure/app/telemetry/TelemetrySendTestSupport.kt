package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import java.io.IOException

internal class FakeCall(
    private val request: Request,
    private val responder: (executionIndex: Int) -> Response,
) : Call {

    @Volatile
    var executeCount = 0

    @Volatile
    var canceled = false

    override fun request(): Request = request

    override fun execute(): Response {
        executeCount++
        if (canceled) throw IOException("canceled")
        return responder(executeCount)
    }

    override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()

    override fun isExecuted(): Boolean = executeCount > 0

    override fun isCanceled(): Boolean = canceled

    override fun cancel() {
        canceled = true
    }

    override fun timeout(): Timeout = Timeout()

    override fun clone(): Call = FakeCall(request, responder)
}

internal fun fakeResponse(
    request: Request,
    code: Int,
    body: String?,
    retryAfter: String? = null,
): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message("fake")
    .apply { retryAfter?.let { header("Retry-After", it) } }
    .body(body.orEmpty().toResponseBody("application/json".toMediaType()))
    .build()

/** 手动窗口调度器：测试中显式触发到期回调，不依赖真实 5 分钟等待 */
internal class ManualWindowScheduler : WindowScheduler {

    private class Task(
        val delayMs: Long,
        val action: () -> Unit,
    ) : WindowTimer {
        @Volatile
        var canceled = false

        override fun cancel() {
            canceled = true
        }
    }

    private val tasks = mutableListOf<Task>()

    val scheduledDelays: List<Long>
        @Synchronized get() = tasks.map { it.delayMs }

    @Synchronized
    override fun schedule(delayMs: Long, task: () -> Unit): WindowTimer =
        Task(delayMs, task).also { tasks.add(it) }

    @Synchronized
    fun fireActive() {
        val active = tasks.filter { !it.canceled }
        tasks.clear()
        active.forEach { it.action() }
    }

    @Synchronized
    fun activeCount(): Int = tasks.count { !it.canceled }
}

/**
 * 可交错测试的许可边界
 *
 * [beforeCommit] 在取得许可锁前执行（模拟尚未开始的提交），[afterCheck] 在持有许可锁
 * 且确认许可当前后执行（模拟已通过校验、提交尚未落地），两者都可用于确定性地制造
 * “旧提交恢复”与关闭/重开的交错。
 */
internal class FakePermitCommitBoundary(initialEpoch: Long = 1) : PermitCommitBoundary {

    private val policyLock = Any()

    @Volatile
    var enabled = true

    @Volatile
    var epoch = initialEpoch

    @Volatile
    var beforeCommit: (() -> Unit)? = null

    @Volatile
    var afterCheck: (() -> Unit)? = null

    override fun isCurrent(permit: CollectionPermit): Boolean =
        permit.enabled && enabled && permit.epoch == epoch

    override fun commitIfCurrent(permit: CollectionPermit, block: () -> Unit): Boolean {
        beforeCommit?.invoke()
        synchronized(policyLock) {
            if (!isCurrent(permit)) return false
            afterCheck?.invoke()
            block()
            return true
        }
    }

    override fun currentPermit(): CollectionPermit = synchronized(policyLock) {
        CollectionPermit(enabled, epoch)
    }

    /** 与提交共用许可锁：提交进行中时切换必须等待 */
    fun switchTo(newEpoch: Long, newEnabled: Boolean = true) {
        synchronized(policyLock) {
            epoch = newEpoch
            enabled = newEnabled
        }
    }
}
