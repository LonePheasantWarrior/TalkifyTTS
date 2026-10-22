package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySchemaTest {

    private val appFrame =
        "com.github.lonepheasantwarrior.talkify.service.TalkifyTtsService.processRequestSynchronously:723"

    private fun ttsResultData(): Map<String, Any> = mapOf(
        "provider_id" to "openAI",
        "model_id" to "gpt-4o-mini-tts",
        "model_category" to TelemetryIds.CATEGORY_DEFAULT,
        "voice_id" to "alloy",
        "voice_category" to TelemetryIds.CATEGORY_BUILTIN,
        "text_length" to 10,
        "status" to TtsTelemetryTracker.STATUS_SUCCESS,
        "duration_ms" to 200,
        "duration_bucket" to "<1s",
        "has_audio" to 1,
        "audio_bytes" to 1_024,
        "provider_completed" to 1,
        "sampled" to 1,
    )

    private fun ttsWindowData(): Map<String, Any> = mapOf(
        "provider_id" to "openAI",
        "model_category" to TelemetryIds.CATEGORY_DEFAULT,
        "window_start_s" to 1_700_000_000L,
        "window_end_s" to 1_700_000_300L,
        "request_count" to 2,
        "success_count" to 2,
        "error_count" to 0,
        "timeout_count" to 0,
        "cancelled_count" to 0,
        "ignored_count" to 0,
        "has_audio_count" to 2,
        "provider_completed_count" to 2,
        "text_chars" to 20L,
        "audio_bytes" to 2_048L,
        "prepare_ms_total" to 20L,
        "synthesis_ms_total" to 160L,
        "first_audio_missing" to 0,
        "duration_lt_1s" to 0,
        "duration_1_3s" to 2,
        "duration_3_10s" to 0,
        "duration_10_30s" to 0,
        "duration_gt_30s" to 0,
        "first_audio_lt_500ms" to 2,
        "first_audio_500ms_1s" to 0,
        "first_audio_1_3s" to 0,
        "first_audio_gt_3s" to 0,
    )

    private fun crashData(): Map<String, Any> = mapOf(
        "exception_class" to "java.lang.RuntimeException",
        "exception_chain_depth" to 2,
        "exception_2" to "java.lang.IllegalStateException",
        "frame_count" to 1,
        "frame_1" to appFrame,
        "thread_type" to "main",
        "crash_timestamp_s" to 1_700_000_000L,
        "crash_app_version" to "1.0.36",
        "crash_version_code" to 38,
        "crash_build_channel" to "release",
    )

    /** 每个已注册事件的最小合法契约；新增埋点必须同步补入 */
    private val validEvents: Map<String, Triple<String, String, Map<String, Any>>> = mapOf(
        AppActionTracker.EVENT_APP_FOREGROUNDED to Triple(
            TelemetryOrigin.APP, "/",
            mapOf("foreground_source" to "cold"),
        ),
        AppPageTracker.EVENT_SURFACE_OPENED to Triple(
            TelemetryOrigin.UI, "/",
            mapOf("surface_id" to AppPageTracker.SURFACE_CONFIG, "source" to AppPageTracker.SOURCE_MAIN),
        ),
        AppActionTracker.EVENT_PREVIEW_PLAY_CLICK to Triple(
            TelemetryOrigin.UI, "/",
            mapOf(
                "provider_id" to "openAI",
                "voice_id" to "alloy",
                "voice_category" to TelemetryIds.CATEGORY_BUILTIN,
                "text_length" to 10,
                "outcome" to AppActionTracker.OUTCOME_STARTED,
            ),
        ),
        AppActionTracker.EVENT_PREVIEW_PLAYBACK to Triple(
            TelemetryOrigin.UI, "/",
            mapOf(
                "provider_id" to "openAI",
                "model_id" to "gpt-4o-mini-tts",
                "model_category" to TelemetryIds.CATEGORY_DEFAULT,
                "voice_id" to "alloy",
                "voice_category" to TelemetryIds.CATEGORY_BUILTIN,
                "text_length" to 10,
                "status" to AppActionTracker.STATUS_SUCCESS,
                "duration_ms" to 200,
                "duration_bucket" to "<1s",
            ),
        ),
        AppActionTracker.EVENT_PREVIEW_VOICE_SELECTED to Triple(
            TelemetryOrigin.UI, "/",
            mapOf(
                "provider_id" to "openAI",
                "voice_id" to "alloy",
                "voice_category" to TelemetryIds.CATEGORY_BUILTIN,
            ),
        ),
        AppActionTracker.EVENT_PROVIDER_SWITCHED to Triple(
            TelemetryOrigin.UI, "/",
            mapOf("from_provider" to "azure", "to_provider" to "openAI"),
        ),
        AppActionTracker.EVENT_CONFIG_SAVED to Triple(
            TelemetryOrigin.UI, "/",
            mapOf(
                "provider_id" to "openAI",
                "is_local_model" to 0,
                "has_custom_api_url" to 1,
                "has_custom_model_id" to 0,
                "voice_id" to "alloy",
                "voice_category" to TelemetryIds.CATEGORY_BUILTIN,
            ),
        ),
        AppActionTracker.EVENT_MODEL_DOWNLOAD_DIALOG to Triple(
            TelemetryOrigin.UI, "/",
            mapOf(
                "model_id" to "zipvoice_distill",
                "source" to AppActionTracker.SOURCE_PREVIEW,
                "action" to AppActionTracker.ACTION_CONFIRM,
                "size_display" to "~200 MB",
            ),
        ),
        AppActionTracker.EVENT_MODEL_DOWNLOAD_START to Triple(
            TelemetryOrigin.DOWNLOAD_SERVICE, "/download-service",
            mapOf("model_id" to "zipvoice_distill", "file_count" to 3, "total_size_mb" to 200),
        ),
        AppActionTracker.EVENT_MODEL_DOWNLOAD_RESULT to Triple(
            TelemetryOrigin.DOWNLOAD_SERVICE, "/download-service",
            mapOf(
                "model_id" to "zipvoice_distill",
                "status" to AppActionTracker.STATUS_COMPLETED,
                "duration_ms" to 1_000,
                "duration_bucket" to "1-3s",
                "progress" to 100,
            ),
        ),
        AppActionTracker.EVENT_UPDATE_CHECK to Triple(
            TelemetryOrigin.UI, "/",
            mapOf(
                "trigger" to AppActionTracker.TRIGGER_MANUAL,
                "result" to AppActionTracker.RESULT_NO_UPDATE,
                "duration_ms" to 500,
            ),
        ),
        AppActionTracker.EVENT_UPDATE_DIALOG_ACTION to Triple(
            TelemetryOrigin.UI, "/",
            mapOf(
                "source" to AppActionTracker.TRIGGER_STARTUP,
                "action" to AppActionTracker.ACTION_REMIND_LATER,
                "version" to "1.0.37",
            ),
        ),
        AppActionTracker.EVENT_DONATE_CHANNEL_CLICK to Triple(
            TelemetryOrigin.UI, "/about",
            mapOf("channel" to AppActionTracker.CHANNEL_WECHAT),
        ),
        AppActionTracker.EVENT_DONATE_QR_SAVED to Triple(
            TelemetryOrigin.UI, "/about",
            mapOf(
                "channel" to AppActionTracker.CHANNEL_ALIPAY,
                "result" to AppActionTracker.RESULT_SUCCESS,
            ),
        ),
        AppActionTracker.EVENT_EXTERNAL_LINK_OPEN to Triple(
            TelemetryOrigin.UI, "/about",
            mapOf("target" to AppActionTracker.TARGET_GITHUB),
        ),
        AppActionTracker.EVENT_QQ_GROUP_COPIED to Triple(TelemetryOrigin.UI, "/about", emptyMap()),
        AppActionTracker.EVENT_STARTUP_NETWORK_ACTION to Triple(
            TelemetryOrigin.STARTUP, "/",
            mapOf("action" to AppActionTracker.ACTION_OPEN_SETTINGS, "offline_capable" to 0),
        ),
        AppActionTracker.EVENT_NOTIFICATION_PERMISSION to Triple(
            TelemetryOrigin.UI, "/",
            mapOf("action" to AppActionTracker.ACTION_GRANTED),
        ),
        AppActionTracker.EVENT_BATTERY_OPTIMIZATION to Triple(
            TelemetryOrigin.UI, "/",
            mapOf("action" to AppActionTracker.ACTION_SKIPPED),
        ),
        AppActionTracker.EVENT_SETTINGS_JUMP to Triple(
            TelemetryOrigin.UI, "/",
            mapOf(
                "target" to AppActionTracker.TARGET_TTS,
                "source" to AppActionTracker.SOURCE_DEFAULT_BANNER,
            ),
        ),
        AppActionTracker.EVENT_APP_CRASH to Triple(TelemetryOrigin.CRASH_REPAIR, "/", crashData()),
        AppActionTracker.EVENT_RX_SUPPRESSED to Triple(
            TelemetryOrigin.APP, "/",
            mapOf(
                "exception_class" to "java.lang.IllegalStateException",
                "thread_type" to "background",
            ),
        ),
        TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT to Triple(
            TelemetryOrigin.TTS_SERVICE, "/tts-service", ttsResultData(),
        ),
        TtsTelemetryTracker.EVENT_TTS_METRICS_WINDOW to Triple(
            TelemetryOrigin.TTS_SERVICE, "/tts-service", ttsWindowData(),
        ),
    )

    private fun prepare(
        name: String,
        data: Map<String, Any>,
        origin: String = TelemetryOrigin.UI,
        url: String = "/",
    ): TelemetryEvent? = TelemetrySchema.prepare(
        TelemetryEvent(name = name, url = url, origin = origin, data = data),
    )

    @Test
    fun `所有已注册事件的最小契约均通过`() {
        validEvents.forEach { (name, spec) ->
            val (origin, url, data) = spec
            assertNotNull("事件 $name 应通过契约", prepare(name, data, origin, url))
        }
    }

    @Test
    fun `窗口维度溢出归入other仍可通过契约`() {
        val prepared = prepare(
            TtsTelemetryTracker.EVENT_TTS_METRICS_WINDOW,
            ttsWindowData() + mapOf("provider_id" to "other", "model_category" to "other"),
            TelemetryOrigin.TTS_SERVICE,
            "/tts-service",
        )
        assertNotNull(prepared)
    }

    @Test
    fun `未注册事件与未知属性被拒绝`() {
        assertNull(prepare("not_registered", emptyMap()))
        assertNull(
            prepare(
                AppActionTracker.EVENT_EXTERNAL_LINK_OPEN,
                mapOf(
                    "target" to AppActionTracker.TARGET_GITHUB,
                    "api_key" to "AUDIT_DUMMY_SECRET",
                ),
                url = "/about",
            ),
        )
        assertNull(
            prepare(
                AppPageTracker.EVENT_SURFACE_OPENED,
                mapOf(
                    "surface_id" to AppPageTracker.SURFACE_CONFIG,
                    "source" to AppPageTracker.SOURCE_MAIN,
                    "screen" to "/",
                    "secret" to "x",
                ),
            ),
        )
    }

    @Test
    fun `必填缺失枚举错用与数值越界被拒绝`() {
        assertNull(
            prepare(
                TtsTelemetryTracker.EVENT_TTS_METRICS_WINDOW,
                ttsWindowData() - "provider_id",
                TelemetryOrigin.TTS_SERVICE,
                "/tts-service",
            ),
        )
        assertNull(
            prepare(
                TtsTelemetryTracker.EVENT_TTS_METRICS_WINDOW,
                ttsWindowData() + ("success_count" to -1),
                TelemetryOrigin.TTS_SERVICE,
                "/tts-service",
            ),
        )
        assertNull(
            prepare(
                TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT,
                ttsResultData() + ("status" to "great"),
                TelemetryOrigin.TTS_SERVICE,
                "/tts-service",
            ),
        )
        assertNull(
            prepare(
                AppActionTracker.EVENT_PREVIEW_PLAY_CLICK,
                mapOf(
                    "provider_id" to "openAI",
                    "voice_id" to "alloy",
                    "voice_category" to TelemetryIds.CATEGORY_BUILTIN,
                    "text_length" to 10_000_001,
                    "outcome" to AppActionTracker.OUTCOME_STARTED,
                ),
            ),
        )
    }

    @Test
    fun `自由文本与敏感字段无法借助合法键名通过`() {
        assertNull(
            prepare(
                AppActionTracker.EVENT_EXTERNAL_LINK_OPEN,
                mapOf("target" to "https://example.com/private"),
                url = "/about",
            ),
        )
        assertNull(
            prepare(
                AppActionTracker.EVENT_EXTERNAL_LINK_OPEN,
                mapOf("target" to "hello\nworld"),
                url = "/about",
            ),
        )
        assertNull(
            prepare(
                AppActionTracker.EVENT_EXTERNAL_LINK_OPEN,
                mapOf("target" to "a".repeat(65)),
                url = "/about",
            ),
        )
        assertNull(
            prepare(
                AppActionTracker.EVENT_APP_FOREGROUNDED,
                mapOf("foreground_source" to "cold", "device_model" to "bad\u0000name"),
                TelemetryOrigin.APP,
            ),
        )
    }

    @Test
    fun `崩溃帧格式与数量必须自洽`() {
        assertNull(
            prepare(
                AppActionTracker.EVENT_APP_CRASH,
                crashData() + ("frame_1" to "not a frame"),
                TelemetryOrigin.CRASH_REPAIR,
            ),
        )
        assertNull(
            prepare(
                AppActionTracker.EVENT_APP_CRASH,
                crashData() + ("frame_count" to 2),
                TelemetryOrigin.CRASH_REPAIR,
            ),
        )
        assertNull(
            prepare(
                AppActionTracker.EVENT_APP_CRASH,
                crashData() + ("exception_chain_depth" to 3),
                TelemetryOrigin.CRASH_REPAIR,
            ),
        )
    }

    @Test
    fun `模型音色分类结果必须与ID一致`() {
        assertNull(
            prepare(
                TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT,
                ttsResultData() + mapOf(
                    "model_id" to "my-private-model",
                    "model_category" to TelemetryIds.CATEGORY_CUSTOM,
                ),
                TelemetryOrigin.TTS_SERVICE,
                "/tts-service",
            ),
        )
        assertNotNull(
            prepare(
                TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT,
                ttsResultData() + mapOf(
                    "model_id" to TelemetryIds.CATEGORY_CUSTOM,
                    "model_category" to TelemetryIds.CATEGORY_CUSTOM,
                ),
                TelemetryOrigin.TTS_SERVICE,
                "/tts-service",
            ),
        )
    }

    @Test
    fun `公共属性由schema统一附加且业务数据不能覆盖`() {
        val prepared = prepare(
            TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT,
            ttsResultData(),
            TelemetryOrigin.TTS_SERVICE,
            "/tts-service",
        )
        assertNotNull(prepared)
        assertEquals(TelemetrySchema.VERSION, prepared!!.data["schema_version"])
        assertEquals(TelemetryOrigin.TTS_SERVICE, prepared.data["event_source"])
        assertTrue(prepared.wireBytes > 0)

        assertNull(
            prepare(
                AppActionTracker.EVENT_PREVIEW_VOICE_SELECTED,
                mapOf(
                    "provider_id" to "openAI",
                    "voice_id" to "alloy",
                    "voice_category" to TelemetryIds.CATEGORY_BUILTIN,
                    "schema_version" to "9",
                ),
            ),
        )
    }

    @Test
    fun `UTF8字节预算按真实编码计算`() {
        val ascii = prepare(
            AppActionTracker.EVENT_APP_FOREGROUNDED,
            mapOf("foreground_source" to "cold", "device_model" to "A".repeat(64)),
            TelemetryOrigin.APP,
        )!!
        val chinese = prepare(
            AppActionTracker.EVENT_APP_FOREGROUNDED,
            mapOf("foreground_source" to "cold", "device_model" to "华".repeat(64)),
            TelemetryOrigin.APP,
        )!!
        assertTrue(chinese.wireBytes > ascii.wireBytes + 100)
        assertTrue(chinese.wireBytes < TelemetrySchema.MAX_DATA_BYTES + 256)
    }

    @Test
    fun `pageview仅接受合法路径与结构化标题`() {
        assertNotNull(TelemetrySchema.prepare(TelemetryEvent(url = "/")))
        assertNotNull(TelemetrySchema.prepare(TelemetryEvent(url = "/about", title = "About")))
        assertNull(TelemetrySchema.prepare(TelemetryEvent(url = "https://example.com")))
        assertNull(TelemetrySchema.prepare(TelemetryEvent(url = "/about?ref=x")))
        assertNull(TelemetrySchema.prepare(TelemetryEvent(url = "/about", title = "任意用户文本")))
    }

    @Test
    fun `TTS成功状态禁止携带失败阶段且首包字段成组`() {
        assertNull(
            prepare(
                TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT,
                ttsResultData() + ("failure_stage" to TtsTelemetryTracker.STAGE_SYNTHESIS),
                TelemetryOrigin.TTS_SERVICE,
                "/tts-service",
            ),
        )
        assertNull(
            prepare(
                TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT,
                ttsResultData() + ("first_audio_ms" to 20),
                TelemetryOrigin.TTS_SERVICE,
                "/tts-service",
            ),
        )
    }

    @Test
    fun `屏幕路径校验只允许应用内路由`() {
        assertTrue(TelemetrySchema.isValidScreen("/"))
        assertTrue(TelemetrySchema.isValidScreen("/about"))
        assertTrue(TelemetrySchema.isValidScreen("/download-service"))
        assertTrue(!TelemetrySchema.isValidScreen("about"))
        assertTrue(!TelemetrySchema.isValidScreen("https://example.com"))
    }
}
