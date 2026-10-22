package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TelemetryController
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TelemetryDiagnostics
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.UmamiClient
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.UmamiSessionState
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.readLimitedBody
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 录制子系统门面（会话回放 & 热图）
 *
 * **当前版本整体停用**（[RECORDING_IMPLEMENTATION_ENABLED] = false）：原生回放尚未通过
 * 隐私、播放器与时间轴联调验收，客户端不发起配置探测、不采语义树、不缓存轨迹，
 * 也不产生 `/api/record` 流量。界面诊断许可仍独立保存（默认关闭），为后续受控恢复保留。
 *
 * 恢复顺序：先完成 U01（默认拒绝的文本策略/strict/子树脱敏）与 U09/U10 的验收，
 * 再打开总开关；Replay 另有独立开关 [REPLAY_IMPLEMENTATION_ENABLED]。
 *
 * 许可边界：`stop(flush=false)` 禁止任何冲刷，定时器、在途快照、滚动防抖与轨迹缓冲
 * 全部配对结束；录制缓冲绑定创建时的不可变会话上下文，令牌轮换后自动失效。
 */
object UmamiRecorder {

    private const val TAG = "TalkifyTelemetry"

    /** 录制能力总开关：未完成隐私与播放器验收前必须保持 false */
    private const val RECORDING_IMPLEMENTATION_ENABLED = false

    /** Replay 独立开关：U01/U10 验收通过前必须保持 false */
    private const val REPLAY_IMPLEMENTATION_ENABLED = false

    /** 界面诊断能力是否可用（供设置页展示；总开关关闭时为 false） */
    val isAvailable: Boolean = RECORDING_IMPLEMENTATION_ENABLED

    private const val CACHE_WAIT_ATTEMPTS = 50
    private const val CACHE_WAIT_INTERVAL_MS = 100L
    private const val RECORD_FLUSH_INTERVAL_MS = 2_000L
    private const val HEATMAP_FLUSH_INTERVAL_MS = 5_000L
    private const val SCROLL_DEBOUNCE_MS = 400L

    private val crashGuard = CoroutineExceptionHandler { _, throwable ->
        TtsLogger.w(TAG) { "recorder 协程异常: ${throwable.javaClass.simpleName}" }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)
    private val sessionMutex = Any()
    private val recordHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @Volatile
    private var session: RecorderSession? = null

    @Volatile
    private var probeCall: Call? = null

    @Volatile
    private var startGeneration = 0L

    @Volatile
    private var currentUrl: String = "/"

    @Volatile
    private var currentTitle: String? = null

    @Volatile
    private var lastViewportWidth = -1

    @Volatile
    private var lastViewportHeight = -1

    @Volatile
    private var lastScrollTopPx = 0

    @Volatile
    private var lastMaxScrollPx = 0

    fun start() {
        if (!RECORDING_IMPLEMENTATION_ENABLED) return
        if (!TelemetryController.isTelemetryEnabled()) return
        if (!TelemetryController.isDiagnosticsEnabled()) return
        if (UmamiClient.isRemoteDisabled()) return
        val generation = synchronized(sessionMutex) {
            if (session != null) return
            ++startGeneration
        }
        scope.launch {
            val config = withContext(Dispatchers.IO) {
                UmamiRecorderConfig.fetch { call -> probeCall = call }
            } ?: return@launch
            if (generation != startGeneration) return@launch
            if (!TelemetryController.isTelemetryEnabled() || !TelemetryController.isDiagnosticsEnabled()) {
                return@launch
            }
            val doReplay = REPLAY_IMPLEMENTATION_ENABLED && config.replayEnabled && sample(config.sampleRate)
            val doHeatmap = config.heatmapEnabled && sample(config.heatmapSampleRate)
            if (!doReplay && !doHeatmap) {
                TtsLogger.i(TAG) { "recorder 本场未命中采样，跳过录制" }
                return@launch
            }
            val recordSession = awaitRecordSession()
            if (recordSession == null) {
                TtsLogger.w(TAG) { "recorder 等待会话令牌超时，放弃本场录制" }
                return@launch
            }
            synchronized(sessionMutex) {
                if (session != null) return@launch
                if (generation != startGeneration) return@launch
                if (!UmamiClient.isRecordSessionCurrent(recordSession)) return@launch
                session = RecorderSession(
                    config = config,
                    recordSession = recordSession,
                    startUrl = currentUrl,
                    startTitle = currentTitle,
                    viewportWidth = lastViewportWidth,
                    viewportHeight = lastViewportHeight,
                    scrollTopPx = lastScrollTopPx,
                    maxScrollPx = lastMaxScrollPx,
                    doReplay = doReplay,
                    doHeatmap = doHeatmap,
                    post = ::post,
                )
            }
            TtsLogger.i(TAG) {
                "recorder 会话启动: replay=$doReplay heatmap=$doHeatmap maxDuration=${config.maxDurationMs}ms"
            }
        }
    }

