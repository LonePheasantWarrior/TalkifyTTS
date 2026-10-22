package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.os.Build
import android.os.SystemClock
import com.github.lonepheasantwarrior.talkify.BuildConfig
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Umami 传输层（协议适配 + 有界发送）
 *
 * 责任边界：
 * - **许可代际**：入队、Call 登记、响应回写都携带捕获时的 [CollectionPermit]；关闭许可
 *   与取消已登记 Call 在同一串行边界内完成，关闭返回后旧请求不会再启动，
 *   旧响应也不能写入新代际
 * - **有界队列**：256 条 / 512KiB / 60s TTL（单调时钟），单 worker 顺序发送；
 *   资源不足丢弃遥测，绝不阻塞业务线程
 * - **会话一致性**：基础事件的响应按 FIFO 顺序回写会话令牌；录制缓冲绑定创建时的
 *   不可变会话上下文，令牌轮换后旧缓冲自动失效
 * - **协议识别**：只有符合已确认部署契约的 2xx JSON 才算成功；空体/HTML/畸形 JSON/
 *   字段类型错误不更新缓存、不增加成功计数；400 先判断是否真的携带令牌
 * - **限流**：完整解析 `Retry-After`（秒数与 HTTP-date），站点级闸门约束后续事件
 *
 * Umami 的 pageview（不带 `name`）驱动访客/浏览量；自定义事件只进入 Events 区域。
 *
 * @see TelemetryController
 * @see TelemetryQueue
 */
object UmamiClient {

    private const val TAG = "TalkifyTelemetry"

    enum class ConfigState(val label: String) {
        NONE("未配置"),
        INVALID("配置非法"),
        VALID("正常"),
    }

    private const val CALL_TIMEOUT_SECONDS = 10L
    private const val MAX_RESPONSE_BYTES = 16 * 1024

    private val rawEndpoint: String = BuildConfig.UMAMI_ENDPOINT.trim()
    private val rawWebsiteId: String = BuildConfig.UMAMI_WEBSITE_ID.trim()

    val BASE_URL: String = TelemetryConfigValidator.normalizeEndpoint(rawEndpoint)
    val WEBSITE_ID: String = rawWebsiteId

    const val HOSTNAME = "com.github.lonepheasantwarrior.talkify"

    val configState: ConfigState = when (TelemetryConfigValidator.validate(rawEndpoint, rawWebsiteId)) {
        TelemetryConfigValidator.Result.VALID -> ConfigState.VALID
        TelemetryConfigValidator.Result.INVALID -> ConfigState.INVALID
        TelemetryConfigValidator.Result.NONE -> ConfigState.NONE
    }

    val isConfigured: Boolean = configState == ConfigState.VALID

