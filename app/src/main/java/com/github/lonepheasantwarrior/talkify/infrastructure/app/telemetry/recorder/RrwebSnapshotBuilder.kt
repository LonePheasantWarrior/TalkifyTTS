package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder.RrwebSnapshotBuilder.MAX_TEXT_LENGTH
import org.json.JSONArray
import org.json.JSONObject

/**
 * rrweb DOM 快照序列化器（纯函数）
 *
 * 把 [UiSnapshot] 合成为 rrweb 兼容的文档节点树：
 * - 文档 → Doctype → html(head+title, body)，语义节点 → 绝对定位 div，文本/描述 → 文本节点
 * - mirror id 深度优先分配（文档 = 1），与回放播放器的节点还原契约一致
 * - 绝对坐标换算为相对父节点的偏移（父容器 position:relative）
 *
 * **隐私红线**：[UiNode.editable] 节点不序列化任何文本（对齐网页端 maskAllInputs）；
 * 文本截断、节点数/深度上限防止异常树撑爆 payload
 */
internal object RrwebSnapshotBuilder {

    /** 序列化元素数上限（不含文档骨架），防止异常语义树撑爆 payload */
    private const val MAX_ELEMENTS = 400

    /** 序列化深度上限 */
    private const val MAX_DEPTH = 30

    /** 单个文本节点字符数上限 */
    private const val MAX_TEXT_LENGTH = 200

    /** 点击命中测试结果：DFS 序（父先于子），同点命中时取最后一个即最深节点 */
    internal data class HitRect(val nodeId: Int, val left: Int, val top: Int, val width: Int, val height: Int)

    internal class Result(
        /** FullSnapshot 事件 data.node 的 JSON 字符串 */
        val documentJson: String,
        val hitRects: List<HitRect>,
    ) {
        /** 返回包含指定窗口坐标的最深节点 id（无命中时回退到 body） */
        fun findNodeIdAt(x: Int, y: Int): Int? {
            for (i in hitRects.indices.reversed()) {
                val rect = hitRects[i]
                if (x >= rect.left && x < rect.left + rect.width && y >= rect.top && y < rect.top + rect.height) {
                    return rect.nodeId
                }
            }
            return null
        }
    }

    fun build(snapshot: UiSnapshot): Result = Builder(snapshot).build()

    /**
     * 单次快照构建：持有 id 分配计数、元素上限与命中矩形收集
     */
    private class Builder(private val snapshot: UiSnapshot) {

        private val hitRects = mutableListOf<HitRect>()
        private var nextId = 1
        private var elementCount = 0

        fun build(): Result {
            val documentNode = documentSkeleton()
            return Result(documentNode.toString(), hitRects.toList())
        }

        private fun documentSkeleton(): JSONObject {
            // mirror id 按文档顺序分配：文档 = 1，与 rrweb 播放器的还原约定一致
            val docId = allocateId()
            val docTypeId = allocateId()
            val htmlId = allocateId()
            val headId = allocateId()
            val titleId = allocateId()
            val titleTextId = allocateId()
            val bodyId = allocateId()

            val head = element("head", JSONObject(), headId).apply {
                put("childNodes", JSONArray().apply {
                    put(element("title", JSONObject(), titleId).apply {
                        put("childNodes", JSONArray().apply {
                            put(textNode(snapshot.title ?: "Talkify", titleTextId))
                        })
                    })
                })
            }
            val body = element(
                "body",
                JSONObject().put(
                    "style",
                    "margin:0;padding:0;width:${snapshot.screenWidth}px;height:${snapshot.screenHeight}px;" +
                        "position:relative;overflow:hidden;background:#ffffff;"
                ),
                bodyId
            ).apply {
                hitRects.add(HitRect(bodyId, 0, 0, snapshot.screenWidth, snapshot.screenHeight))
                put("childNodes", serializeChildren(snapshot.root, depth = 1))
            }
            val html = element("html", JSONObject(), htmlId).apply {
                put("childNodes", JSONArray().put(head).put(body))
            }
            return JSONObject().apply {
                put("type", Rrweb.NODE_DOCUMENT)
                put("id", docId)
                put("childNodes", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", Rrweb.NODE_DOCUMENT_TYPE)
                        put("name", "html")
                        put("publicId", "")
                        put("systemId", "")
                        put("id", docTypeId)
                    })
                    put(html)
                })
            }
        }

        /**
         * 序列化 [node] 的子节点列表
         *
         * 换算规则：子元素 left/top = 绝对坐标 − 父元素绝对坐标
         */
        private fun serializeChildren(node: UiNode, depth: Int): JSONArray {
            val array = JSONArray()
            if (depth > MAX_DEPTH) return array
            for (child in node.children) {
                if (elementCount >= MAX_ELEMENTS) break
                val target = collapse(child) ?: continue
                elementCount++
                array.put(serializeElement(target, node, depth))
            }
            return array
        }

        private fun serializeElement(node: UiNode, parent: UiNode, depth: Int): JSONObject {
            val style = buildString {
                append("position:absolute;")
                append("left:${node.left - parent.left}px;")
                append("top:${node.top - parent.top}px;")
                append("width:${node.width}px;")
                append("height:${node.height}px;")
                append("overflow:hidden;")
                if (node.role == "button") append("cursor:pointer;")
            }
            val attributes = JSONObject().put("style", style)
            node.role?.let { attributes.put("data-role", it) }
            val element = element("div", attributes, allocateId())
            hitRects.add(HitRect(element.optInt("id"), node.left, node.top, node.width, node.height))
            val childNodes = JSONArray()
            displayText(node)?.let { childNodes.put(textNode(it, allocateId())) }
            if (depth < MAX_DEPTH) {
                val nested = serializeChildren(node, depth + 1)
                for (i in 0 until nested.length()) {
                    childNodes.put(nested.get(i))
                }
            }
            element.put("childNodes", childNodes)
            return element
        }

        private fun element(tagName: String, attributes: JSONObject, id: Int): JSONObject = JSONObject().apply {
            put("type", Rrweb.NODE_ELEMENT)
            put("tagName", tagName)
            put("attributes", attributes)
            put("id", id)
        }

        private fun textNode(content: String, id: Int): JSONObject = JSONObject().apply {
            put("type", Rrweb.NODE_TEXT)
            put("textContent", content)
            put("id", id)
        }

        private fun allocateId(): Int = nextId++

        /**
         * 折叠纯布局穿透节点（无文本/描述/角色且仅有一个子节点），并丢弃无内容的叶子节点，
         * 压缩 payload 体积；根节点由调用方保证不进入此方法
         */
        private fun collapse(node: UiNode): UiNode? {
            var current = node
            while (current.text == null && current.contentDescription == null && current.role == null &&
                !current.editable && current.children.size == 1
            ) {
                current = current.children.first()
            }
            val hasContent = current.text != null || current.contentDescription != null ||
                current.role != null || current.editable
            return if (hasContent || current.children.isNotEmpty()) current else null
        }

        /**
         * 节点展示文本
         *
         * 可编辑节点一律返回 null（脱敏，不呈现任何输入内容）；
         * 其余取语义文本，缺省回退 contentDescription，截断到 [MAX_TEXT_LENGTH]
         */
        private fun displayText(node: UiNode): String? {
            if (node.editable) return null
            val raw = node.text ?: node.contentDescription ?: return null
            return raw.take(MAX_TEXT_LENGTH)
        }
    }
}
