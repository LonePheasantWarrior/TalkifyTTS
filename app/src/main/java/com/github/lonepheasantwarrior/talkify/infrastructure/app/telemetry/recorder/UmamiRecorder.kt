package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.UmamiClient
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * Umami 录制子系统门面（会话回放 & 热图）
 *
 * 将 Umami recorder.js 的录制协议翻译为安卓原生逻辑，与 [UmamiClient]
 * 共享会话令牌（`x-umami-cache`）。UI 层只依赖本门面，不得直接引用内部组件。
 *
 * **启动流程**（对齐 recorder.js）：
 * 探测服务端配置 → 回放/热图各自独立采样 → 轮询等待会话令牌（100ms × 50 次）
 * → 装配录制会话。任一步不满足则整场静默放弃。
 *
 * **生命周期**：每次应用回到前台录一场（网页等价于"每页加载一场"），
 * `maxDuration` 到期后停止产生事件；退后台冲刷并结束本场。
 *
 * **线程模型**：UI 事件回调（onClick/onScrollDelta/onPointerMove）在主线程调用，
 * 内部锁保护后转交协程；网络发送异步，绝不阻塞 UI。
 *
 * **隐私红线**：回放仅采集无障碍可见内容（文本/描述/布局），输入内容一律脱敏，
 * 详见 [ReplaySession] 与 [RrwebSnapshotBuilder]。
 *
 * 服务端配置默认采样率 0.15，验证时需在 Umami 后台将采样率调为 1。
 */
object UmamiRecorder {

    private const val TAG = "TalkifyTelemetry"

    /** 会话令牌等待：100ms × 50 次 ≈ 5s（对齐 recorder.js 的 re() 重试上限） */
    private const val CACHE_WAIT_ATTEMPTS = 50
    private const val CACHE_WAIT_INTERVAL_MS = 100L

    /** record 流冲刷周期（对齐 recorder.js 的 2s 定时器） */
    private const val RECORD_FLUSH_INTERVAL_MS = 2_000L

    /** 热图流冲刷周期（对齐 recorder.js 的 5s 定时器） */
    private const val HEATMAP_FLUSH_INTERVAL_MS = 5_000L

    /** 滚动深度上报防抖（对齐 recorder.js 的 400ms） */
    private const val SCROLL_DEBOUNCE_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionMutex = Any()
    private val recordHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var session: RecorderSession? = null

    /** 当前页面路径（UI 路由映射，未通知前默认 "/"） */
    @Volatile
    private var currentUrl: String = "/"

    @Volatile
    private var currentTitle: String? = null

    /** 最近一次通知的视口尺寸，用于去重（onSizeChanged 在每次布局都会触发） */
    @Volatile
    private var lastViewportWidth = -1

    @Volatile
    private var lastViewportHeight = -1

    /** 应用回到前台时启动一场录制（已有进行中的会话则跳过） */
    fun start() {
        synchronized(sessionMutex) {
            if (session != null) return
        }
        scope.launch {
            val config = UmamiRecorderConfig.fetch() ?: return@launch
            val doReplay = config.replayEnabled && sample(config.sampleRate)
            val doHeatmap = config.heatmapEnabled && sample(config.heatmapSampleRate)
            if (!doReplay && !doHeatmap) {
                TtsLogger.i(TAG) { "recorder 本场未命中采样，跳过录制" }
                return@launch
            }
            val cache = awaitSessionCache()
            if (cache == null) {
                TtsLogger.w(TAG) { "recorder 等待会话令牌超时，放弃本场录制" }
                return@launch
            }
            synchronized(sessionMutex) {
                if (session != null) return@launch
                session = RecorderSession(config, currentUrl, currentTitle, doReplay, doHeatmap, ::post)
            }
            TtsLogger.i(TAG) {
                "recorder 会话启动: replay=$doReplay heatmap=$doHeatmap maxDuration=${config.maxDurationMs}ms"
            }
        }
    }

    /** 应用退到后台：冲刷并结束本场录制 */
    fun stop() {
        val closing = synchronized(sessionMutex) { session.also { session = null } } ?: return
        closing.close()
    }

    // ==================== UI 事件入口（主线程调用） ====================

    /** 通知页面路径变更（应用内映射路径，如 "/about"） */
    fun onUrlChanged(url: String, title: String? = null) {
        currentUrl = url
        title?.let { currentTitle = it }
        session?.onUrlChanged(url, title)
    }

    /** 通知视口尺寸变化（旋转/分屏等，窗口像素；尺寸未变时忽略） */
    fun onViewportResized(width: Int, height: Int) {
        if (width == lastViewportWidth && height == lastViewportHeight) return
        lastViewportWidth = width
        lastViewportHeight = height
        session?.onViewportResized(width, height)
    }

    /** 一次点击（视口内坐标，px） */
    fun onClick(x: Int, y: Int) {
        session?.onClick(x, y)
    }

