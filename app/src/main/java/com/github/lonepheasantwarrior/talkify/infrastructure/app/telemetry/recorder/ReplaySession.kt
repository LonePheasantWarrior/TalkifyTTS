package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder.ReplaySession.Companion.CHECKOUT_INTERVAL_MS
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder.ReplaySession.Companion.POINTER_BATCH_INTERVAL_MS
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder.ReplaySession.Companion.POINTER_SAMPLE_INTERVAL_MS
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话回放录制会话（rrweb 事件流）
 *
 * 将安卓 UI 翻译为 rrweb 兼容事件流：
 * - 开场 DomContentLoaded → Load → Meta + FullSnapshot（对齐 rrweb checkout 行为）
 * - 每 [CHECKOUT_INTERVAL_MS] 重发快照（对齐 checkoutEveryNms=30s），保证静态线框的新鲜度
 * - 页面切换：Custom `url-change` 事件 + 立即重快照
 * - 触摸移动按 [POINTER_SAMPLE_INTERVAL_MS] 采样，[POINTER_BATCH_INTERVAL_MS] 批量上送
 * - 点击：按最近一次快照命中测试定位节点，产出 MouseInteraction(Click)
 * - 视口变化：ViewportResize + 重快照
 *
 * 快照捕获在主线程遍历无障碍树（`LocalView` 宿主 View 的
 * [android.view.accessibility.AccessibilityNodeProvider]，即语义树的公开只读视图），
 * 序列化在 Default 线程完成；单次快照串行（进行中跳过新请求），全程异常吞没
 *
 * @param transport record 流传输层
 */
