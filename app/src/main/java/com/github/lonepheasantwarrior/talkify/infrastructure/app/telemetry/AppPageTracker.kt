package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

/**
 * 应用页面导航埋点（语义层）
 *
 * 与 [AppActionTracker] 构成"去了哪里 / 做了什么"的分工：
 * - **去了哪里**（本组件）：进入某个页面、抽屉或弹窗 = 一次 pageview（虚拟路由），
 *   驱动 Umami 仪表盘的浏览量与 Pages 面板——它是"位置"，不是"事件"
 * - **做了什么**（[AppActionTracker]）：点击/触发某项功能 = 自定义事件（name + data）
 *
 * **虚拟页面约定**（改动前必读）：
 * - 全屏页面（"/"、"/about"）由 MainActivity 的 NavController 监听上报，不经本组件
 * - 抽屉/弹窗由各自**打开时机**上报一次 pageview，关闭不补发（回到原界面不产生新导航），
 *   因此 Pages 面板中各虚拟路径的浏览量 = 该界面被打开的次数
 * - 纯结果反馈（"已是最新版本"提示框、打赏保存指引、崩溃对话框等）不是导航目的地，
 *   不设路径，其信息由触发事件的 result 属性承载
 * - pageview 不携带 data 属性，打开场景的附加信息由后续动作事件承载
 * - recorder 录制子系统不跟随虚拟路由（抽屉/Compose 弹窗属独立窗口，本就不被采集）
 *
 * @see TalkifyTelemetry.trackPageView
 * @see AppActionTracker
 */
object AppPageTracker {

    // ==================== 真实路由（MainActivity NavController，此处仅备档） ====================

    const val PATH_MAIN = "/"
    const val PATH_ABOUT = "/about"

    // ==================== 虚拟路由（抽屉/弹窗） ====================

    /** 配置面板抽屉（FAB 抽屉，MainViewModel.openConfigSheet 全部入口） */
    const val PATH_CONFIG = "/config"

    /** 供应商选择抽屉（主界面卡片点击；选定供应商是 provider_switched 事件） */
    const val PATH_PROVIDER_SELECT = "/provider-select"

    /** 打赏抽屉 */
    const val PATH_DONATE = "/donate"

    /** 关于隐私弹窗 */
    const val PATH_PRIVACY = "/privacy"

    /** 本地模型下载确认弹窗（配置面板 / 预览播放两处入口共用） */
    const val PATH_MODEL_DOWNLOAD_CONFIRM = "/model-download-confirm"

    /** 更新弹窗（启动自动 / 关于页手动两处入口共用） */
    const val PATH_UPDATE = "/update"

    /** 启动检查·网络阻断弹窗 */
    const val PATH_NETWORK_BLOCKED = "/network-blocked"

    /** 启动检查·通知权限弹窗 */
    const val PATH_NOTIFICATION_PERMISSION = "/notification-permission"

    /** 启动检查·电池优化弹窗 */
    const val PATH_BATTERY_OPTIMIZATION = "/battery-optimization"

    /**
     * 上报一次"进入某页面/抽屉/弹窗"（pageview）
     *
     * 必须在**状态置位/打开动作的确切时机**调用（点击回调、状态机转移），
     * 不得放在 Composable 组合体内（重组会重复上报）
     *
     * @param path  虚拟路由路径（见各 PATH_ 常量）
     * @param title 页面标题（可选，供访客日志阅读）
     */
    fun open(path: String, title: String? = null) {
        TalkifyTelemetry.trackPageView(path, title)
    }
}
