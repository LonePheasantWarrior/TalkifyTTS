package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.content.Context
import androidx.core.content.edit
import com.github.lonepheasantwarrior.talkify.BuildConfig
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 崩溃摘要本地留存与补报
 *
 * 崩溃现场**不联网**：在采集许可有效时把受限摘要同步写入独立的 SharedPreferences，
 * 随后无条件交还系统处理器。`commit` 是同步磁盘 I/O，可能耗时数十毫秒到数秒；
 * 写入失败只放弃摘要，绝不延迟或改变系统终止流程。
 *
 * 摘要只包含异常类名链、应用代码栈帧签名（类/方法/行号）、线程类型与构建版本，
 * 不含异常消息、请求内容、原始线程名或用户数据。异常链与栈帧遍历都有 identity
 * visited 集合、最大深度、最大扫描帧数和字符预算，循环 cause 不会无限遍历。
 *
 * 补报在进程初始化后由后台任务尝试，不依赖 Activity；仅当事件成功交给有界队列
 * 后才删除本地摘要，未配置/队列满/schema 拒绝会保留到下次启动。摘要固定保留
 * [PENDING_TTL_MS]，过期或损坏时清理。
 */
object CrashReporter {

    private const val TAG = "TalkifyTelemetry"
    private const val PREFS_NAME = "talkify_crash_reports"
    private const val KEY_PENDING = "pending_crash"
    private const val APP_PACKAGE_PREFIX = "com.github.lonepheasantwarrior.talkify"
    internal const val MAX_CHAIN = 3
    internal const val MAX_CAUSE_DEPTH = 8
    private const val MAX_FRAMES = 8
    private const val MAX_SCAN_FRAMES = 64
    private const val MAX_CLASS_LENGTH = 80
    private const val MAX_FRAME_LENGTH = 160
    private const val MAX_FRAME_CHARS = MAX_FRAMES * MAX_FRAME_LENGTH
    // 事件附加公共属性后仍需低于 schema 的 4KiB，摘要本身留出余量
    private const val MAX_SUMMARY_BYTES = 3 * 1024
    internal const val PENDING_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val CLASS_PATTERN = Regex("^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*$")
    private val FRAME_PATTERN =
        Regex("^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$<][A-Za-z0-9_$<>]*(?::\\d{1,7})?$")

    enum class Handoff { NONE, ACCEPTED, DROPPED, EXPIRED, CORRUPT, NOT_PERMITTED }

    data class CrashSummary(
        val exceptionClass: String,
        val exceptionChain: List<String>,
        val frames: List<String>,
        val threadType: String,
        val timestampSec: Long,
        val appVersion: String,
        val versionCode: Int,
        val buildChannel: String,
    ) {
        val exceptionChainDepth: Int get() = exceptionChain.size
    }

    /** 崩溃线程调用：只做本地受限写入，任何异常都吞掉，绝不拖延系统终止 */
    @Synchronized
    fun record(thread: Thread, throwable: Throwable) {
        val permit = TelemetryController.capturePermit()
        if (!permit.enabled) return
        try {
            val context = TalkifyAppHolder.getContext() ?: return
            val summary = buildSummary(thread, throwable)
            val payload = encodeBounded(summary) ?: return
            TelemetryController.runIfPermitCurrent(permit) {
                prefs(context).edit(commit = true) { putString(KEY_PENDING, payload) }
            }
        } catch (e: Throwable) {
            TtsLogger.w(TAG) { "崩溃摘要写入失败: ${e.javaClass.simpleName}" }
        }
    }

