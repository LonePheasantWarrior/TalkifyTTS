package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.os.Build
import com.github.lonepheasantwarrior.talkify.BuildConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * 遥测事件的数据契约与运行时约束
 *
 * 不是“通用 Map 过滤器”：每个事件都声明**允许键、必填键、精确类型、枚举/格式与
 * 数值范围**；未知键一律拒绝。普通字符串没有可靠的自动自由文本识别方法，因此所有
 * 可能夹带用户内容的字段都改为有限枚举或结构化签名（ID 正则、版本号、崩溃帧）。
 *
 * 体积限制按组装公共属性后的**最终 UTF-8 JSON 字节数**执行，而不是 Kotlin 字符数；
 * 队列预算使用同一字节数。
 */
internal object TelemetrySchema {

    const val VERSION = "2"

    const val MAX_DATA_BYTES = 4 * 1024
    private const val MAX_KEYS = 32
    private const val MAX_FRAME_LENGTH = 160
    private const val MAX_CLASS_LENGTH = 80
    private const val WIRE_OVERHEAD_BYTES = 256

    private val NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val SCREEN_PATTERN = Regex("^/[a-z0-9-]{0,31}$")
    private val SAFE_ID_PATTERN = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val SAFE_MODEL_ID_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val ERROR_CODE_PATTERN = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val VERSION_PATTERN = Regex("^[0-9A-Za-z._+-]{1,32}$")
    private val COUNTRY_PATTERN = Regex("^[A-Z]{2}$")
    private val SIZE_DISPLAY_PATTERN = Regex("^~?\\d{1,5}(\\.\\d{1,2})? (B|KB|MB|GB)$")
    private val PAGE_TITLE_PATTERN = Regex("^[A-Za-z0-9 ._-]{1,32}$")
    private val EXCEPTION_CLASS_PATTERN =
        Regex("^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*$")
    private val FRAME_PATTERN =
        Regex("^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$<][A-Za-z0-9_$<>]*(?::\\d{1,7})?$")

    private val ORIGINS = setOf(
        TelemetryOrigin.UI,
        TelemetryOrigin.APP,
        TelemetryOrigin.STARTUP,
        TelemetryOrigin.TTS_SERVICE,
        TelemetryOrigin.DOWNLOAD_SERVICE,
        TelemetryOrigin.CRASH_REPAIR,
    )

    private val RESERVED_COMMON_KEYS = setOf(
        "schema_version",
        "event_source",
        "build_channel",
        "app_version",
        "os_version",
    )

    private val PROVIDER_IDS =
        ProviderIds.entries.map { it.providerId }.toSet() + TelemetryIds.CATEGORY_UNKNOWN
    private val MODEL_CATEGORIES = setOf(
        TelemetryIds.CATEGORY_DEFAULT,
        TelemetryIds.CATEGORY_BUILTIN,
        TelemetryIds.CATEGORY_CUSTOM,
        TelemetryIds.CATEGORY_UNKNOWN,
    )
    /** 窗口聚合在维度溢出时归入 other；空维度归入 unknown */
    private val AGGREGATE_PROVIDER_IDS = PROVIDER_IDS + setOf("other", "unknown")
    private val AGGREGATE_MODEL_CATEGORIES = MODEL_CATEGORIES + "other"
    private val THREAD_TYPES = setOf("main", "background")
    private val SURFACE_SOURCES = setOf("fab", "main", "about", "startup", "manual", "config_sheet", "preview")
    private val DIALOG_SOURCES = setOf("config_sheet", "preview")
    private val TRIGGERS = setOf(AppActionTracker.TRIGGER_STARTUP, AppActionTracker.TRIGGER_MANUAL)
    private val TTS_STATUSES = setOf(
        TtsTelemetryTracker.STATUS_SUCCESS,
        TtsTelemetryTracker.STATUS_ERROR,
        TtsTelemetryTracker.STATUS_TIMEOUT,
        TtsTelemetryTracker.STATUS_CANCELLED,
        TtsTelemetryTracker.STATUS_IGNORED,
    )
    private val PREVIEW_STATUSES = setOf(
        AppActionTracker.STATUS_SUCCESS,
        AppActionTracker.STATUS_STOPPED,
        AppActionTracker.STATUS_ERROR,
    )
    private val DOWNLOAD_STATUSES = setOf(
        AppActionTracker.STATUS_COMPLETED,
        AppActionTracker.STATUS_FAILED,
        AppActionTracker.STATUS_CANCELLED,
    )
    private val FAILURE_STAGES = setOf(
        TtsTelemetryTracker.STAGE_VALIDATION,
        TtsTelemetryTracker.STAGE_PREPARATION,
        TtsTelemetryTracker.STAGE_SYNTHESIS,
        TtsTelemetryTracker.STAGE_FRAMEWORK_DELIVERY,
    )
    private val DOWNLOAD_FAILURE_CODES = setOf(
        "dir_create", "disk_space", "file_download", "archive_download",
        "archive_extract", "archive_layout", "file_verify", "md5_mismatch",
    )
    private val DURATION_BUCKETS = setOf("<1s", "1-3s", "3-10s", "10-30s", "30-60s", ">30s", ">60s")
    private val NETWORK_TYPES = setOf("vpn", "wifi", "cellular", "ethernet")
    private val DENSITY_BUCKETS = setOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

