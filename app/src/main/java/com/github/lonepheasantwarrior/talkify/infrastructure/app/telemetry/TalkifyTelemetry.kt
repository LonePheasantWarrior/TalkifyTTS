package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

/**
 * 遥测门面（业务层唯一入口）
 *
 * 业务层只依赖本门面与语义层 Tracker；许可、队列、会话与网络发送由
 * [TelemetryController] / [UmamiClient] 统一承担。
 *
 * **隐私与口径约定**：
 * - 只允许枚举值、ID 类别、计数与时长；不得携带朗读文本、API Key、URL、代理地址
 *   或用户自由输入（schema 层会拒绝 URL/换行/超长自由文本）
 * - 打开页面/抽屉/弹窗不是事件：整页导航走 [ScreenTracker]，抽屉/弹窗走
 *   [AppPageTracker.surfaceOpened]
 * - 系统 TTS 事件使用 origin = [TelemetryOrigin.TTS_SERVICE] 与固定路径 `/tts-service`，
 *   不伪装成主页点击
 *
 * @see TelemetryController
 * @see UmamiClient
 */
object TalkifyTelemetry {

    fun isUserEnabled(): Boolean = TelemetryController.isTelemetryEnabled()

    fun trackPageView(
        url: String = ScreenTracker.currentScreen(),
        title: String? = null,
        permit: CollectionPermit? = null,
    ): Boolean {
        val effectivePermit = permit ?: TelemetryController.capturePermit()
        if (!permitAllowed(effectivePermit)) return false
        return UmamiClient.trackPage(url, title, effectivePermit)
    }

    /**
     * 上报一个自定义事件
     *
     * @param eventName  已注册的事件名（见 [TelemetrySchema]）
     * @param properties 仅 String / Int / Long；字段必须属于该事件的白名单
     * @param url        事件发生的真实页面路径，默认取当前 screen
     * @param origin     事件来源（ui / tts_service / download_service / startup / app / crash_repair）
     * @param permit     异步/跨线程生产者在创建时捕获的许可；同步 UI 事件可省略
     */
    fun trackEvent(
        eventName: String,
        properties: Map<String, Any> = emptyMap(),
        url: String = ScreenTracker.currentScreen(),
        origin: String = TelemetryOrigin.UI,
        permit: CollectionPermit? = null,
    ): Boolean {
        val effectivePermit = permit ?: TelemetryController.capturePermit()
        if (!permitAllowed(effectivePermit)) return false
        val data = if (origin == TelemetryOrigin.UI && !properties.containsKey("screen")) {
            LinkedHashMap<String, Any>(properties.size + 1).apply {
                putAll(properties)
                // 事件路径即其归属页面；显式传 /about 时不会因导航时序被写成其它页面
                put(
                    "screen",
                    if (TelemetrySchema.isValidScreen(url)) url else ScreenTracker.currentScreen()
                )
            }
        } else {
            properties
        }
        return UmamiClient.track(eventName, data, url, origin, effectivePermit)
    }

    /**
     * 上报一次会话身份声明（Identify）
     *
     * 当前工程没有业务调用；保留协议能力，使用前需按部署版本验证字段语义。
     */
    fun identify(properties: Map<String, Any>, permit: CollectionPermit? = null): Boolean {
        val effectivePermit = permit ?: TelemetryController.capturePermit()
        if (!permitAllowed(effectivePermit)) return false
        return UmamiClient.identify(properties, effectivePermit)
    }

    private fun permitAllowed(permit: CollectionPermit?): Boolean =
        if (permit == null) isUserEnabled() else TelemetryController.isPermitCurrent(permit)
}
