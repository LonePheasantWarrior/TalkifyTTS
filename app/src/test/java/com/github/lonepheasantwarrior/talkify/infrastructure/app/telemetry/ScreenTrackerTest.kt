package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ScreenTrackerTest {

    private val pageViews = mutableListOf<String>()

    @Before
    fun setUp() {
        ScreenTracker.resetForTest()
        pageViews.clear()
        ScreenTracker.pageViewReporter = { path, _ -> pageViews.add(path) }
    }

    @After
    fun tearDown() {
        ScreenTracker.pageViewReporter = { path, title -> TalkifyTelemetry.trackPageView(path, title) }
        ScreenTracker.resetForTest()
    }

    @Test
    fun `冷启动每个真实页面只计一次`() {
        ScreenTracker.onAppForeground()
        ScreenTracker.onScreenChanged("/", "Main")
        assertEquals(listOf("/"), pageViews)

        // 旋转/Activity 重建：同一前台场次同一路径不重复
        ScreenTracker.onScreenChanged("/", "Main")
        assertEquals(listOf("/"), pageViews)

        ScreenTracker.onScreenChanged("/about", "About")
        ScreenTracker.onScreenChanged("/about", "About")
        assertEquals(listOf("/", "/about"), pageViews)
    }

    @Test
    fun `路由先于前台就绪时在前台补报`() {
        ScreenTracker.onScreenChanged("/", "Main")
        assertEquals(emptyList<String>(), pageViews)
        ScreenTracker.onAppForeground()
        assertEquals(listOf("/"), pageViews)
    }

    @Test
    fun `从后台恢复关于页时上报关于页而非主页`() {
        ScreenTracker.onAppForeground()
        ScreenTracker.onScreenChanged("/", "Main")
        ScreenTracker.onScreenChanged("/about", "About")
        ScreenTracker.onAppBackground()
        pageViews.clear()

        ScreenTracker.onAppForeground()
        assertEquals(listOf("/about"), pageViews)
    }

    @Test
    fun `没有路由时不产生pageview`() {
        ScreenTracker.onAppForeground()
        assertEquals(emptyList<String>(), pageViews)
    }

    @Test
    fun `重新开启统计只锚定当前页面`() {
        ScreenTracker.onAppForeground()
        ScreenTracker.onScreenChanged("/about", "About")
        pageViews.clear()

        ScreenTracker.restartSession()
        assertEquals(listOf("/about"), pageViews)
    }

    @Test
    fun `非法路径被忽略`() {
        ScreenTracker.onAppForeground()
        ScreenTracker.onScreenChanged("https://example.com", null)
        assertEquals(emptyList<String>(), pageViews)
        assertEquals("/", ScreenTracker.currentScreen())
    }
}
