package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

/**
 * 真实页面路由的唯一责任方
 *
 * 全应用只有本组件可以上报 pageview：
 * - 前台进入：对当前真实路由上报一次（冷启动、切后台再回来均适用）
 * - 路由变化：仅在前台且路径确实变化时上报
 * - 旋转/重组/Activity 重建：同一前台场次内同一路径不重复上报
 *
 * Application 只发送前后台信号，MainActivity 只同步当前路由；任何弹窗/抽屉都
 * 不再伪造 pageview（改用 [AppPageTracker.surfaceOpened] 事件）。
 */
object ScreenTracker {

    const val PATH_MAIN = "/"
    const val PATH_ABOUT = "/about"
    const val PATH_TTS_SERVICE = "/tts-service"
    const val PATH_DOWNLOAD_SERVICE = "/download-service"

    private val lock = Any()
    private var foreground = false
    private var hasReportedInForeground = false
    private var currentPath: String? = null
    private var currentTitle: String? = null
    private var lastReportedPath: String? = null

    @Volatile
    private var screenForEvents: String = PATH_MAIN

    internal var pageViewReporter: (String, String?) -> Unit = { path, title ->
        TalkifyTelemetry.trackPageView(path, title)
    }

    fun currentScreen(): String = screenForEvents

    fun onAppForeground() {
        synchronized(lock) {
            foreground = true
            hasReportedInForeground = false
            reportIfPossibleLocked()
        }
    }

    fun onAppBackground() {
        synchronized(lock) {
            foreground = false
            hasReportedInForeground = false
        }
    }

    /** MainActivity 的 NavController 路由变化/初始路由同步入口 */
    fun onScreenChanged(path: String, title: String? = null) {
        if (!TelemetrySchema.isValidScreen(path)) return
        synchronized(lock) {
            currentPath = path
            currentTitle = title
            screenForEvents = path
            reportIfPossibleLocked()
        }
    }

    /** 许可重新开启时建立新会话锚点：只上报当前真实页面，不补发关闭期间的事件 */
    fun restartSession() {
        synchronized(lock) {
            if (!foreground) return
            hasReportedInForeground = false
            reportIfPossibleLocked()
        }
    }

    private fun reportIfPossibleLocked() {
        if (!foreground) return
        val path = currentPath ?: return
        if (hasReportedInForeground && path == lastReportedPath) return
        hasReportedInForeground = true
        lastReportedPath = path
        pageViewReporter(path, currentTitle)
    }

    internal fun resetForTest() {
        synchronized(lock) {
            foreground = false
            hasReportedInForeground = false
            currentPath = null
            currentTitle = null
            lastReportedPath = null
            screenForEvents = PATH_MAIN
        }
    }
}
