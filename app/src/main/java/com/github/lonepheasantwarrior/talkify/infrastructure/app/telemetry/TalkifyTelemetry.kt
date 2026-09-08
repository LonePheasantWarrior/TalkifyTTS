package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

/**
 * Talkify 遥测服务（门面）
 *
 * 作为整个应用遥测能力的**唯一入口**，所有事件上报统一经过此服务，
 * 严禁在其他位置直接引用底层实现（如 [UmamiClient]）。
 *
 * **分层设计**：
 * - **门面层**（本组件）：定义统一的 `trackEvent` 接口，业务层零感知底层实现
 * - **语义层**：[TtsTelemetryTracker] 集中管理 TTS 埋点的事件名与属性 schema
 * - **画像层**：[DeviceInfoCollector] 负责匿名设备信息收集（零权限）
 * - **传输层**：[UmamiClient] 封装 Umami 协议与网络发送
 *
 * **设计原则**：
 * - **低耦合**：业务层只依赖本门面，更换统计后端只需替换传输层，业务代码零改动
 * - **零阻塞**：上报即入队即返回，绝不阻塞调用线程
 * - **零权限**：不额外收集敏感信息，匿名设备信息由 [DeviceInfoCollector] 负责
 *
 * @see UmamiClient
 * @see TtsTelemetryTracker
 * @see DeviceInfoCollector
 */
object TalkifyTelemetry {

    /**
     * 上报一次页面访问（Pageview）
     *
     * Umami 仪表盘的访客/浏览量等核心指标仅由 pageview 驱动（自定义事件不参与计算），
     * 应在应用启动时上报一次，作为统计会话锚点；页面路由变化时也应补发（对齐
     * script.js 的 SPA 行为）。
     *
     * @param url   页面路径，默认 "/"
     * @param title 页面标题（可选，对齐 script.js 的 document.title 字段）
     */
    fun trackPageView(url: String = "/", title: String? = null) {
        UmamiClient.trackPage(url, title)
    }

    /**
     * 上报一个简单事件
     *
     * 使用示例：
     * ```kotlin
     * TalkifyTelemetry.trackEvent("settings_viewed")
     * ```
     *
     * @param eventName 事件名称（建议使用 snake_case）
     */
    fun trackEvent(eventName: String) {
        UmamiClient.track(eventName, emptyMap())
    }

    /**
     * 上报一个带自定义属性的事件
     *
     * 使用示例：
     * ```kotlin
     * TalkifyTelemetry.trackEvent("tts_synthesis", mapOf(
     *     "provider_id" to "aliyunBailian",
     *     "text_length" to 128
     * ))
     * ```
     *
     * @param eventName  事件名称（建议使用 snake_case）
     * @param properties 自定义属性，仅支持 String 和 Int 类型值
     */
    fun trackEvent(eventName: String, properties: Map<String, Any>) {
        UmamiClient.track(eventName, properties)
    }

    /**
     * 上报一次会话身份声明（Identify）
     *
     * 为当前统计会话附加画像属性（对齐 script.js 的 `umami.identify()`），
     * 不参与访客/浏览量等核心指标，仅与会话关联展示。
     *
     * @param properties 会话属性，仅支持 String 和 Int 类型值
     */
    fun identify(properties: Map<String, Any>) {
        UmamiClient.identify(properties)
    }
}