    private sealed interface Rule
    private data class TextRule(
        val maxLength: Int = 64,
        val pattern: Regex? = null,
    ) : Rule

    private data class EnumRule(val values: Set<String>) : Rule
    private data class NumberRule(val min: Long, val max: Long) : Rule
    private data object FlagRule : Rule

    private data class EventSpec(
        val required: Map<String, Rule>,
        val optional: Map<String, Rule> = emptyMap(),
        val origins: Set<String>? = null,
    )

    private val TEXT_64 = TextRule(64)
    private val SAFE_ID = TextRule(64, SAFE_ID_PATTERN)
    private val MODEL_RAW_ID = TextRule(64, SAFE_MODEL_ID_PATTERN)
    private val ERROR_CODE = TextRule(64, ERROR_CODE_PATTERN)
    private val VERSION_RULE = TextRule(32, VERSION_PATTERN)
    private val EXCEPTION_CLASS = TextRule(MAX_CLASS_LENGTH, EXCEPTION_CLASS_PATTERN)
    private val FRAME = TextRule(MAX_FRAME_LENGTH, FRAME_PATTERN)
    private val SCREEN = TextRule(32, SCREEN_PATTERN)
    private val COUNT = NumberRule(0, 1_000_000)
    private val DURATION_MS = NumberRule(0, 86_400_000)
    private val TIMESTAMP_S = NumberRule(0, 4_102_444_800)
    private val AUDIO_BYTES = NumberRule(0, 1_000_000_000_000)
    private val TEXT_CHARS = NumberRule(0, 10_000_000_000)
    private val MS_TOTAL = NumberRule(0, 10_000_000_000)

    private val CHAIN_KEYS = (2..4).associate { "exception_$it" to EXCEPTION_CLASS }
    private val FRAME_KEYS = (1..8).associate { "frame_$it" to FRAME }
    private val DURATION_KEYS =
        TtsMetricsAggregator.DURATION_BUCKETS.associate { "duration_$it" to COUNT }
    private val FIRST_AUDIO_KEYS =
        TtsMetricsAggregator.FIRST_AUDIO_BUCKETS.associate { "first_audio_$it" to COUNT }

