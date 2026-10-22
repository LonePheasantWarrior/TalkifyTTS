package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 热图事件构造与滚动深度状态机（纯逻辑，可单测）
 *
 * 对齐 recorder.js 热图通道的行为：
 * - click：x/y 为视口内坐标，pageX/pageY = 视口坐标 + 滚动偏移，
 *   pageW/pageH 收录点击点在内的页面最大范围
 * - scroll：`scrollPct = (scrollTop + viewportH) / pageH`，仅上报自上次发送以来
 *   超过历史最大深度的值（防抖与发送由编排层负责，本类只产出事件）
 * - url 变化时重置滚动进度追踪（对齐 recorder.js 的 url 切换处理）
 *
 * 网页端 pageH 取文档真实高度；安卓端由页面滚动容器上报精确的
 * scrollTop 与 maxScroll（`pageH = maxScroll + viewportH`），语义一致。
 *
 * 线程安全：内部锁保护状态，可在任意线程调用
 */
internal class HeatmapSession(
    viewportWidth: Int,
    viewportHeight: Int,
    startUrl: String = "/",
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private var currentUrl: String = startUrl
    private var viewportWidth = max(viewportWidth, 0)
    private var viewportHeight = max(viewportHeight, 0)
    private var containerViewportHeight = max(viewportHeight, 0)
    private var scrollTop = 0f
    private var pageHeight = max(viewportHeight, 0).toFloat()
    private var sentMaxPct = 0
    private var pendingMaxPct = 0

    fun onUrlChanged(url: String) {
        synchronized(lock) {
            currentUrl = url
            scrollTop = 0f
            pageHeight = effectiveViewportHeightLocked().toFloat()
            sentMaxPct = 0
            pendingMaxPct = 0
        }
    }

    fun onViewportResized(width: Int, height: Int) {
        synchronized(lock) {
            viewportWidth = max(width, 0)
            viewportHeight = max(height, 0)
            containerViewportHeight = max(height, 0)
        }
    }

    /**
     * 页面滚动容器位置更新（px）
     *
     * @param scrollTopPx 当前滚动位置
     * @param maxScrollPx 最大可滚动距离；0 表示容器暂不可滚动或高度未知
     * @param containerViewportPx 滚动容器可视高度；0 表示未知，回退窗口高度
     */
    fun onScrollPosition(scrollTopPx: Int, maxScrollPx: Int, containerViewportPx: Int = 0) {
        synchronized(lock) {
            if (containerViewportPx > 0) containerViewportHeight = containerViewportPx
            scrollTop = max(scrollTopPx, 0).toFloat()
            if (maxScrollPx > 0) {
                // 使用当前内容高度而非历史最大值：内容收缩后深度口径随之更新
                pageHeight = (maxScrollPx + effectiveViewportHeightLocked()).toFloat()
            } else if (containerViewportPx > 0) {
                // 容器已测量但不可滚动：内容高度即视口高度（处理内容收缩到不足一屏）
                pageHeight = effectiveViewportHeightLocked().toFloat()
            }
            pendingMaxPct = max(pendingMaxPct, computeScrollPctLocked())
        }
    }

    /** 构造一次点击的热图事件 */
    fun click(x: Int, y: Int): JSONObject = synchronized(lock) {
        // rrweb 协议：pageX/pageY = 视口坐标 + 对应轴向滚动量。
        // 本类只跟踪纵向滚动（scrollPct 语义即纵向），横向偏移恒为 0，
        // pageX 必须等于 x——把纵向 scrollTop 加到 x 会使滚动后点击点横向漂移
        val pageX = x
        val pageY = (y + scrollTop).roundToInt()
        val viewportH = effectiveViewportHeightLocked()
        JSONObject()
            .put("type", "click")
            .put("url", currentUrl)
            .put("x", x)
            .put("y", y)
            .put("pageX", pageX)
            .put("pageY", pageY)
            .put("pageW", max(viewportWidth, pageX))
            .put("pageH", max(pageHeight.roundToInt(), pageY))
            .put("viewportW", viewportWidth)
            .put("viewportH", viewportH)
            .put("timestamp", clockMs())
    }

    /**
     * 取出待上报的滚动事件（若有）
     *
     * 仅当自上次发送以来出现更深的滚动进度时返回事件，否则返回 null
     */
    fun takeDeeperScrollEvent(): JSONObject? = synchronized(lock) {
        if (pendingMaxPct <= 0 || pendingMaxPct <= sentMaxPct) return null
        val viewportH = effectiveViewportHeightLocked()
        val event = JSONObject()
            .put("type", "scroll")
            .put("url", currentUrl)
            .put("scrollPct", pendingMaxPct)
            .put("viewportW", viewportWidth)
            .put("viewportH", viewportH)
            .put("pageW", viewportWidth)
            .put("pageH", pageHeight.roundToInt())
            .put("timestamp", clockMs())
        sentMaxPct = pendingMaxPct
        pendingMaxPct = 0
        event
    }

    private fun effectiveViewportHeightLocked(): Int =
        if (containerViewportHeight > 0) containerViewportHeight else viewportHeight

    private fun computeScrollPctLocked(): Int {
        val viewportH = effectiveViewportHeightLocked()
        if (viewportH <= 0) return 0
        val bottom = scrollTop + viewportH
        val pct = (bottom / max(1f, pageHeight) * 100f).roundToInt()
        return min(max(pct, 0), 100)
    }
}