    /**
     * 退到后台或关闭许可：冲刷并结束本场录制
     *
     * @param flushBufferedEvents false 时禁止任何 flush（用户关闭统计/诊断的语义）
     */
    fun stop(flushBufferedEvents: Boolean = true) {
        probeCall?.cancel()
        probeCall = null
        val closing = synchronized(sessionMutex) {
            ++startGeneration
            session.also { session = null }
        } ?: return
        closing.close(flushBufferedEvents)
    }

    // ==================== UI 事件入口（主线程调用） ====================

    fun onUrlChanged(url: String, title: String? = null) {
        currentUrl = url
        title?.let { currentTitle = it }
        session?.onUrlChanged(url, title)
    }

    fun onViewportResized(width: Int, height: Int) {
        if (width == lastViewportWidth && height == lastViewportHeight) return
        lastViewportWidth = width
        lastViewportHeight = height
        session?.onViewportResized(width, height)
    }

    fun onClick(x: Int, y: Int) {
        session?.onClick(x, y)
    }

    fun onPointerMove(x: Int, y: Int) {
        session?.onPointerMove(x, y)
    }

    /**
     * 页面滚动容器位置更新
     *
     * @param scrollTopPx 当前滚动位置
     * @param maxScrollPx 最大可滚动距离
     * @param containerViewportPx 滚动容器可视高度（px，缺省为 0 表示未知）
     */
    fun onScrollPosition(scrollTopPx: Int, maxScrollPx: Int, containerViewportPx: Int = 0) {
        lastScrollTopPx = scrollTopPx
        lastMaxScrollPx = maxScrollPx
        session?.onScrollPosition(scrollTopPx, maxScrollPx, containerViewportPx)
    }

    // ==================== 内部实现 ====================

    private suspend fun awaitRecordSession(): UmamiSessionState.RecordSession? {
        repeat(CACHE_WAIT_ATTEMPTS) {
            UmamiClient.recordSession()?.let { return it }
            if (!TelemetryController.isTelemetryEnabled() || !TelemetryController.isDiagnosticsEnabled()) {
                return null
            }
            delay(CACHE_WAIT_INTERVAL_MS)
        }
        return UmamiClient.recordSession()
    }

    private fun sample(rate: Double): Boolean = when {
        rate >= 1.0 -> true
        rate <= 0.0 -> false
        else -> Random.nextDouble() <= rate
    }

    private class RecorderSession(
        config: UmamiRecorderConfig,
        private val recordSession: UmamiSessionState.RecordSession,
        startUrl: String,
        startTitle: String?,
        viewportWidth: Int,
        viewportHeight: Int,
        scrollTopPx: Int,
        maxScrollPx: Int,
        doReplay: Boolean,
        doHeatmap: Boolean,
        post: (body: String, cache: String) -> Unit,
    ) {
        private val job = SupervisorJob()
        private val sessionScope = CoroutineScope(
            job + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
                TtsLogger.w(TAG) { "recorder 会话协程异常: ${throwable.javaClass.simpleName}" }
            }
        )
        private val transport = RecordTransport(
            websiteId = UmamiClient.WEBSITE_ID,
            cacheProvider = {
                recordSession.token.takeIf { UmamiClient.isRecordSessionCurrent(recordSession) }
            },
            post = post,
        )
        private val replay = if (doReplay) ReplaySession(transport, strictMask = config.strictMask) else null
        private val heatmap = if (doHeatmap) {
            val width = if (viewportWidth > 0) viewportWidth else currentViewport().first
            val height = if (viewportHeight > 0) viewportHeight else currentViewport().second
            HeatmapSession(
                viewportWidth = width,
                viewportHeight = height,
                startUrl = startUrl,
            )
        } else {
            null
        }
        @Volatile
        private var scrollDebounceJob: Job? = null

