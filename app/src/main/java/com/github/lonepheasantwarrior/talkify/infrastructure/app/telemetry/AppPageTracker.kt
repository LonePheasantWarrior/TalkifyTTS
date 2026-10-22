package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

/**
 * 界面容器（抽屉/弹窗）打开埋点
 *
 * 只有整页导航才是 pageview（由 [ScreenTracker] 统一上报）。抽屉、弹窗、BottomSheet
 * 均为有限枚举事件 [EVENT_SURFACE_OPENED]，避免污染 Umami 的浏览量、路径与跳出率。
 *
 * 字段：`surface_id`、`source`（触发入口）、`screen`（打开时所在真实页面，门面自动附加）。
 * 只在打开时机调用一次，关闭不补发。
 */
object AppPageTracker {

    const val EVENT_SURFACE_OPENED = "surface_opened"

    const val SURFACE_CONFIG = "config"
    const val SURFACE_PROVIDER_SELECT = "provider_select"
    const val SURFACE_DONATE = "donate"
    const val SURFACE_PRIVACY = "privacy"
    const val SURFACE_MODEL_DOWNLOAD_CONFIRM = "model_download_confirm"
    const val SURFACE_UPDATE = "update"
    const val SURFACE_NETWORK_BLOCKED = "network_blocked"
    const val SURFACE_NOTIFICATION_PERMISSION = "notification_permission"
    const val SURFACE_BATTERY_OPTIMIZATION = "battery_optimization"

    const val SOURCE_FAB = "fab"
    const val SOURCE_MAIN = "main"
    const val SOURCE_ABOUT = "about"
    const val SOURCE_STARTUP = "startup"
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_CONFIG_SHEET = "config_sheet"
    const val SOURCE_PREVIEW = "preview"

    fun surfaceOpened(surfaceId: String, source: String) {
        TalkifyTelemetry.trackEvent(
            EVENT_SURFACE_OPENED,
            linkedMapOf(
                "surface_id" to surfaceId,
                "source" to source,
            )
        )
    }
}
