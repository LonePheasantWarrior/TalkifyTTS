package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RrwebSnapshotBuilderTest {

    private fun childNodes(node: JSONObject): Array<JSONObject> {
        val array = node.getJSONArray("childNodes")
        return Array(array.length()) { array.getJSONObject(it) }
    }

    private fun parseDocument(result: RrwebSnapshotBuilder.Result): JSONObject = JSONObject(result.documentJson)

    private fun bodyOf(document: JSONObject): JSONObject {
        val html = childNodes(document)[1]
        assertEquals("html", html.getString("tagName"))
        val (head, body) = childNodes(html).toList()
        assertEquals("head", head.tagName())
        assertEquals("body", body.tagName())
        return body
    }

    private fun JSONObject.tagName(): String = getString("tagName")

    @Test
    fun `文档骨架包含doctype与html结构`() {
        val snapshot = UiSnapshot("/", "Main", 1080, 2400, root = UiNode(0, 0, 1080, 2400))
        val document = parseDocument(RrwebSnapshotBuilder.build(snapshot))

        assertEquals(Rrweb.NODE_DOCUMENT, document.getInt("type"))
        assertEquals(1, document.getInt("id"))
        val (docType, html) = childNodes(document).toList()
        assertEquals(Rrweb.NODE_DOCUMENT_TYPE, docType.getInt("type"))
        assertEquals("html", docType.getString("name"))
        assertEquals("html", html.tagName())

        val (head, body) = childNodes(html).toList()
        assertEquals("head", head.tagName())
        assertEquals("body", body.tagName())
        assertTrue(
            body.getJSONObject("attributes").getString("style")
                .contains("width:1080px;height:2400px;")
        )
    }

    @Test
    fun `标题写入head且缺省回退Talkify`() {
        val withTitle = parseDocument(
            RrwebSnapshotBuilder.build(UiSnapshot("/", "About", 100, 100, root = UiNode(0, 0, 100, 100)))
        )
        val head = childNodes(childNodes(withTitle).asList()[1]).first()
        val title = childNodes(head).first()
        assertEquals("title", title.tagName())
        assertEquals("About", childNodes(title).first().getString("textContent"))

        val withoutTitle = parseDocument(
            RrwebSnapshotBuilder.build(UiSnapshot("/", null, 100, 100, root = UiNode(0, 0, 100, 100)))
        )
        val head2 = childNodes(childNodes(withoutTitle).asList()[1]).first()
        val title2 = childNodes(head2).first()
        assertEquals("Talkify", childNodes(title2).first().getString("textContent"))
    }

    @Test
    fun `绝对坐标换算为相对父节点偏移`() {
        val snapshot = UiSnapshot(
            "/", null, 1000, 2000,
            root = UiNode(10, 20, 980, 1960, children = listOf(
                UiNode(60, 90, 100, 50, text = "标签")
            ))
        )
        val body = bodyOf(parseDocument(RrwebSnapshotBuilder.build(snapshot)))
        val div = childNodes(body).first()
        val style = div.getJSONObject("attributes").getString("style")
        assertTrue(style.contains("left:50px;"))
        assertTrue(style.contains("top:70px;"))
        assertTrue(style.contains("width:100px;"))
        assertTrue(style.contains("height:50px;"))
    }

    @Test
    fun `纯布局穿透节点被折叠`() {
        val snapshot = UiSnapshot(
            "/", null, 1000, 2000,
            root = UiNode(0, 0, 1000, 2000, children = listOf(
                UiNode(0, 0, 1000, 1000, children = listOf( // 无内容、单子节点 → 穿透
                    UiNode(10, 10, 200, 40, text = "穿透后保留")
                ))
            ))
        )
        val body = bodyOf(parseDocument(RrwebSnapshotBuilder.build(snapshot)))
        val divs = childNodes(body).filter { it.getInt("type") == Rrweb.NODE_ELEMENT }
        assertEquals(1, divs.size)
        val text = childNodes(divs.first()).first()
        assertEquals("穿透后保留", text.getString("textContent"))
        val style = divs.first().getJSONObject("attributes").getString("style")
        assertTrue(style.contains("left:10px;top:10px;"))
    }

    @Test
    fun `可编辑节点不序列化任何文本`() {
        val snapshot = UiSnapshot(
            "/", null, 1000, 2000,
            root = UiNode(0, 0, 1000, 2000, children = listOf(
                UiNode(10, 10, 300, 60, text = "sk-secret-api-key", role = "input", editable = true)
            ))
        )
        val body = bodyOf(parseDocument(RrwebSnapshotBuilder.build(snapshot)))
        val div = childNodes(body).first()
        assertEquals("input", div.getJSONObject("attributes").optString("data-role"))
        assertTrue(childNodes(div).isEmpty())
    }

    @Test
    fun `文本截断到200字符`() {
        val longText = "字".repeat(300)
        val snapshot = UiSnapshot(
            "/", null, 1000, 2000,
            root = UiNode(0, 0, 1000, 2000, children = listOf(
                UiNode(0, 0, 100, 20, text = longText)
            ))
        )
        val body = bodyOf(parseDocument(RrwebSnapshotBuilder.build(snapshot)))
        val text = childNodes(childNodes(body).first()).first()
        assertEquals(200, text.getString("textContent").length)
    }

    @Test
    fun `元素数量达到上限后停止序列化`() {
        val children = (0 until 500).map {
            UiNode(0, it * 10, 100, 10, text = "item$it")
        }
        val snapshot = UiSnapshot("/", null, 1000, 8000, root = UiNode(0, 0, 1000, 8000, children = children))
        val body = bodyOf(parseDocument(RrwebSnapshotBuilder.build(snapshot)))
        assertEquals(400, childNodes(body).size)
    }

    @Test
    fun `按钮角色映射与点击命中测试`() {
        val button = UiNode(100, 200, 300, 120, text = "开始合成", role = "button")
        val snapshot = UiSnapshot("/", null, 1080, 2400, root = UiNode(0, 0, 1080, 2400, children = listOf(button)))
        val result = RrwebSnapshotBuilder.build(snapshot)

        val body = bodyOf(parseDocument(result))
        val div = childNodes(body).first()
        val attributes = div.getJSONObject("attributes")
        assertEquals("button", attributes.getString("data-role"))
        assertTrue(attributes.getString("style").contains("cursor:pointer;"))

        val buttonId = div.getInt("id")
        assertEquals(buttonId, result.findNodeIdAt(150, 220))
        val bodyId = body.getInt("id")
        assertEquals(bodyId, result.findNodeIdAt(900, 2300))
        assertNull(result.findNodeIdAt(5000, 5000))
    }
}
