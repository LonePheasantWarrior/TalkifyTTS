package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.content.Context
import android.view.View

/**
 * 全局 Context 持有者
 *
 * 供供应商、本地模型管理等非 Android 组件层获取应用 Context，
 * 避免它们对 Activity/Service 的直接依赖。
 *
 * 另追踪两类瞬态引用（均由生命周期回调维护，不构成泄漏）：
 * - 当前前台 Activity（onResume 设置、onPause 清除）
 * - 语义宿主 View（组合内注册，供遥测录制子系统遍历无障碍树；
 *   AndroidComposeView 在新版 Compose 中为 internal 类型，
 *   通过组合内 LocalView 拿到实例、经 AccessibilityNodeProvider 公开 API 读取）
 */
object TalkifyAppHolder {
    private var appContext: Context? = null

    @Volatile
    private var activity: Activity? = null

    @Volatile
    private var semanticsHostView: View? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    fun getContext(): Context? = appContext

    /** 当前前台 Activity（应用在后台时为 null） */
    fun currentActivity(): Activity? = activity

    fun setCurrentActivity(value: Activity?) {
        activity = value
    }

    /** 语义宿主 View（Compose 内容所在的 AndroidComposeView，应用不在前台时为 null） */
    fun semanticsHostView(): View? = semanticsHostView

    fun setSemanticsHostView(value: View?) {
        semanticsHostView = value
    }
}