internal class ReplaySession(
    private val transport: RecordTransport,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    @Volatile
    private var snapshotResult: RrwebSnapshotBuilder.Result? = null

    @Volatile
    private var currentUrl: String = "/"

    @Volatile
    private var currentTitle: String? = null
    private val pointerBuffer = mutableListOf<PointerPos>()
    private var lastPointerSampleMs = 0L
    private var snapshotInFlight = false
    private var checkoutJob: Job? = null
    private var pointerFlushJob: Job? = null

    private data class PointerPos(val x: Int, val y: Int, val nodeId: Int, val atMs: Long)

    /** 启动开场事件流与周期任务 */
    fun start(url: String, title: String?) {
        currentUrl = url
        currentTitle = title
        val now = clockMs()
        transport.addRecordEvent(Rrweb.domContentLoadedEvent(now))
        transport.addRecordEvent(Rrweb.loadEvent(now))
        takeSnapshot()
        checkoutJob = scope.launch {
            while (isActive) {
                delay(CHECKOUT_INTERVAL_MS)
                takeSnapshot()
            }
        }
        pointerFlushJob = scope.launch {
            while (isActive) {
                delay(POINTER_BATCH_INTERVAL_MS)
                flushPointers()
            }
        }
    }

    fun onUrlChanged(url: String, title: String?) {
        currentUrl = url
        title?.let { currentTitle = it }
        try {
            transport.addRecordEvent(
                Rrweb.customEvent("url-change", JSONObject().put("url", url), clockMs())
            )
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "url-change 事件构造失败: ${e.message}" }
        }
        takeSnapshot()
    }

    fun onViewportResized() = takeSnapshot()

    /** 记录一次触摸移动（采样节流，仅暂存不发送） */
    fun onPointerMove(x: Int, y: Int) {
        val now = clockMs()
        synchronized(lock) {
            if (now - lastPointerSampleMs < POINTER_SAMPLE_INTERVAL_MS) return
            lastPointerSampleMs = now
            val nodeId = snapshotResult?.findNodeIdAt(x, y) ?: -1
            pointerBuffer.add(PointerPos(x, y, nodeId, now))
        }
    }

    /** 记录一次点击（命中最近快照的节点） */
    fun onClick(x: Int, y: Int) {
        val nodeId = snapshotResult?.findNodeIdAt(x, y) ?: return
        transport.addRecordEvent(Rrweb.clickEvent(nodeId, x, y, clockMs()))
    }

    /** 会话结束：停止周期任务并释放协程 */
    fun close() {
        checkoutJob?.cancel()
        pointerFlushJob?.cancel()
        flushPointers()
        scope.cancel()
    }

    // ==================== 快照 ====================

    private fun takeSnapshot() {
        synchronized(lock) {
            if (snapshotInFlight) return
            snapshotInFlight = true
        }
        scope.launch {
            try {
                val captured = withContext(Dispatchers.Main) { captureUiSnapshot(currentUrl, currentTitle) }
                    ?: return@launch
                val result = RrwebSnapshotBuilder.build(captured)
                val now = clockMs()
                transport.addRecordEvent(
                    Rrweb.metaEvent(captured.url, captured.screenWidth, captured.screenHeight, now)
                )
                transport.addRecordEvent(
                    Rrweb.fullSnapshotEvent(JSONObject(result.documentJson), now),
                    solo = true,
                    eventTimestampMs = now
                )
                snapshotResult = result
            } catch (e: Exception) {
                TtsLogger.w(TAG) { "回放快照失败: ${e.message}" }
            } finally {
                synchronized(lock) { snapshotInFlight = false }
            }
        }
    }

    private fun flushPointers() {
        val batch = synchronized(lock) {
            if (pointerBuffer.isEmpty()) return
            val batchStart = pointerBuffer.first().atMs
            val positions = JSONArray()
            for (pos in pointerBuffer) {
                positions.put(Rrweb.mousePosition(pos.x, pos.y, pos.nodeId, pos.atMs - batchStart))
            }
            pointerBuffer.clear()
            positions
        }
        transport.addRecordEvent(Rrweb.mouseMoveEvent(batch, clockMs()))
    }

    // ==================== 无障碍树捕获（主线程） ====================

    private fun captureUiSnapshot(url: String, title: String?): UiSnapshot? {
        val hostView = TalkifyAppHolder.semanticsHostView() ?: return null
        if (!hostView.isShown) return null
        return try {
            val provider = hostView.accessibilityNodeProvider ?: return null
            val rootInfo = provider.createAccessibilityNodeInfo(View.NO_ID) ?: return null
            val metrics = hostView.resources.displayMetrics
            val counter = intArrayOf(0)
            val rootNode = toUiNode(rootInfo, depth = 0, counter) ?: return null
            UiSnapshot(
                url = url,
                title = title,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                root = rootNode
            )
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "语义树捕获失败: ${e.message}" }
            null
        }
    }

    /**
     * 无障碍节点 → [UiNode]
     *
     * 坐标取 boundsInScreen（全屏窗口下与窗口坐标一致）；文本取 text，缺省回退
     * contentDescription；可编辑节点仅记录 editable 标记（内容在序列化层丢弃）。
     * 带节点数与深度上限，防止异常树耗尽资源
     */
    private fun toUiNode(info: AccessibilityNodeInfo, depth: Int, counter: IntArray): UiNode? {
        counter[0]++
        if (depth > MAX_CAPTURE_DEPTH || counter[0] > MAX_CAPTURE_NODES) return null
        val bounds = Rect()
        info.getBoundsInScreen(bounds)
        return UiNode(
            left = bounds.left,
            top = bounds.top,
            width = bounds.width(),
            height = bounds.height(),
            text = info.text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription = info.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            role = mapRole(info.className?.toString(), info.isEditable),
            editable = info.isEditable,
            children = buildList {
                for (i in 0 until info.childCount) {
                    val child = info.getChild(i) ?: continue
                    if (!child.isVisibleToUser) continue
                    toUiNode(child, depth + 1, counter)?.let { add(it) }
                }
            }
        )
    }

    /** 标准控件类名 → 线框 data-role 属性；可编辑输入一律视为 input */
    private fun mapRole(className: String?, editable: Boolean): String? {
        if (editable) return "input"
        return when (className?.substringAfterLast('.')?.lowercase()) {
            "button", "imagebutton" -> "button"
            "checkbox" -> "checkbox"
            "switch", "togglebutton" -> "switch"
            "radiobutton" -> "radio"
            "image", "imageview" -> "image"
            else -> null
        }
    }

    companion object {
        private const val TAG = "TalkifyTelemetry"

        /** 对齐 rrweb checkoutEveryNms = 30s */
        private const val CHECKOUT_INTERVAL_MS = 30_000L

        /** 对齐 rrweb mousemove 批量回调间隔 500ms */
        private const val POINTER_BATCH_INTERVAL_MS = 500L

        /** 对齐 rrweb mousemove 采样间隔 50ms */
        private const val POINTER_SAMPLE_INTERVAL_MS = 50L

        private const val MAX_CAPTURE_DEPTH = 40
        private const val MAX_CAPTURE_NODES = 1000
    }
}
