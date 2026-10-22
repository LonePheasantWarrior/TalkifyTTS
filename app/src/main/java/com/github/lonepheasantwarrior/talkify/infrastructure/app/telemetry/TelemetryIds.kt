package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds

/**
 * 模型/音色标识的隐私分类
 *
 * 用户可自定义模型 ID、音色 ID 或第三方平台声音 ID。此类值可能包含私有名称并无限
 * 增加统计维度，因此默认归为 `custom`；只有供应商默认值或内置白名单 ID 才原样上报。
 */
object TelemetryIds {

    const val CATEGORY_DEFAULT = "default"
    const val CATEGORY_BUILTIN = "builtin"
    const val CATEGORY_CUSTOM = "custom"
    const val CATEGORY_UNKNOWN = "unknown"

    private const val MAX_ID_LENGTH = 64
    private const val MAX_EXCEPTION_LENGTH = 80

    data class ClassifiedId(val value: String, val category: String)

    fun model(
        rawModelId: String?,
        defaultModelId: String?,
        knownModelIds: Collection<String> = emptyList(),
    ): ClassifiedId {
        val raw = rawModelId?.trim().orEmpty()
        if (raw.isBlank()) return ClassifiedId(CATEGORY_DEFAULT, CATEGORY_DEFAULT)
        if (raw == defaultModelId) return ClassifiedId(raw.take(MAX_ID_LENGTH), CATEGORY_DEFAULT)
        if (knownModelIds.contains(raw)) return ClassifiedId(raw.take(MAX_ID_LENGTH), CATEGORY_BUILTIN)
        return ClassifiedId(CATEGORY_CUSTOM, CATEGORY_CUSTOM)
    }

    /** 供应商 ID 必须属于注册表；历史/损坏配置归为 unknown，避免整条 TTS 事件被 schema 拒绝 */
    fun provider(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (ProviderIds.entries.any { it.providerId == trimmed }) trimmed else CATEGORY_UNKNOWN
    }

    /** 异常类名只保留可打印的 Java 类名字符，避免自由文本/XSS 类内容进入事件 */
    internal fun exceptionClass(raw: String): String =
        raw.take(MAX_EXCEPTION_LENGTH).map { char ->
            if (char.isLetterOrDigit() || char == '_' || char == '$' || char == '.') char else '_'
        }.joinToString("").trimEnd('.').ifBlank { "java.lang.Throwable" }

    fun voice(
        rawVoiceId: String?,
        isKnown: (String) -> Boolean,
        forcedCustom: Boolean = false,
    ): ClassifiedId {
        val raw = rawVoiceId?.trim().orEmpty()
        if (forcedCustom) return ClassifiedId(CATEGORY_CUSTOM, CATEGORY_CUSTOM)
        if (raw.isBlank()) return ClassifiedId(CATEGORY_DEFAULT, CATEGORY_DEFAULT)
        return if (isKnown(raw)) {
            ClassifiedId(raw.take(MAX_ID_LENGTH), CATEGORY_BUILTIN)
        } else {
            ClassifiedId(CATEGORY_CUSTOM, CATEGORY_CUSTOM)
        }
    }
}