    private val EVENT_SPECS: Map<String, EventSpec> = mapOf(
        AppActionTracker.EVENT_APP_FOREGROUNDED to EventSpec(
            required = mapOf(
                "foreground_source" to EnumRule(
                    setOf(AppActionTracker.FOREGROUND_COLD, AppActionTracker.FOREGROUND_RESUME),
                ),
            ),
            optional = mapOf(
                "device_model" to TEXT_64,
                "device_brand" to TEXT_64,
                "manufacturer" to TEXT_64,
                "device_codename" to TEXT_64,
                "soc_platform" to TEXT_64,
                "screen_density" to EnumRule(DENSITY_BUCKETS),
                "client_country" to TextRule(2, COUNTRY_PATTERN),
                "client_country_source" to EnumRule(setOf("sim", "locale")),
                "total_ram_mb" to NumberRule(0, 1_048_576),
                "is_low_ram_device" to FlagRule,
                "network_type" to EnumRule(NETWORK_TYPES),
            ),
            origins = setOf(TelemetryOrigin.APP),
        ),
        AppPageTracker.EVENT_SURFACE_OPENED to EventSpec(
            required = mapOf(
                "surface_id" to EnumRule(
                    setOf(
                        AppPageTracker.SURFACE_CONFIG,
                        AppPageTracker.SURFACE_PROVIDER_SELECT,
                        AppPageTracker.SURFACE_DONATE,
                        AppPageTracker.SURFACE_PRIVACY,
                        AppPageTracker.SURFACE_MODEL_DOWNLOAD_CONFIRM,
                        AppPageTracker.SURFACE_UPDATE,
                        AppPageTracker.SURFACE_NETWORK_BLOCKED,
                        AppPageTracker.SURFACE_NOTIFICATION_PERMISSION,
                        AppPageTracker.SURFACE_BATTERY_OPTIMIZATION,
                    ),
                ),
                "source" to EnumRule(SURFACE_SOURCES),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_PREVIEW_PLAY_CLICK to EventSpec(
            required = mapOf(
                "provider_id" to EnumRule(PROVIDER_IDS),
                "voice_id" to SAFE_ID,
                "voice_category" to EnumRule(MODEL_CATEGORIES),
                "text_length" to NumberRule(0, 10_000_000),
                "outcome" to EnumRule(
                    setOf(
                        AppActionTracker.OUTCOME_STARTED,
                        AppActionTracker.OUTCOME_EMPTY_TEXT,
                        AppActionTracker.OUTCOME_NOT_CONFIGURED,
                        AppActionTracker.OUTCOME_MODEL_NEED_DOWNLOAD,
                        AppActionTracker.OUTCOME_MODEL_DOWNLOADING,
                    ),
                ),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_PREVIEW_PLAYBACK to EventSpec(
            required = mapOf(
                "provider_id" to EnumRule(PROVIDER_IDS),
                "model_id" to SAFE_ID,
                "model_category" to EnumRule(MODEL_CATEGORIES),
                "voice_id" to SAFE_ID,
                "voice_category" to EnumRule(MODEL_CATEGORIES),
                "text_length" to NumberRule(0, 10_000_000),
                "status" to EnumRule(PREVIEW_STATUSES),
                "duration_ms" to DURATION_MS,
                "duration_bucket" to EnumRule(DURATION_BUCKETS),
            ),
            optional = mapOf(
                "error_code" to ERROR_CODE,
                "first_audio_ms" to DURATION_MS,
                "sample_rate" to NumberRule(0, 384_000),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_PREVIEW_VOICE_SELECTED to EventSpec(
            required = mapOf(
                "provider_id" to EnumRule(PROVIDER_IDS),
                "voice_id" to SAFE_ID,
                "voice_category" to EnumRule(MODEL_CATEGORIES),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_PROVIDER_SWITCHED to EventSpec(
            required = mapOf(
                "from_provider" to EnumRule(PROVIDER_IDS),
                "to_provider" to EnumRule(PROVIDER_IDS),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_CONFIG_SAVED to EventSpec(
            required = mapOf(
                "provider_id" to EnumRule(PROVIDER_IDS),
                "is_local_model" to FlagRule,
                "has_custom_api_url" to FlagRule,
                "has_custom_model_id" to FlagRule,
                "voice_id" to SAFE_ID,
                "voice_category" to EnumRule(MODEL_CATEGORIES),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_MODEL_DOWNLOAD_DIALOG to EventSpec(
            required = mapOf(
                "model_id" to MODEL_RAW_ID,
                "source" to EnumRule(DIALOG_SOURCES),
                "action" to EnumRule(setOf(AppActionTracker.ACTION_CONFIRM, AppActionTracker.ACTION_DISMISS)),
            ),
            optional = mapOf("size_display" to TextRule(24, SIZE_DISPLAY_PATTERN)),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_MODEL_DOWNLOAD_START to EventSpec(
            required = mapOf(
                "model_id" to MODEL_RAW_ID,
                "file_count" to NumberRule(0, 10_000),
                "total_size_mb" to NumberRule(0, 10_000_000),
            ),
            origins = setOf(TelemetryOrigin.DOWNLOAD_SERVICE),
        ),
        AppActionTracker.EVENT_MODEL_DOWNLOAD_RESULT to EventSpec(
            required = mapOf(
                "model_id" to MODEL_RAW_ID,
                "status" to EnumRule(DOWNLOAD_STATUSES),
                "duration_ms" to DURATION_MS,
                "duration_bucket" to EnumRule(DURATION_BUCKETS),
                "progress" to NumberRule(0, 100),
            ),
            optional = mapOf("failure_code" to EnumRule(DOWNLOAD_FAILURE_CODES)),
            origins = setOf(TelemetryOrigin.DOWNLOAD_SERVICE),
        ),
        AppActionTracker.EVENT_UPDATE_CHECK to EventSpec(
            required = mapOf(
                "trigger" to EnumRule(TRIGGERS),
                "result" to EnumRule(
                    setOf(
                        AppActionTracker.RESULT_UPDATE_AVAILABLE,
                        AppActionTracker.RESULT_NO_UPDATE,
                        AppActionTracker.RESULT_TIMEOUT,
                        AppActionTracker.RESULT_NETWORK_ERROR,
                        AppActionTracker.RESULT_SERVER_ERROR,
                        AppActionTracker.RESULT_PARSE_ERROR,
                    ),
                ),
                "duration_ms" to DURATION_MS,
            ),
            optional = mapOf("latest_version" to VERSION_RULE),
            origins = setOf(TelemetryOrigin.UI, TelemetryOrigin.STARTUP),
        ),
        AppActionTracker.EVENT_UPDATE_DIALOG_ACTION to EventSpec(
            required = mapOf(
                "source" to EnumRule(TRIGGERS),
                "action" to EnumRule(
                    setOf(AppActionTracker.ACTION_UPDATE_NOW, AppActionTracker.ACTION_REMIND_LATER),
                ),
                "version" to VERSION_RULE,
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_DONATE_CHANNEL_CLICK to EventSpec(
            required = mapOf(
                "channel" to EnumRule(
                    setOf(AppActionTracker.CHANNEL_WECHAT, AppActionTracker.CHANNEL_ALIPAY),
                ),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_DONATE_QR_SAVED to EventSpec(
            required = mapOf(
                "channel" to EnumRule(
                    setOf(AppActionTracker.CHANNEL_WECHAT, AppActionTracker.CHANNEL_ALIPAY),
                ),
                "result" to EnumRule(
                    setOf(AppActionTracker.RESULT_SUCCESS, AppActionTracker.RESULT_FAILED),
                ),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_EXTERNAL_LINK_OPEN to EventSpec(
            required = mapOf(
                "target" to EnumRule(
                    setOf(AppActionTracker.TARGET_GITHUB, AppActionTracker.TARGET_PRIVACY_POLICY),
                ),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_QQ_GROUP_COPIED to EventSpec(
            required = emptyMap(),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_STARTUP_NETWORK_ACTION to EventSpec(
            required = mapOf(
                "action" to EnumRule(
                    setOf(AppActionTracker.ACTION_OPEN_SETTINGS, AppActionTracker.ACTION_ACKNOWLEDGED),
                ),
                "offline_capable" to FlagRule,
            ),
            origins = setOf(TelemetryOrigin.STARTUP),
        ),
        AppActionTracker.EVENT_NOTIFICATION_PERMISSION to EventSpec(
            required = mapOf(
                "action" to EnumRule(
                    setOf(
                        AppActionTracker.ACTION_GRANTED,
                        AppActionTracker.ACTION_DENIED,
                        AppActionTracker.ACTION_REQUESTED,
                        AppActionTracker.ACTION_SKIPPED,
                    ),
                ),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_BATTERY_OPTIMIZATION to EventSpec(
            required = mapOf(
                "action" to EnumRule(
                    setOf(AppActionTracker.ACTION_GO_SETTINGS, AppActionTracker.ACTION_SKIPPED),
                ),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_SETTINGS_JUMP to EventSpec(
            required = mapOf(
                "target" to EnumRule(
                    setOf(AppActionTracker.TARGET_TTS, AppActionTracker.TARGET_NOTIFICATION),
                ),
                "source" to EnumRule(
                    setOf(AppActionTracker.SOURCE_DEFAULT_BANNER, AppActionTracker.SOURCE_PERMISSION_DIALOG),
                ),
            ),
            origins = setOf(TelemetryOrigin.UI),
        ),
        AppActionTracker.EVENT_APP_CRASH to EventSpec(
            required = mapOf(
                "exception_class" to EXCEPTION_CLASS,
                "exception_chain_depth" to NumberRule(1, 3),
                "frame_count" to NumberRule(0, 8),
                "thread_type" to EnumRule(THREAD_TYPES),
                "crash_timestamp_s" to TIMESTAMP_S,
                "crash_app_version" to VERSION_RULE,
                "crash_version_code" to NumberRule(0, 2_100_000_000),
                "crash_build_channel" to EnumRule(setOf("debug", "release")),
            ),
            optional = CHAIN_KEYS + FRAME_KEYS,
            origins = setOf(TelemetryOrigin.CRASH_REPAIR),
        ),
        AppActionTracker.EVENT_RX_SUPPRESSED to EventSpec(
            required = mapOf(
                "exception_class" to EXCEPTION_CLASS,
                "thread_type" to EnumRule(THREAD_TYPES),
            ),
            origins = setOf(TelemetryOrigin.APP),
        ),
        TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT to EventSpec(
            required = mapOf(
                "provider_id" to EnumRule(PROVIDER_IDS),
                "model_id" to SAFE_ID,
                "model_category" to EnumRule(MODEL_CATEGORIES),
                "voice_id" to SAFE_ID,
                "voice_category" to EnumRule(MODEL_CATEGORIES),
                "text_length" to NumberRule(0, 10_000_000),
                "status" to EnumRule(TTS_STATUSES),
                "duration_ms" to DURATION_MS,
                "duration_bucket" to EnumRule(DURATION_BUCKETS),
                "has_audio" to FlagRule,
                "audio_bytes" to AUDIO_BYTES,
                "provider_completed" to FlagRule,
                "sampled" to FlagRule,
            ),
            optional = mapOf(
                "failure_stage" to EnumRule(FAILURE_STAGES),
                "error_code" to ERROR_CODE,
                "prepare_ms" to DURATION_MS,
                "synthesis_ms" to DURATION_MS,
                "first_audio_ms" to DURATION_MS,
                "sample_rate" to NumberRule(0, 384_000),
            ),
            origins = setOf(TelemetryOrigin.TTS_SERVICE),
        ),
        TtsTelemetryTracker.EVENT_TTS_METRICS_WINDOW to EventSpec(
            required = mapOf(
                "provider_id" to EnumRule(AGGREGATE_PROVIDER_IDS),
                "model_category" to EnumRule(AGGREGATE_MODEL_CATEGORIES),
                "window_start_s" to TIMESTAMP_S,
                "window_end_s" to TIMESTAMP_S,
                "request_count" to COUNT,
                "success_count" to COUNT,
                "error_count" to COUNT,
                "timeout_count" to COUNT,
                "cancelled_count" to COUNT,
                "ignored_count" to COUNT,
                "has_audio_count" to COUNT,
                "provider_completed_count" to COUNT,
                "text_chars" to TEXT_CHARS,
                "audio_bytes" to AUDIO_BYTES,
                "prepare_ms_total" to MS_TOTAL,
                "synthesis_ms_total" to MS_TOTAL,
                "first_audio_missing" to COUNT,
            ) + DURATION_KEYS + FIRST_AUDIO_KEYS,
            optional = mapOf("has_other_dimensions" to FlagRule),
            origins = setOf(TelemetryOrigin.TTS_SERVICE),
        ),
    )

    fun isKnownEvent(name: String): Boolean = EVENT_SPECS.containsKey(name)

    /**
     * 校验并组装最终事件（含公共属性与实际线上字节数）；返回 null 表示拒绝。
     */
    fun prepare(event: TelemetryEvent): TelemetryEvent? {
        if (event.origin !in ORIGINS) return null
        if (event.type == "event" && event.name == null) return preparePageView(event)
        if (event.type == "identify") {
            if (event.name != null || event.data.isNotEmpty()) return null
            if (!SCREEN_PATTERN.matches(event.url)) return null
            return finish(event.copy(data = commonData(event.origin)), includeCommon = true)
        }
        if (event.type != "event") return null
        val name = event.name ?: return null
        val spec = EVENT_SPECS[name] ?: return null
        if (spec.origins != null && event.origin !in spec.origins) return null
        if (!SCREEN_PATTERN.matches(event.url)) return null
        val data = validateData(event.data, spec) ?: return null
        if (!validateCrossFields(name, data)) return null
        val fullData = LinkedHashMap<String, Any>(data.size + RESERVED_COMMON_KEYS.size).apply {
            putAll(data)
            putAll(commonData(event.origin))
        }
        return finish(
            event.copy(name = name, data = fullData, title = null),
            includeCommon = true,
        )
    }

    private fun preparePageView(event: TelemetryEvent): TelemetryEvent? {
        if (event.origin != TelemetryOrigin.UI) return null
        if (event.data.isNotEmpty()) return null
        if (!SCREEN_PATTERN.matches(event.url)) return null
        val title = event.title?.let { normalizeText(it, TextRule(32, PAGE_TITLE_PATTERN)) ?: return null }
        return finish(event.copy(type = "event", name = null, data = emptyMap(), title = title), includeCommon = false)
    }

    private fun finish(event: TelemetryEvent, includeCommon: Boolean): TelemetryEvent? {
        val dataBytes = if (event.data.isEmpty()) {
            0
        } else {
            JSONObject(event.data).toString().toByteArray(StandardCharsets.UTF_8).size
        }
        if (includeCommon && dataBytes > MAX_DATA_BYTES) return null
        if (dataBytes < 0) return null
        val wireBytes = WIRE_OVERHEAD_BYTES +
            event.url.toByteArray(StandardCharsets.UTF_8).size +
            (event.title?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) +
            (event.name?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) +
            dataBytes
        return event.copy(wireBytes = wireBytes)
    }

    private fun validateData(data: Map<String, Any>, spec: EventSpec): Map<String, Any>? {
        if (data.size > MAX_KEYS) return null
        for (required in spec.required.keys) {
            if (!data.containsKey(required)) return null
        }
        val sanitized = LinkedHashMap<String, Any>(data.size)
        for ((key, value) in data) {
            if (key == "screen") {
                val normalized = validateValue(key, value, SCREEN) ?: return null
                sanitized[key] = normalized
                continue
            }
            if (key in RESERVED_COMMON_KEYS) return null
            val rule = spec.required[key] ?: spec.optional[key] ?: return null
            sanitized[key] = validateValue(key, value, rule) ?: return null
        }
        return sanitized
    }

    private fun validateValue(key: String, value: Any, rule: Rule): Any? = when (rule) {
        is TextRule -> (value as? String)?.let { normalizeText(it, rule) }
        is EnumRule -> (value as? String)?.takeIf { it in rule.values }
        is NumberRule -> {
            val number = when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            } ?: return null
            if (number !in rule.min..rule.max) return null
            if (value is Long) value else value as Int
        }
        FlagRule -> when (value) {
            is Int -> value.takeIf { it == 0 || it == 1 }
            is Long -> value.takeIf { it == 0L || it == 1L }?.toInt()
            else -> null
        }
    }

    private fun normalizeText(value: String, rule: TextRule): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it == '\n' || it == '\r' || it == '\u0000' || it.isISOControl() }) return null
        if (trimmed.length > rule.maxLength) return null
        if (trimmed.contains("://")) return null
        if (rule.pattern != null && !rule.pattern.matches(trimmed)) return null
        return trimmed
    }

    private fun validateCrossFields(name: String, data: Map<String, Any>): Boolean {
        if (!validateClassifiedPair(data, "model")) return false
        if (!validateClassifiedPair(data, "voice")) return false
        return when (name) {
            AppActionTracker.EVENT_APP_CRASH -> {
                val frames = FRAME_KEYS.keys.count { data.containsKey(it) }
                val chain = CHAIN_KEYS.keys.count { data.containsKey(it) } + 1
                val contiguousFrames = (1..frames).all { data.containsKey("frame_$it") }
                val contiguousChain = (2..chain).all { data.containsKey("exception_$it") }
                number(data["frame_count"]) == frames.toLong() &&
                    number(data["exception_chain_depth"]) == chain.toLong() &&
                    contiguousFrames && contiguousChain
            }
            TtsTelemetryTracker.EVENT_TTS_REQUEST_RESULT -> {
                val status = data["status"]
                when (status) {
                    TtsTelemetryTracker.STATUS_SUCCESS -> !data.containsKey("failure_stage")
                    TtsTelemetryTracker.STATUS_IGNORED -> true
                    else -> data.containsKey("failure_stage")
                } && groupPresence(data, "first_audio_ms", "sample_rate")
            }
            TtsTelemetryTracker.EVENT_TTS_METRICS_WINDOW -> {
                val request = number(data["request_count"])
                val accounted = listOf("success_count", "error_count", "timeout_count", "cancelled_count")
                    .sumOf { number(data[it]) }
                val start = number(data["window_start_s"])
                val end = number(data["window_end_s"])
                val hasAudio = number(data["has_audio_count"])
                val completed = number(data["provider_completed_count"])
                accounted == request && start <= end && hasAudio <= request && completed <= request
            }
            else -> true
        }
    }

    private fun validateClassifiedPair(data: Map<String, Any>, prefix: String): Boolean {
        val hasId = data.containsKey("${prefix}_id")
        val hasCategory = data.containsKey("${prefix}_category")
        // 仅当两者同时出现时校验分类一致性；下载类事件只有受正则约束的原始模型 ID
        if (!hasId || !hasCategory) return true
        val id = data["${prefix}_id"] as? String ?: return false
        val category = data["${prefix}_category"] as? String ?: return false
        return when (category) {
            TelemetryIds.CATEGORY_CUSTOM, TelemetryIds.CATEGORY_UNKNOWN -> id == category
            TelemetryIds.CATEGORY_DEFAULT, TelemetryIds.CATEGORY_BUILTIN ->
                id != TelemetryIds.CATEGORY_CUSTOM &&
                    id != TelemetryIds.CATEGORY_UNKNOWN &&
                    SAFE_ID_PATTERN.matches(id)
            else -> false
        }
    }

    private fun groupPresence(data: Map<String, Any>, first: String, second: String): Boolean =
        data.containsKey(first) == data.containsKey(second)

    private fun number(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> Long.MIN_VALUE
    }

    private fun commonData(origin: String): Map<String, Any> = linkedMapOf(
        "schema_version" to VERSION,
        "event_source" to origin,
        "build_channel" to if (BuildConfig.DEBUG) "debug" else "release",
        "app_version" to BuildConfig.VERSION_NAME.ifBlank { "unknown" },
        "os_version" to Build.VERSION.RELEASE.orEmpty().take(32).ifBlank { "unknown" },
    )

    fun isValidScreen(screen: String): Boolean = SCREEN_PATTERN.matches(screen) && screen.length <= 32
}

/**
 * 待发送的遥测事件（入队前完成 schema 校验）
 *
 * @param type event / identify
 * @param name 自定义事件名；pageview 与 identify 为 null
 * @param url  页面路径（pageview 必填；事件缺省为当前真实 screen）
 * @param origin 事件来源：ui / tts_service / download_service / startup / app / crash_repair
 * @param wireBytes 校验后估算的最终请求体积（用于队列字节预算）
 */
internal data class TelemetryEvent(
    val type: String = "event",
    val name: String? = null,
    val url: String = "/",
    val title: String? = null,
    val origin: String = TelemetryOrigin.UI,
    val data: Map<String, Any> = emptyMap(),
    val wireBytes: Int = 0,
)

internal object TelemetryOrigin {
    const val UI = "ui"
    const val APP = "app"
    const val STARTUP = "startup"
    const val TTS_SERVICE = "tts_service"
    const val DOWNLOAD_SERVICE = "download_service"
    const val CRASH_REPAIR = "crash_repair"
}