    /** 触摸移动采样（视口内坐标，px，内部按 50ms 节流） */
    fun onPointerMove(x: Int, y: Int) {
        session?.onPointerMove(x, y)
    }

    /**
     * 页面滚动容器位置更新（px）
     *
     * 由各页面的滚动容器观察 [androidx.compose.foundation.ScrollState] 上报，
     * 热图据此计算精确的滚动深度百分比
     */
    fun onScrollPosition(scrollTopPx: Int, maxScrollPx: Int) {
        session?.onScrollPosition(scrollTopPx, maxScrollPx)
    }

    // ==================== 内部实现 ====================

    private suspend fun awaitSessionCache(): String? {
        repeat(CACHE_WAIT_ATTEMPTS) {
            UmamiClient.sessionCache()?.let { return it }
            delay(CACHE_WAIT_INTERVAL_MS)
        }
        return UmamiClient.sessionCache()
    }

    /** 采样判定，对齐 recorder.js 的 X()：≥1 恒真、≤0 恒假、否则随机命中 */
    private fun sample(rate: Double): Boolean = when {
        rate >= 1.0 -> true
        rate <= 0.0 -> false
        else -> Random.nextDouble() <= rate
    }

    /**
     * 单场录制会话：装配回放/热图会话与冲刷循环，到期后停止产生事件
     */
    private class RecorderSession(
        config: UmamiRecorderConfig,
        startUrl: String,
        startTitle: String?,
        doReplay: Boolean,
        doHeatmap: Boolean,
        post: (body: String, cache: String) -> Unit,
    ) {
        private val job = SupervisorJob()
        private val sessionScope = CoroutineScope(job + Dispatchers.Default)
        private val transport = RecordTransport(
            websiteId = UmamiClient.WEBSITE_ID,
            cacheProvider = { UmamiClient.sessionCache() },
            post = post,
        )
        private val replay = if (doReplay) ReplaySession(transport) else null
        private val heatmap = if (doHeatmap) {
            val metrics = currentViewport()
            HeatmapSession(metrics.first, metrics.second)
        } else {
            null
        }
        private var scrollDebounceJob: Job? = null

        @Volatile
        private var expired = false

        init {
            replay?.start(startUrl, startTitle)
            sessionScope.launch {
                while (isActive) {
                    delay(RECORD_FLUSH_INTERVAL_MS)
                    transport.flushRecord()
                }
            }
            sessionScope.launch {
                while (isActive) {
                    delay(HEATMAP_FLUSH_INTERVAL_MS)
                    transport.flushHeatmap()
                }
            }
            sessionScope.launch {
                delay(config.maxDurationMs)
                expire()
            }
        }

        fun onUrlChanged(url: String, title: String?) {
            if (expired) return
            heatmap?.onUrlChanged(url)
            replay?.onUrlChanged(url, title)
        }

        fun onViewportResized(width: Int, height: Int) {
            if (expired) return
            heatmap?.onViewportResized(width, height)
            replay?.onViewportResized()
        }

        fun onClick(x: Int, y: Int) {
            if (expired) return
            heatmap?.let {
                transport.addHeatmapEvent(it.click(x, y))
            }
            replay?.onClick(x, y)
        }

        fun onPointerMove(x: Int, y: Int) {
            if (expired) return
            replay?.onPointerMove(x, y)
        }

        fun onScrollPosition(scrollTopPx: Int, maxScrollPx: Int) {
            if (expired) return
            heatmap?.onScrollPosition(scrollTopPx, maxScrollPx)
            scrollDebounceJob?.cancel()
            scrollDebounceJob = sessionScope.launch {
                delay(SCROLL_DEBOUNCE_MS)
                heatmap?.takeDeeperScrollEvent()?.let { transport.addHeatmapEvent(it) }
            }
        }

        /** 会话结束（退后台）：冲刷全部缓冲并停止一切任务 */
        fun close() {
            expire()
            transport.flushAll()
            job.cancel()
        }

        /** maxDuration 到期：停止产生事件（对齐 recorder.js 的 N 标记），冲刷在途内容 */
        private fun expire() {
            if (expired) return
            expired = true
            scrollDebounceJob?.cancel()
            heatmap?.takeDeeperScrollEvent()?.let { transport.addHeatmapEvent(it) }
            replay?.close()
            transport.flushAll()
        }

        private fun currentViewport(): Pair<Int, Int> {
            val metrics = TalkifyAppHolder.getContext()?.resources?.displayMetrics
            return Pair(metrics?.widthPixels ?: 0, metrics?.heightPixels ?: 0)
        }
    }

    private fun post(body: String, cache: String) {
        try {
            val request = Request.Builder()
                .url("${UmamiClient.BASE_URL}/api/record")
                .header("x-umami-cache", cache)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            recordHttpClient.newCall(request).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            TtsLogger.w(TAG) { "recorder 上报失败: HTTP ${it.code}" }
                        }
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