    /**
     * 正常启动/回到前台时调用：仅在统计许可有效时补报一次。
     *
     * 只有事件成功交给有界队列后才删除；未配置、队列满、schema 拒绝会保留摘要，
     * 但“入队后仍可能因 TTL/网络失败丢失”，不承诺可靠送达。
     */
    @Synchronized
    fun flushPendingIfAllowed(): Handoff {
        val permit = TelemetryController.capturePermit()
        if (!permit.enabled) return Handoff.NOT_PERMITTED
        val context = TalkifyAppHolder.getContext() ?: return Handoff.DROPPED
        try {
            val raw = prefs(context).getString(KEY_PENDING, null) ?: return Handoff.NONE
            if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_SUMMARY_BYTES) {
                removePending(context)
                return Handoff.CORRUPT
            }
            val summary = parse(raw)
            if (summary == null) {
                removePending(context)
                return Handoff.CORRUPT
            }
            if (isExpired(summary, System.currentTimeMillis())) {
                removePending(context)
                return Handoff.EXPIRED
            }
            if (!TelemetryController.isPermitCurrent(permit)) {
                removePending(context)
                return Handoff.NOT_PERMITTED
            }
            val accepted = AppActionTracker.appCrashSummary(summary, permit)
            if (accepted) {
                removePending(context)
                return Handoff.ACCEPTED
            }
            if (!TelemetryController.isPermitCurrent(permit)) {
                removePending(context)
                return Handoff.NOT_PERMITTED
            }
            return Handoff.DROPPED
        } catch (e: Throwable) {
            TtsLogger.w(TAG) { "崩溃摘要补报失败: ${e.javaClass.simpleName}" }
            return Handoff.DROPPED
        }
    }

    @Synchronized
    fun clearPending() {
        try {
            val context = TalkifyAppHolder.getContext() ?: return
            // 非崩溃路径不做同步磁盘删除
            prefs(context).edit { remove(KEY_PENDING) }
        } catch (_: Throwable) {
        }
    }

    internal fun buildSummary(thread: Thread, throwable: Throwable): CrashSummary {
        val chain = mutableListOf<String>()
        val visitedChain = newIdentitySet<Throwable>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && chain.size < MAX_CHAIN && depth < MAX_CAUSE_DEPTH) {
            if (!visitedChain.add(current)) break
            chain.add(sanitizeClass(current.javaClass.name))
            current = current.cause
            depth++
        }

        val appFrames = mutableListOf<String>()
        val fallbackFrames = mutableListOf<String>()
        val visitedFrames = newIdentitySet<Throwable>()
        var cursor: Throwable? = throwable
        var scanDepth = 0
        var scannedFrames = 0
        var frameChars = 0
        var budgetExhausted = false
        while (cursor != null && !budgetExhausted && scanDepth < MAX_CAUSE_DEPTH &&
            scannedFrames < MAX_SCAN_FRAMES
        ) {
            if (!visitedFrames.add(cursor)) break
            // 每访问一层都消耗 cause 深度预算，空栈异常也不能无限延伸扫描
            scanDepth++
            val trace = try {
                cursor.stackTrace
            } catch (_: Throwable) {
                emptyArray()
            }
            for (element in trace) {
                if (scannedFrames >= MAX_SCAN_FRAMES) {
                    budgetExhausted = true
                    break
                }
                scannedFrames++
                val frame = frameSignature(element)
                if (frameChars + frame.length > MAX_FRAME_CHARS) {
                    budgetExhausted = true
                    break
                }
                frameChars += frame.length
                if (appFrames.size < MAX_FRAMES && element.className.startsWith(APP_PACKAGE_PREFIX)) {
                    appFrames.add(frame)
                } else if (fallbackFrames.size < MAX_FRAMES) {
                    fallbackFrames.add(frame)
                }
            }
            // 帧/字符或深度预算已耗尽时立即停止，不再沿 cause 读取下一层
            if (budgetExhausted || scanDepth >= MAX_CAUSE_DEPTH) break
            cursor = cursor.cause
        }
        val frames = if (appFrames.isNotEmpty()) appFrames else fallbackFrames.take(MAX_FRAMES)
        return CrashSummary(
            exceptionClass = chain.firstOrNull() ?: sanitizeClass(throwable.javaClass.name),
            exceptionChain = chain,
            frames = frames,
            threadType = if (thread.name == "main") "main" else "background",
            timestampSec = System.currentTimeMillis() / 1000,
            appVersion = sanitizeVersion(BuildConfig.VERSION_NAME),
            versionCode = BuildConfig.VERSION_CODE,
            buildChannel = if (BuildConfig.DEBUG) "debug" else "release",
        )
    }

    private fun encodeBounded(summary: CrashSummary): String? {
        var frames = summary.frames
        var chain = summary.exceptionChain
        while (true) {
            val payload = encode(summary.copy(exceptionChain = chain, frames = frames))
            if (payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_SUMMARY_BYTES) return payload
            when {
                frames.isNotEmpty() -> frames = frames.dropLast(1)
                chain.size > 1 -> chain = chain.dropLast(1)
                else -> return null
            }
        }
    }

    private fun encode(summary: CrashSummary): String = JSONObject().apply {
        put("exception_class", summary.exceptionClass)
        put("exception_chain", JSONArray(summary.exceptionChain))
        put("frames", JSONArray(summary.frames))
        put("thread_type", summary.threadType)
        put("timestamp_s", summary.timestampSec)
        put("app_version", summary.appVersion)
        put("version_code", summary.versionCode)
        put("build_channel", summary.buildChannel)
    }.toString()

    /**
     * 生成既是合法类/方法签名、又不超过长度上限的帧。
     *
     * 不能直接对整串 `take(160)`：长类名被截断后可能缺少方法段，schema 会拒绝整个
     * 崩溃事件。这里优先保留方法/行号，再按剩余预算截断类名。
     */
    private fun frameSignature(element: StackTraceElement): String {
        val location = if (element.lineNumber >= 0) ":${element.lineNumber}" else ""
        val method = sanitizeIdentifier(element.methodName, allowAngle = true).take(64)
            .ifBlank { "unknown" }
        val suffix = ".$method$location"
        val budget = MAX_FRAME_LENGTH - suffix.length
        val className = sanitizeIdentifier(element.className, allowAngle = false)
            .take(budget.coerceAtLeast(1))
            .trimEnd('.')
            .ifBlank { "UnknownClass" }
        return className + suffix
    }

    private fun sanitizeClass(name: String): String =
        sanitizeIdentifier(name, allowAngle = false)
            .take(MAX_CLASS_LENGTH)
            .trimEnd('.')
            .ifBlank { "java.lang.Throwable" }

    private fun sanitizeIdentifier(value: String, allowAngle: Boolean): String =
        value.map { char ->
            val allowed = char.isLetterOrDigit() || char == '_' || char == '$' || char == '.' ||
                (allowAngle && (char == '<' || char == '>'))
            if (allowed) char else '_'
        }.joinToString("")

    private fun sanitizeVersion(value: String): String =
        value.take(32)
            .filter { it.isLetterOrDigit() || it in "._+-" }
            .ifBlank { "unknown" }

    internal fun parse(raw: String): CrashSummary? = try {
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_SUMMARY_BYTES) {
            null
        } else {
            val json = JSONObject(raw)
            val chain = json.optJSONArray("exception_chain").toStringList()
                .map { it.take(MAX_CLASS_LENGTH).trimEnd('.') }
                .filter { CLASS_PATTERN.matches(it) }
            val frames = json.optJSONArray("frames").toStringList()
                .filter { it.length <= MAX_FRAME_LENGTH && FRAME_PATTERN.matches(it) }
            val exceptionClass = json.optString("exception_class")
                .take(MAX_CLASS_LENGTH)
                .trimEnd('.')
            if (!CLASS_PATTERN.matches(exceptionClass)) {
                null
            } else {
                CrashSummary(
                    exceptionClass = exceptionClass,
                    exceptionChain = chain.ifEmpty { listOf(exceptionClass) }.take(MAX_CHAIN),
                    frames = frames.take(MAX_FRAMES),
                    threadType = json.optString("thread_type")
                        .takeIf { it == "main" || it == "background" } ?: "background",
                    timestampSec = json.optLong("timestamp_s", 0),
                    appVersion = sanitizeVersion(json.optString("app_version")),
                    versionCode = json.optInt("version_code", 0)
                        .coerceIn(0, 2_100_000_000),
                    buildChannel = json.optString("build_channel")
                        .takeIf { it == "debug" || it == "release" } ?: "release",
                ).takeIf { it.timestampSec > 0 }
            }
        }
    } catch (e: Exception) {
        TtsLogger.w(TAG) { "崩溃摘要解析失败: ${e.javaClass.simpleName}" }
        null
    }

    internal fun isExpired(summary: CrashSummary, nowWallMs: Long): Boolean {
        val recordedMs = if (summary.timestampSec > Long.MAX_VALUE / 1000) {
            Long.MAX_VALUE
        } else {
            summary.timestampSec * 1000
        }
        return recordedMs <= 0 ||
            nowWallMs - recordedMs > PENDING_TTL_MS ||
            recordedMs - nowWallMs > PENDING_TTL_MS
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val limit = minOf(length(), MAX_FRAMES)
        return (0 until limit).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }?.take(MAX_FRAME_LENGTH)
        }
    }

    private fun removePending(context: Context) {
        try {
            prefs(context).edit { remove(KEY_PENDING) }
        } catch (_: Throwable) {
        }
    }

    private fun <T> newIdentitySet(): MutableSet<T> =
        Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("context unavailable")
}
