package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.content.Context
import android.os.Build
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Umami 统计上报客户端（传输层）
 *
 * 封装自建 Umami 实例的事件上报接口（`POST /api/send`），是遥测链路中唯一知晓
 * Umami 协议细节（端点、网站 ID、payload 结构）的组件。更换统计后端只需重写此组件。
 *
 * **Pageview 与自定义事件的角色分工**（Umami 核心行为，改动前必读）：
 * - Umami 仪表盘的访客/浏览量及 Pages、OS、国家等面板**仅由 pageview 驱动**
 * - 带 `name` 的自定义事件**不参与**上述指标计算，仅出现在 Events（事件）区域
 * - 因此应用启动时必须上报一次 [trackPage]，否则仪表盘将全零
 *
 * **设计原则**：
 * - **高内聚**：协议、配置、传输三者闭环在同一个 `object` 内，其余组件零感知
 * - **自举设计**：通过 [TalkifyAppHolder] 自主获取 Context，不依赖调用方传入
 * - **公共属性注入**：自动为每个自定义事件附加 `app_version` 与 `os_version`，
 *   调用方无需（也不应）手动携带，所有事件均可按版本/系统筛选
 * - **零阻塞**：基于 OkHttp 异步请求，调用后立即返回，不阻塞任何业务线程
 * - **容错隔离**：Context 未就绪时静默跳过；网络失败仅记录日志，**遥测绝不引发崩溃**
 *
 * @see TalkifyTelemetry
 */
object UmamiClient {

    private const val TAG = "TalkifyTelemetry"

    private const val ENDPOINT = "https://analytics.private-cloud.site:3000/api/send"
    private const val WEBSITE_ID = "d14bae38-0658-4b5e-a5d4-63befc13b0fd"

    /** 与 Umami 后台网站的 Domain 设置保持一致，保证仪表盘筛选与展示一致 */
    private const val HOSTNAME = "com.github.lonepheasantwarrior.talkify"

    private const val CALL_TIMEOUT_SECONDS = 10L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 异步上报一个自定义事件
     *
     * @param eventName  事件名称（建议使用 snake_case）
     * @param properties 自定义属性，仅支持 String 和 Int 类型值
     */
    fun track(eventName: String, properties: Map<String, Any>) {
        send(url = "/", name = eventName, properties = properties)
    }

    /**
     * 异步上报一次页面访问（Pageview）
     *
     * 用于驱动 Umami 仪表盘的访客/浏览量等核心指标，应在应用启动时上报一次
     */
    fun trackPage(url: String) {
        send(url = url)
    }

    // ==================== 内部实现 ====================

    private fun send(url: String, name: String? = null, properties: Map<String, Any> = emptyMap()) {
        val context = TalkifyAppHolder.getContext() ?: return
        try {
            httpClient.newCall(buildRequest(context, url, name, properties)).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            TtsLogger.w(TAG) { "Umami 上报失败: HTTP ${it.code}" }
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    TtsLogger.w(TAG) { "Umami 上报失败: ${e.message}" }
                }
            })
        } catch (e: Exception) {
            TtsLogger.w(TAG) { "Umami 上报异常: ${e.message}" }
        }
    }

    private fun buildRequest(
        context: Context,
        url: String,
        name: String?,
        properties: Map<String, Any>
    ): Request {
        val payload = JSONObject().apply {
            put("website", WEBSITE_ID)
            put("hostname", HOSTNAME)
            put("language", Locale.getDefault().toLanguageTag())
            put("referrer", "")
            put("screen", "${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
            put("url", url)
            if (name != null) {
                put("name", name)
                put("data", JSONObject(properties).apply {
                    put("app_version", appVersion(context))
                    put("os_version", Build.VERSION.RELEASE)
                })
            }
        }
        val body = JSONObject().put("type", "event").put("payload", payload).toString()
        return Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", userAgent(context))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    /**
     * 构造 User-Agent
     *
     * Umami 据此自动解析 OS 名称及版本、浏览器并派生匿名会话，App 版本号也随之附带
     */
    private fun userAgent(context: Context): String =
        "Talkify/${appVersion(context)} (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
}