    private val sessionState = UmamiSessionState()
    private val queue = TelemetryQueue()
    private val workerLock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "talkify-telemetry").apply { isDaemon = true }
    }

    @Volatile
    private var policyEnabled = false

    @Volatile
    private var policyEpoch = 0L

    @Volatile
    private var workerRunning = false

    @Volatile
    private var activeCall: Call? = null

    /** 远端停用回调（由控制器注册，用于同步停止录制通道） */
    @Volatile
    var onRemoteDisabled: (() -> Unit)? = null

    // ==================== 测试注入点（生产路径保持默认值） ====================

    internal class SendTestHooks(
        var beforeRegister: (() -> Unit)? = null,
        var afterRegister: (() -> Unit)? = null,
        var beforeExecute: (() -> Unit)? = null,
        var afterParse: (() -> Unit)? = null,
        var beforeApply: (() -> Unit)? = null,
    )

    @Volatile
    internal var testHooks: SendTestHooks? = null

    @Volatile
    internal var callFactory: ((Request) -> Call)? = null

    @Volatile
    internal var configOverride: Boolean? = null

    @Volatile
    internal var effectiveBaseUrl: String = BASE_URL

    @Volatile
    internal var effectiveWebsiteId: String = WEBSITE_ID

    @Volatile
    internal var permitGate: (CollectionPermit) -> Boolean = {
        TelemetryController.isPermitCurrent(it)
    }

    @Volatile
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }

    @Volatile
    internal var wallClock: () -> Long = System::currentTimeMillis

    @Volatile
    internal var monotonicClock: () -> Long = { SystemClock.elapsedRealtime() }

    private val retryGate = RetryGate { monotonicClock() }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 遥测不跟随重定向：避免令牌/事件被导向预期服务边界之外的地址
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val userAgent: String by lazy {
        // OkHttp 请求头必须是可见 ASCII；个别 OEM 的 Build.MODEL 含非 ASCII 字符，
        // 直接拼入会导致请求构造失败、全部遥测静默丢弃
        val model = Build.MODEL.orEmpty().map { char ->
            if (char.code in 0x20..0x7E) char else '_'
        }.joinToString("")
        "Talkify/${BuildConfig.VERSION_NAME.ifBlank { "unknown" }} " +
            "(Linux; Android ${Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" }}; $model)"
    }

    // ==================== 门面入口 ====================

    fun track(
        eventName: String,
        properties: Map<String, Any>,
        url: String = "/",
        origin: String = TelemetryOrigin.UI,
        permit: CollectionPermit? = null,
    ): Boolean = enqueue(
        TelemetryEvent(name = eventName, url = url, origin = origin, data = properties),
        permit,
    )

    fun trackPage(url: String, title: String? = null, permit: CollectionPermit? = null): Boolean =
        enqueue(TelemetryEvent(name = null, url = url, title = title), permit)

    fun identify(properties: Map<String, Any>, permit: CollectionPermit? = null): Boolean =
        enqueue(TelemetryEvent(type = "identify", name = null, url = "/", data = properties), permit)

    fun userAgent(): String = userAgent

    fun sessionToken(): String? = sessionState.currentToken()

    internal fun recordSession(): UmamiSessionState.RecordSession? = sessionState.recordSession(
        effectiveWebsiteId,
        policyEnabled && configured(),
    )

    internal fun isRecordSessionCurrent(session: UmamiSessionState.RecordSession): Boolean =
        sessionState.isRecordSessionCurrent(session, policyEnabled && configured())

    fun isRemoteDisabled(): Boolean = sessionState.isRemoteDisabled()

    /** 本地诊断用：当前待发条数与估算字节 */
    internal fun queueSnapshot(): Pair<Int, Int> = queue.size() to queue.byteSize()

    /**
     * 应用许可变更（由 [TelemetryController] 调用）
     *
     * “登记新 Call 前的许可复核”和“关闭时取消已登记 Call”都在 [workerLock] 内完成：
     * - 若关闭先完成，发送线程后续登记会看到旧 epoch 并放弃执行
     * - 若登记先完成，关闭会在返回前取消该 Call，execute 随后只能得到取消异常
     */
    fun setPolicy(enabled: Boolean, epoch: Long) {
        synchronized(workerLock) {
            policyEnabled = enabled
            policyEpoch = epoch
            sessionState.setPolicy(epoch, enabled)
            if (!enabled) {
                queue.clear()
                activeCall?.cancel()
                activeCall = null
                // 站点级限流不因本地许可切换而遗忘：重新开启仍须尊重服务端 Retry-After
            }
        }
    }

    /** 每次回到前台的受限探测：允许复核一次远端停用状态（低频，不额外造请求） */
    fun onForegroundProbe() {
        if (!policyEnabled || !configured()) return
        sessionState.beginForegroundProbe(policyEpoch)
    }

    /**
     * 录制通道响应处理：只关心停用与过滤，不把 `/api/record` 的 cache 用于基础会话轮换
     */
    internal fun handleRecordResponse(statusCode: Int, body: LimitedBody) {
        if (statusCode !in 200..299) {
            TelemetryDiagnostics.recordHttpError(statusCode)
            return
        }
        if (!policyEnabled) return
        val text = body as? LimitedBody.Text
        if (text == null) {
            TelemetryDiagnostics.recordProtocolError()
            return
        }
        val json = try {
            JSONObject(text.value)
        } catch (_: Exception) {
            TelemetryDiagnostics.recordProtocolError()
            return
        }
        val beep = json.opt("beep")
        if (beep != null) {
            TelemetryDiagnostics.recordFiltered()
            return
        }
        val ok = if (json.has("ok")) json.opt("ok") else null
        if (ok != null && ok !is Boolean) {
            TelemetryDiagnostics.recordProtocolError()
            return
        }
        if (ok == false) {
            val reason = json.opt("reason")
            val disabled = reason is String && reason.contains("disabled", ignoreCase = true)
            if (disabled) {
                applyRemoteDisabled()
            } else {
                TelemetryDiagnostics.recordRejected()
            }
        }
    }

    // ==================== 入队与发送 ====================

    private fun enqueue(event: TelemetryEvent, permit: CollectionPermit?): Boolean {
        val effectivePermit = permit ?: TelemetryController.capturePermit()
        return enqueueInternal(event, effectivePermit, startWorker = true)
    }

    internal fun enqueueForTest(event: TelemetryEvent, permit: CollectionPermit): Boolean =
        enqueueInternal(event, permit, startWorker = false)

    internal fun drainOnceForTest(): Boolean {
        val entry = queue.poll {
            TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.TTL_EXPIRED)
        } ?: return false
        processWithRetry(entry)
        return true
    }

    private fun enqueueInternal(
        event: TelemetryEvent,
        permit: CollectionPermit,
        startWorker: Boolean,
    ): Boolean {
        try {
            if (!configured()) return false
            // 许可校验先于锁，避免持有发送锁时反向获取控制器锁
            if (!permit.enabled || !permitGate(permit)) {
                TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                return false
            }
            val prepared = TelemetrySchema.prepare(event)
            if (prepared == null) {
                TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.SCHEMA_REJECTED)
                return false
            }
            var reason: TelemetryDiagnostics.DropReason? = null
            val accepted = synchronized(workerLock) {
                when {
                    !policyEnabled || permit.epoch != policyEpoch -> {
                        reason = TelemetryDiagnostics.DropReason.POLICY_DISABLED
                        false
                    }
                    sessionState.isRemoteDisabled() -> {
                        reason = TelemetryDiagnostics.DropReason.REMOTE_DISABLED
                        false
                    }
                    !queue.offer(prepared, permit.epoch) -> {
                        reason = TelemetryDiagnostics.DropReason.QUEUE_FULL
                        false
                    }
                    else -> true
                }
            }
            if (!accepted) {
                reason?.let { TelemetryDiagnostics.recordDrop(it) }
                return false
            }
            TelemetryDiagnostics.recordEnqueued()
            if (startWorker) ensureWorker()
            return true
        } catch (e: Throwable) {
            // 遥测绝不向业务线程抛出异常
            TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.SEND_FAILED)
            TtsLogger.w(TAG) { "遥测入队异常: ${e.javaClass.simpleName}" }
            return false
        }
    }

    private fun ensureWorker() {
        synchronized(workerLock) {
            if (workerRunning) return
            workerRunning = true
            worker.execute { drainLoop() }
        }
    }

    private fun drainLoop() {
        try {
            while (true) {
                val entry = queue.poll {
                    TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.TTL_EXPIRED)
                }
                if (entry == null) {
                    synchronized(workerLock) {
                        if (queue.isEmpty()) {
                            workerRunning = false
                            return
                        }
                    }
                    continue
                }
                try {
                    processWithRetry(entry)
                } catch (e: Throwable) {
                    // 单事件处理异常不得杀死发送 worker（否则后续遥测永久静默）
                    TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.SEND_FAILED)
                    TtsLogger.w(TAG) { "遥测事件处理异常: ${e.javaClass.simpleName}" }
                }
            }
        } catch (e: Throwable) {
            // 队列/调度层异常：释放 running 标记，下一条事件会重新拉起 worker
            synchronized(workerLock) { workerRunning = false }
            TtsLogger.w(TAG) { "遥测 worker 异常退出: ${e.javaClass.simpleName}" }
        }
    }

    private fun processWithRetry(entry: TelemetryQueue.Entry) {
        var attempt = 0
        while (true) {
            if (!isPolicyCurrent(entry)) {
                TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                return
            }
            if (queue.isExpired(entry)) {
                TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.TTL_EXPIRED)
                return
            }
            val gateWait = retryGate.remainingMs()
            if (gateWait > 0) {
                if (queue.remainingTtl(entry) <= gateWait) {
                    TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.TTL_EXPIRED)
                    return
                }
                if (!sleepWhileCurrent(gateWait, entry)) {
                    TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                    return
                }
            }
            val result = execute(entry)
            testHooks?.afterParse?.invoke()
            when (result) {
                is SendResult.Success -> {
                    if (!applySuccess(entry, result.response)) {
                        TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                    }
                    return
                }
                is SendResult.Filtered -> {
                    if (policyMatches(entry)) TelemetryDiagnostics.recordFiltered()
                    else TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                    return
                }
                is SendResult.Rejected -> {
                    if (policyMatches(entry)) TelemetryDiagnostics.recordRejected()
                    else TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                    return
                }
                is SendResult.ProtocolError -> {
                    if (policyMatches(entry)) TelemetryDiagnostics.recordProtocolError()
                    else TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                    return
                }
                is SendResult.Dropped -> {
                    TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.SEND_FAILED)
                    return
                }
                is SendResult.PolicyDropped -> {
                    TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                    return
                }
                is SendResult.RemoteDisabled -> {
                    if (!applyRemoteDisabled(entry)) {
                        TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                    }
                    return
                }
                is SendResult.InvalidToken -> {
                    if (!applyInvalidToken(entry)) {
                        TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                        return
                    }
                    TelemetryDiagnostics.recordInvalidToken()
                    if (entry.attempts == 0) {
                        entry.attempts++
                        continue
                    }
                    TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.SEND_FAILED)
                    return
                }
                is SendResult.Retryable -> {
                    val waitMs = result.retryAfterMs ?: backoffMs(attempt)
                    // 站点冷却与当前事件是否继续重试是两件事：先记录期限，
                    // 即使本次已耗尽重试预算，后续事件也不能提前发送
                    retryGate.deferFor(waitMs)
                    if (attempt >= MAX_RETRIES || entry.attempts >= MAX_RETRIES) {
                        TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.SEND_FAILED)
                        return
                    }
                    // 完整等待会超过事件 TTL：丢弃而不是提前重试；闸门仍约束后续事件
                    if (queue.remainingTtl(entry) <= waitMs) {
                        TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.TTL_EXPIRED)
                        return
                    }
                    if (!sleepWhileCurrent(waitMs, entry)) {
                        TelemetryDiagnostics.recordDrop(TelemetryDiagnostics.DropReason.POLICY_DISABLED)
                        return
                    }
                    attempt++
                    entry.attempts++
                }
            }
        }
    }

    private fun execute(entry: TelemetryQueue.Entry): SendResult {
        val request = try {
            buildRequest(entry.event)
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "遥测请求构造失败: ${e.message}" }
            return SendResult.Dropped
        }
        val call = try {
            callFactory?.invoke(request) ?: httpClient.newCall(request)
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "遥测 Call 创建失败: ${e.message}" }
            return SendResult.Dropped
        }
        testHooks?.beforeRegister?.invoke()
        val registered = synchronized(workerLock) {
            if (!isPolicyCurrentLocked(entry)) {
                false
            } else {
                activeCall = call
                true
            }
        }
        if (!registered) {
            call.cancel()
            return SendResult.PolicyDropped
        }
        testHooks?.afterRegister?.invoke()
        return try {
            testHooks?.beforeExecute?.invoke()
            call.execute().use { response ->
                val body = readLimitedBody(response, MAX_RESPONSE_BYTES)
                val tokenPresent = request.header("x-umami-cache") != null
                when {
                    response.code == 400 -> {
                        TelemetryDiagnostics.recordHttpError(response.code)
                        classify400(body, tokenPresent)
                    }
                    response.code == 429 -> {
                        TelemetryDiagnostics.recordHttpError(response.code)
                        SendResult.Retryable(RetryAfter.parseMillis(response.header("Retry-After"), wallClock()))
                    }
                    response.code in 500..599 -> {
                        TelemetryDiagnostics.recordHttpError(response.code)
                        SendResult.Retryable(RetryAfter.parseMillis(response.header("Retry-After"), wallClock()))
                    }
                    !response.isSuccessful -> {
                        TelemetryDiagnostics.recordHttpError(response.code)
                        SendResult.Rejected
                    }
                    else -> classifyBase(body)
                }
            }
        } catch (e: IOException) {
            if (!isPolicyCurrent(entry)) SendResult.PolicyDropped else SendResult.Retryable(null)
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "遥测发送异常: ${e.javaClass.simpleName}" }
            SendResult.Retryable(null)
        } finally {
            synchronized(workerLock) {
                if (activeCall === call) activeCall = null
            }
        }
    }

    private fun classifyBase(body: LimitedBody): SendResult = when (val parsed = parseBaseResponse(body)) {
        is ParsedResponse.Success -> SendResult.Success(parsed)
        ParsedResponse.Filtered -> SendResult.Filtered
        ParsedResponse.Rejected -> SendResult.Rejected
        ParsedResponse.RemoteDisabled -> SendResult.RemoteDisabled
        ParsedResponse.ProtocolError -> SendResult.ProtocolError
    }

    /**
     * 400 先判断请求是否真的携带令牌，再按错误文本判断是否为令牌失效。
     * 请求格式错误、站点不存在等 400 不能靠换令牌修复。
     */
    private fun classify400(body: LimitedBody, tokenPresent: Boolean): SendResult {
        if (!tokenPresent) return SendResult.Rejected
        val text = (body as? LimitedBody.Text)?.value?.lowercase(Locale.ROOT) ?: return SendResult.Rejected
        val tokenRelated = text.contains("token") || text.contains("cache") || text.contains("session")
        val invalid = listOf("invalid", "expire", "missing", "unknown", "not found", "bad")
            .any { text.contains(it) }
        return if (tokenRelated && invalid) SendResult.InvalidToken else SendResult.Rejected
    }

    private fun applySuccess(entry: TelemetryQueue.Entry, response: ParsedResponse.Success): Boolean {
        testHooks?.beforeApply?.invoke()
        return synchronized(workerLock) {
            if (!policyMatchesLocked(entry)) {
                false
            } else if (!sessionState.applyResponse(response.cache, disabled = false, entry.policyEpoch)) {
                false
            } else {
                TelemetryDiagnostics.recordSent()
                true
            }
        }
    }

    private fun applyRemoteDisabled(entry: TelemetryQueue.Entry? = null): Boolean {
        testHooks?.beforeApply?.invoke()
        val applied = synchronized(workerLock) {
            val expected = entry?.policyEpoch ?: policyEpoch
            if (entry != null && !policyMatchesLocked(entry)) {
                false
            } else if (!sessionState.setRemoteDisabled(expected)) {
                false
            } else {
                queue.clear()
                true
            }
        }
        if (applied) {
            TelemetryDiagnostics.recordRemoteDisabled()
            onRemoteDisabled?.invoke()
        }
        return applied
    }

    private fun applyInvalidToken(entry: TelemetryQueue.Entry): Boolean {
        testHooks?.beforeApply?.invoke()
        return synchronized(workerLock) {
            policyMatchesLocked(entry) && sessionState.onInvalidToken(entry.policyEpoch)
        }
    }

    private fun policyMatches(entry: TelemetryQueue.Entry): Boolean =
        policyEnabled && entry.policyEpoch == policyEpoch &&
            sessionState.policyEpoch() == policyEpoch

    private fun isPolicyCurrent(entry: TelemetryQueue.Entry): Boolean =
        policyMatches(entry) && !sessionState.isRemoteDisabled()

    private fun isPolicyCurrentLocked(entry: TelemetryQueue.Entry): Boolean =
        policyMatches(entry) && !sessionState.isRemoteDisabled()

    private fun policyMatchesLocked(entry: TelemetryQueue.Entry): Boolean =
        policyEnabled && entry.policyEpoch == policyEpoch &&
            sessionState.policyEpoch() == policyEpoch

    private fun sleepWhileCurrent(totalMs: Long, entry: TelemetryQueue.Entry): Boolean {
        var remaining = totalMs.coerceAtLeast(0)
        while (remaining > 0) {
            if (!isPolicyCurrent(entry)) return false
            val slice = min(remaining, 100L)
            try {
                sleeper(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return isPolicyCurrent(entry)
    }

    private fun backoffMs(attempt: Int): Long {
        val base = 500L * (1L shl attempt)
        val jitter = Random.nextLong(0, 250)
        return min(base + jitter, MAX_BACKOFF_WAIT_MS)
    }

    // ==================== 协议组装 ====================

    private fun configured(): Boolean = configOverride ?: isConfigured

    private fun buildRequest(event: TelemetryEvent): Request {
        val payload = JSONObject().apply {
            put("website", effectiveWebsiteId)
            put("hostname", HOSTNAME)
            put("language", Locale.getDefault().toLanguageTag())
            put("referrer", "")
            put("screen", screenSize())
            put("url", event.url)
            event.title?.let { put("title", it) }
            if (event.type == "event" && event.name != null) {
                put("name", event.name)
                put("data", JSONObject(event.data))
            } else if (event.type == "identify" && event.data.isNotEmpty()) {
                put("data", JSONObject(event.data))
            }
        }
        val body = JSONObject().put("type", event.type).put("payload", payload).toString()
        val token = sessionState.currentToken()
        return Request.Builder()
            .url("$effectiveBaseUrl/api/send")
            .header("User-Agent", userAgent)
            .header("x-umami-website-id", effectiveWebsiteId)
            .header("x-umami-hostname", HOSTNAME)
            .apply { token?.let { header("x-umami-cache", it) } }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun screenSize(): String {
        val metrics = TalkifyAppHolder.getContext()?.resources?.displayMetrics ?: return "0x0"
        return "${metrics.widthPixels}x${metrics.heightPixels}"
    }

    private sealed interface ParsedResponse {
        data class Success(val cache: String) : ParsedResponse
        data object Filtered : ParsedResponse
        data object Rejected : ParsedResponse
        data object RemoteDisabled : ParsedResponse
        data object ProtocolError : ParsedResponse
    }

    /**
     * 已确认部署契约的 2xx 响应：
     * - `{beep:"boop"}`：机器人过滤
     * - `{ok:false, reason}`：明确拒绝；reason 含 disabled 时覆盖全部通道
     * - `{disabled:true}`：远端停用
     * - 其余必须是带非空字符串 `cache` 的 JSON 对象，否则按协议错误处理
     */
    private fun parseBaseResponse(body: LimitedBody): ParsedResponse {
        val text = when (body) {
            is LimitedBody.Text -> body.value
            LimitedBody.Absent, LimitedBody.TooLarge -> return ParsedResponse.ProtocolError
        }
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return ParsedResponse.ProtocolError
        }
        if (json.has("beep")) {
            return if (json.opt("beep") is String) ParsedResponse.Filtered else ParsedResponse.ProtocolError
        }
        if (json.has("ok")) {
            val ok = json.opt("ok")
            if (ok !is Boolean) return ParsedResponse.ProtocolError
            if (!ok) {
                val reason = if (json.has("reason")) json.opt("reason") else null
                if (reason != null && reason !is String) return ParsedResponse.ProtocolError
                return if (reason is String && reason.contains("disabled", ignoreCase = true)) {
                    ParsedResponse.RemoteDisabled
                } else {
                    ParsedResponse.Rejected
                }
            }
        }
        if (json.has("disabled")) {
            val disabled = json.opt("disabled")
            if (disabled !is Boolean) return ParsedResponse.ProtocolError
            if (disabled) return ParsedResponse.RemoteDisabled
        }
        val cache = json.opt("cache")
        if (cache !is String || cache.isBlank()) return ParsedResponse.ProtocolError
        return ParsedResponse.Success(cache)
    }

    private sealed interface SendResult {
        data class Success(val response: ParsedResponse.Success) : SendResult
        data object Filtered : SendResult
        data object Rejected : SendResult
        data object RemoteDisabled : SendResult
        data object InvalidToken : SendResult
        data object ProtocolError : SendResult
        data object Dropped : SendResult
        data object PolicyDropped : SendResult
        data class Retryable(val retryAfterMs: Long?) : SendResult
    }

    private const val MAX_RETRIES = 2
    private const val MAX_BACKOFF_WAIT_MS = 10_000L

    /** 仅供单测切断网络副作用 */
    internal fun resetForTest() {
        synchronized(workerLock) {
            policyEnabled = false
            policyEpoch = 0
            workerRunning = false
            queue.clear()
            sessionState.setPolicy(0, false)
            activeCall = null
        }
        retryGate.reset()
        configOverride = null
        effectiveBaseUrl = BASE_URL
        effectiveWebsiteId = WEBSITE_ID
        testHooks = null
        callFactory = null
        permitGate = { TelemetryController.isPermitCurrent(it) }
        sleeper = { Thread.sleep(it) }
        wallClock = System::currentTimeMillis
        monotonicClock = { SystemClock.elapsedRealtime() }
    }
}
