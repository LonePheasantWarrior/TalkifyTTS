package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import org.json.JSONArray
import org.json.JSONObject

/**
 * rrweb 事件协议常量与事件构造器（纯函数）
 *
 * 枚举值与事件结构逐一对照 Umami recorder.js 内嵌的 rrweb 实现，
 * 是安卓合成录制流与 Umami 回放播放器之间的契约，改动前必须核对源码
 */
internal object Rrweb {

    // ==================== EventType ====================

    const val EVENT_DOM_CONTENT_LOADED = 0
    const val EVENT_LOAD = 1
    const val EVENT_FULL_SNAPSHOT = 2
    const val EVENT_INCREMENTAL_SNAPSHOT = 3
    const val EVENT_META = 4
    const val EVENT_CUSTOM = 5

    // ==================== NodeType（序列化节点） ====================

    const val NODE_DOCUMENT = 0
    const val NODE_DOCUMENT_TYPE = 1
    const val NODE_ELEMENT = 2
    const val NODE_TEXT = 3

    // ==================== IncrementalSource ====================

    const val SOURCE_MOUSE_MOVE = 1
    const val SOURCE_MOUSE_INTERACTION = 2
    const val SOURCE_VIEWPORT_RESIZE = 4

    // ==================== MouseInteractions / PointerType ====================

    const val INTERACTION_CLICK = 2
    const val POINTER_TOUCH = 1

    /** 超大事件分片的合成事件类型，服务端按 data.id 重组（对齐 recorder.js） */
    const val FRAGMENT_EVENT_TYPE = "umami:rrweb-event-fragment"

    fun domContentLoadedEvent(timestampMs: Long): JSONObject = simpleEvent(EVENT_DOM_CONTENT_LOADED, timestampMs)

    fun loadEvent(timestampMs: Long): JSONObject = simpleEvent(EVENT_LOAD, timestampMs)

    fun metaEvent(href: String, width: Int, height: Int, timestampMs: Long): JSONObject =
        JSONObject()
            .put("type", EVENT_META)
            .put("timestamp", timestampMs)
            .put("data", JSONObject().put("href", href).put("width", width).put("height", height))

    fun fullSnapshotEvent(documentNode: JSONObject, timestampMs: Long): JSONObject =
        JSONObject()
            .put("type", EVENT_FULL_SNAPSHOT)
            .put("timestamp", timestampMs)
            .put(
                "data",
                JSONObject()
                    .put("node", documentNode)
                    .put("initialOffset", JSONObject().put("left", 0).put("top", 0))
            )

    fun mouseMoveEvent(positions: JSONArray, timestampMs: Long): JSONObject =
        incrementalEvent(JSONObject().put("source", SOURCE_MOUSE_MOVE).put("positions", positions), timestampMs)

    fun mousePosition(x: Int, y: Int, nodeId: Int, timeOffsetMs: Long): JSONObject =
        JSONObject().put("x", x).put("y", y).put("id", nodeId).put("timeOffset", timeOffsetMs)

    fun clickEvent(nodeId: Int, x: Int, y: Int, timestampMs: Long): JSONObject =
        incrementalEvent(
            JSONObject()
                .put("source", SOURCE_MOUSE_INTERACTION)
                .put("type", INTERACTION_CLICK)
                .put("id", nodeId)
                .put("x", x)
                .put("y", y)
                .put("pointerType", POINTER_TOUCH),
            timestampMs
        )

    fun viewportResizeEvent(width: Int, height: Int, timestampMs: Long): JSONObject =
        incrementalEvent(
            JSONObject().put("source", SOURCE_VIEWPORT_RESIZE).put("width", width).put("height", height),
            timestampMs
        )

    fun customEvent(tag: String, payload: JSONObject, timestampMs: Long): JSONObject =
        JSONObject()
            .put("type", EVENT_CUSTOM)
            .put("timestamp", timestampMs)
            .put("data", JSONObject().put("tag", tag).put("payload", payload))

    fun fragmentEvent(fragmentId: String, index: Int, total: Int, value: String, timestampMs: Long): JSONObject =
        JSONObject()
            .put("type", FRAGMENT_EVENT_TYPE)
            .put("timestamp", timestampMs)
            .put(
                "data",
                JSONObject().put("id", fragmentId).put("index", index).put("total", total).put("value", value)
            )

    private fun simpleEvent(type: Int, timestampMs: Long): JSONObject =
        JSONObject().put("type", type).put("timestamp", timestampMs).put("data", JSONObject())

    private fun incrementalEvent(data: JSONObject, timestampMs: Long): JSONObject =
        JSONObject().put("type", EVENT_INCREMENTAL_SNAPSHOT).put("timestamp", timestampMs).put("data", data)
}
