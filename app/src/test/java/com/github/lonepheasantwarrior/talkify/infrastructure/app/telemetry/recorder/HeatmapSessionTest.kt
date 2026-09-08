package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeatmapSessionTest {

    private var nowMs = 50_000L

    private fun newSession(width: Int = 1080, height: Int = 2400): HeatmapSession =
        HeatmapSession(width, height, clockMs = { nowMs })

    @Test
    fun `点击事件字段完整`() {
        val session = newSession()
        val event = session.click(100, 200)
        assertEquals("click", event.getString("type"))
        assertEquals("/", event.getString("url"))
        assertEquals(100, event.getInt("x"))
        assertEquals(200, event.getInt("y"))
        assertEquals(100, event.getInt("pageX"))
        assertEquals(200, event.getInt("pageY"))
        assertEquals(1080, event.getInt("pageW"))
        assertEquals(2400, event.getInt("pageH"))
        assertEquals(1080, event.getInt("viewportW"))
        assertEquals(2400, event.getInt("viewportH"))
        assertEquals(50_000L, event.getLong("timestamp"))
    }

    @Test
    fun `滚动后点击坐标换算为页面坐标`() {
        val session = newSession()
        session.onScrollPosition(scrollTopPx = 500, maxScrollPx = 1900)
        val event = session.click(100, 200)
        assertEquals(600, event.getInt("pageX"))
        assertEquals(700, event.getInt("pageY"))
        // pageH = maxScroll + viewportH = 1900 + 2400
        assertEquals(4300, event.getInt("pageH"))
    }

    @Test
    fun `滚动深度只增上报`() {
        val session = newSession()
        session.onScrollPosition(scrollTopPx = 0, maxScrollPx = 1900)
        // 56% = round((0 + 2400) / (1900 + 2400) * 100)
        val first = session.takeDeeperScrollEvent()
        assertEquals("scroll", first!!.getString("type"))
        assertEquals("/", first.getString("url"))
        assertEquals(56, first.getInt("scrollPct"))
        assertEquals(4300, first.getInt("pageH"))

        // 位置未加深 → 无事件
        session.onScrollPosition(scrollTopPx = 0, maxScrollPx = 1900)
        assertNull(session.takeDeeperScrollEvent())

        // 加深至 84% = round((1200 + 2400) / 4300 * 100)
        session.onScrollPosition(scrollTopPx = 1200, maxScrollPx = 1900)
        val second = session.takeDeeperScrollEvent()
        assertEquals(84, second!!.getInt("scrollPct"))

        // 回滚到顶部不产生更深事件
        session.onScrollPosition(scrollTopPx = 0, maxScrollPx = 1900)
        assertNull(session.takeDeeperScrollEvent())
    }

    @Test
    fun `url变更重置滚动深度追踪`() {
        val session = newSession()
        session.onScrollPosition(scrollTopPx = 1200, maxScrollPx = 1900)
        assertEquals(84, session.takeDeeperScrollEvent()!!.getInt("scrollPct"))

        session.onUrlChanged("/about")
        assertNull(session.takeDeeperScrollEvent())

        session.onScrollPosition(scrollTopPx = 200, maxScrollPx = 0)
        val event = session.takeDeeperScrollEvent()
        assertEquals("/about", event!!.getString("url"))
        // pageH 已随 url 重置为视口高，200px 滚动 → round((200 + 2400) / 2400 * 100) 封顶 100
        assertEquals(100, event.getInt("scrollPct"))
    }

    @Test
    fun `视口尺寸更新影响后续事件`() {
        val session = newSession()
        session.onViewportResized(width = 2160, height = 1200)
        val event = session.click(10, 20)
        assertEquals(2160, event.getInt("viewportW"))
        assertEquals(1200, event.getInt("viewportH"))
        // pageH 保留历史最大估计（横竖屏切换不会缩小已知的页面高度）
        assertEquals(2400, event.getInt("pageH"))
    }

    @Test
    fun `点击坐标超出视口时页面范围相应扩大`() {
        val session = newSession()
        val event = session.click(1200, 3000)
        assertEquals(1200, event.getInt("pageW"))
        assertEquals(3000, event.getInt("pageH"))
    }
}
