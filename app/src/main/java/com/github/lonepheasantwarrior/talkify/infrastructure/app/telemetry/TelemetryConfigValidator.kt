package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * 遥测端点/网站 ID 的严格校验（运行时与构建脚本使用同一套规则）
 *
 * 构建脚本无法直接调用本类，但 `app/build.gradle.kts` 中的
 * `validateTelemetryConfig` 与这里保持逐条等价，并由构建矩阵任务验证。
 */
internal object TelemetryConfigValidator {

    enum class Result { NONE, INVALID, VALID }

    private val UUID_PATTERN =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val INVALID_PERCENT_ESCAPE = Regex("%(?![0-9a-fA-F]{2})")

    fun validate(endpoint: String, websiteId: String): Result {
        val rawEndpoint = endpoint.trim()
        val rawWebsiteId = websiteId.trim()
        if (rawEndpoint.isBlank() || rawWebsiteId.isBlank()) return Result.NONE
        if (!UUID_PATTERN.matches(rawWebsiteId)) return Result.INVALID
        if (runCatching { UUID.fromString(rawWebsiteId) }.isFailure) return Result.INVALID
        if (rawEndpoint.any { it.isWhitespace() || it.isISOControl() }) return Result.INVALID
        // OkHttp 会把裸 % 自动转义为 %25，这里显式拒绝非法百分号转义，与构建侧 URI 校验一致
        if (INVALID_PERCENT_ESCAPE.containsMatchIn(rawEndpoint)) return Result.INVALID
        val url = rawEndpoint.toHttpUrlOrNull() ?: return Result.INVALID
        if (url.scheme != "https") return Result.INVALID
        if (url.host.isBlank()) return Result.INVALID
        if (url.port !in 1..65535) return Result.INVALID
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return Result.INVALID
        if (url.query != null || url.fragment != null) return Result.INVALID
        return Result.VALID
    }

    fun normalizeEndpoint(endpoint: String): String =
        endpoint.trim().toHttpUrlOrNull()?.toString()?.trimEnd('/')
            ?: endpoint.trim().trimEnd('/')
}
