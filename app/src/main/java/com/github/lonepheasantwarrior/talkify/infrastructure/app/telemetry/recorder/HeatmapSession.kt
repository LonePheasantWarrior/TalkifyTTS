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
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private var currentUrl: String = "/"
    private var viewportWidth = max(viewportWidth, 0)
    private var viewportHeight = max(viewportHeight, 0)
    private var scrollTop = 0f
    private var pageHeight = max(viewportHeight, 0).toFloat()
    private var sentMaxPct = 0
    private var pendingMaxPct = 0

    fun onUrlChanged(url: String) {
        synchronized(lock) {
            currentUrl = url
            scrollTop = 0f
            pageHeight = viewportHeight.toFloat()
            sentMaxPct = 0
            pendingMaxPct = 0
        }
    }

    fun onViewportResized(width: Int, height: Int) {
        synchronized(lock) {
            viewportWidth = max(width, 0)
            viewportHeight = max(height, 0)
        }
    }

    /**
     * 页面滚动容器位置更新（px）
     *
     * @param scrollTopPx 当前滚动位置
     * @param maxScrollPx 最大可滚动距离，`pageH = maxScroll + viewportH`；
     *                    0 表示容器暂不可滚动或高度未知，此时维持既有估计
     */
    fun onScrollPosition(scrollTopPx: Int, maxScrollPx: Int) {
        synchronized(lock) {
            scrollTop = max(scrollTopPx, 0).toFloat()
            if (maxScrollPx > 0) {
                pageHeight = max(pageHeight, maxScrollPx.toFloat() + viewportHeight)
            }
            pendingMaxPct = max(pendingMaxPct, computeScrollPctLocked())
        }
    }

    /** 构造一次点击的热图事件 */
    fun click(x: Int, y: Int): JSONObject = synchronized(lock) {
        val pageX = (x + scrollTop).roundToInt()
        val pageY = (y + scrollTop).roundToInt()
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
            .put("viewportH", viewportHeight)
            .put("timestamp", clockMs())
    }

    /**
     * 取出待上报的滚动事件（若有）
     *
     * 仅当自上次发送以来出现更深的滚动进度时返回事件，否则返回 null
     */
    fun takeDeeperScrollEvent(): JSONObject? = synchronized(lock) {
        if (pendingMaxPct <= 0 || pendingMaxPct <= sentMaxPct) return null
        val event = JSONObject()
            .put("type", "scroll")
            .put("url", currentUrl)
            .put("scrollPct", pendingMaxPct)
            .put("viewportW", viewportWidth)
            .put("viewportH", viewportHeight)
            .put("pageW", viewportWidth)
            .put("pageH", pageHeight.roundToInt())
            .put("timestamp", clockMs())
        sentMaxPct = pendingMaxPct
        pendingMaxPct = 0
        event
    }

    private fun computeScrollPctLocked(): Int {
        if (viewportHeight <= 0) return 0
        val bottom = scrollTop + viewportHeight
        val pct = (bottom / max(1f, pageHeight) * 100f).roundToInt()
        return min(max(pct, 0), 100)
    }
}