        @Volatile
        private var expired = false

        @Volatile
        private var closed = false

        init {
            if (scrollTopPx != 0 || maxScrollPx != 0) {
                heatmap?.onScrollPosition(scrollTopPx, maxScrollPx)
            }
            replay?.start(startUrl, startTitle)
            sessionScope.launch {
                while (isActive) {
                    if (!UmamiClient.isRecordSessionCurrent(recordSession)) {
                        close(flush = false)
                        return@launch
                    }
                    delay(RECORD_FLUSH_INTERVAL_MS)
                    transport.flushRecord()
                }
            }
            sessionScope.launch {
                while (isActive) {
                    if (!UmamiClient.isRecordSessionCurrent(recordSession)) {
                        close(flush = false)
                        return@launch
                    }
                    delay(HEATMAP_FLUSH_INTERVAL_MS)
                    transport.flushHeatmap()
                }
            }
            sessionScope.launch {
                delay(config.maxDurationMs)
                close(flush = true)
            }
        }

        fun onUrlChanged(url: String, title: String?) {
            if (expired) return
            // 先提交旧页面尚未防抖发送的最深滚动，再重置到新页面
            heatmap?.takeDeeperScrollEvent()?.let { transport.addHeatmapEvent(it) }
            heatmap?.onUrlChanged(url)
            replay?.onUrlChanged(url, title)
        }

        fun onViewportResized(width: Int, height: Int) {
            if (expired) return
            heatmap?.onViewportResized(width, height)
            replay?.onViewportResized(width, height)
        }

        fun onClick(x: Int, y: Int) {
            if (expired) return
            heatmap?.let { transport.addHeatmapEvent(it.click(x, y)) }
            replay?.onClick(x, y)
        }

        fun onPointerMove(x: Int, y: Int) {
            if (expired) return
            replay?.onPointerMove(x, y)
        }

        fun onScrollPosition(scrollTopPx: Int, maxScrollPx: Int, containerViewportPx: Int) {
            if (expired) return
            heatmap?.onScrollPosition(scrollTopPx, maxScrollPx, containerViewportPx)
            scrollDebounceJob?.cancel()
            scrollDebounceJob = sessionScope.launch {
                delay(SCROLL_DEBOUNCE_MS)
                heatmap?.takeDeeperScrollEvent()?.let { transport.addHeatmapEvent(it) }
            }
        }

        /** 结束本场：flush=false 时丢弃全部缓冲，绝不触发任何网络发送 */
        fun close(flush: Boolean = true) {
            synchronized(this) {
                if (closed) return
                closed = true
                expired = true
            }
            scrollDebounceJob?.cancel()
            replay?.close(flushPointers = flush)
            if (flush) {
                heatmap?.takeDeeperScrollEvent()?.let { transport.addHeatmapEvent(it) }
                transport.flushAll()
            } else {
                transport.discard()
            }
            job.cancel()
        }

        private fun currentViewport(): Pair<Int, Int> {
            val metrics = TalkifyAppHolder.getContext()?.resources?.displayMetrics
            return Pair(metrics?.widthPixels ?: 0, metrics?.heightPixels ?: 0)
        }
    }

    private fun post(body: String, cache: String) {
        // 实际网络执行前再校验一次许可与会话：入队后到执行前可能已被关闭/轮换
        if (!TelemetryController.isTelemetryEnabled() || !TelemetryController.isDiagnosticsEnabled()) return
        if (UmamiClient.isRemoteDisabled()) return
        if (UmamiClient.sessionToken() != cache) return
        try {
            val request = Request.Builder()
                .url("${UmamiClient.BASE_URL}/api/record")
                .header("User-Agent", UmamiClient.userAgent())
                .header("x-umami-cache", cache)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            recordHttpClient.newCall(request).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            TelemetryDiagnostics.recordHttpError(it.code)
                            TtsLogger.w(TAG) { "recorder 上报失败: HTTP ${it.code}" }
                            return
                        }
                        // HTTP 200 不代表入库：过滤返回 {beep:"boop"}，停用返回 {ok:false, reason}
                        UmamiClient.handleRecordResponse(
                            it.code,
                            readLimitedBody(it, 16 * 1024),
                        )
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    TtsLogger.w(TAG) { "recorder 上报失败: ${e.message}" }
                }
            })
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "recorder 上报异常: ${e.message}" }
        }
    }
}
