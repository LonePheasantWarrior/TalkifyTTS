package com.github.lonepheasantwarrior.talkify.domain.model.multirole

/**
 * 多角色朗读的领域模型。
 *
 * 设计约束（见 doc/多角色支持功能实现文档.md 第 3 节）：
 * - `ProfileId` / `RoleId` 是稳定随机标识，名称/下标/时间戳都不参与身份判定；
 * - 方案只保存音色稳定 ID（[VoiceRef]），显示名仅用于展示；
 * - 台词符号以 Unicode 码点保存，避免 UTF-16 代理对在边界处被切断；
 * - 默认关闭；没有有效方案时不能启用。
 */

@JvmInline
value class ProfileId(val value: String)

@JvmInline
value class RoleId(val value: String)

/** 音色稳定引用；名称不参与身份判定，展示时再经 VoiceRepository 解析。 */
data class VoiceRef(val voiceId: String)

/** 一对台词符号；[open] / [close] 为 Unicode 码点。开闭允许相同（英文 `"`）。 */
data class DelimiterPair(
    val open: Int,
    val close: Int,
    val enabled: Boolean = true,
)

data class Role(
    val id: RoleId,
    val name: String,
    val aliases: List<String> = emptyList(),
    val voice: VoiceRef? = null,
)

data class ReadingProfile(
    val id: ProfileId,
    val name: String,
    val providerId: String,
    val narratorVoice: VoiceRef? = null,
    val defaultRoleVoice: VoiceRef? = null,
    val roles: List<Role> = emptyList(),
    val delimiters: List<DelimiterPair> = MultiRoleSettings.defaultDelimiterPairs(),
)

data class MultiRoleSettings(
    val enabled: Boolean = false,
    val activeProfileId: ProfileId? = null,
    val profiles: List<ReadingProfile> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    fun activeProfile(): ReadingProfile? =
        activeProfileId?.let { id -> profiles.firstOrNull { it.id == id } }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /** 新建方案的默认符号：中文双引号、直角引号、英文双引号。 */
        fun defaultDelimiterPairs(): List<DelimiterPair> = listOf(
            DelimiterPair(0x201C, 0x201D), // “ ”
            DelimiterPair(0x300C, 0x300D), // 「 」
            DelimiterPair(0x0022, 0x0022), // " "
        )
    }
}

/** 可选预设符号对（新建/编辑时展示；默认仅启用 [MultiRoleSettings.defaultDelimiterPairs]）。 */
data class DelimiterPreset(
    val open: Int,
    val close: Int,
    val label: String,
)

object MultiRoleLimits {
    const val MAX_PROFILES = 20
    const val MAX_ROLES_PER_PROFILE = 100
    const val MAX_ALIASES_PER_ROLE = 10
    const val MAX_NAME_CODE_POINTS = 40
    const val MAX_ENABLED_DELIMITERS = 16
    const val MAX_ENCODED_BYTES = 1 shl 20 // 1 MiB
}

/**
 * 本地化无关的校验问题码；UI 负责映射到文案，日志/遥测不得输出角色名或别名。
 *
 * [profileId] / [roleId] 仅用于在本地方案编辑器中定位问题。
 */
enum class MultiRoleIssueCode {
    PROFILE_COUNT_LIMIT,
    PROFILE_NAME_EMPTY,
    PROFILE_NAME_DUPLICATE,
    PROFILE_NAME_TOO_LONG,
    ACTIVE_PROFILE_MISSING,
    PROFILE_NOT_FOUND,
    ROLE_COUNT_LIMIT,
    ROLE_NAME_EMPTY,
    ROLE_NAME_TOO_LONG,
    ROLE_ALIAS_COUNT_LIMIT,
    ROLE_ALIAS_EMPTY,
    ROLE_ALIAS_TOO_LONG,
    NAME_DUPLICATE,
    NAME_RESERVED,
    VOICE_MISSING,
    DELIMITER_NONE_ENABLED,
    DELIMITER_TOO_MANY,
    DELIMITER_INVALID_CODE_POINT,
    DELIMITER_DUPLICATE,
    DELIMITER_CONFLICT,
    PROVIDER_UNKNOWN,
    PROVIDER_NOT_CONFIGURED,
    VOICE_UNAVAILABLE,
    OUTPUT_FORMAT_UNKNOWN,
    OUTPUT_FORMAT_MISMATCH,
    MODEL_NOT_READY,
    ENABLED_REQUIRES_VALID_PROFILE,
}

data class MultiRoleIssue(
    val code: MultiRoleIssueCode,
    val profileId: ProfileId? = null,
    val roleId: RoleId? = null,
    val field: String? = null,
)

/** 加载/保存失败原因；不包含文件内容。 */
sealed interface MultiRoleStorageFailure {
    /** 文件无法解析（保留原文件，功能回退单音色）。 */
    data object Corrupted : MultiRoleStorageFailure

    /** 未来 schema；绝不覆盖用户数据。 */
    data class UnsupportedVersion(val version: Int) : MultiRoleStorageFailure

    /** 编码后超过 1 MiB。 */
    data object TooLarge : MultiRoleStorageFailure

    /** 读取/写入 I/O 失败。 */
    data class Io(val message: String?) : MultiRoleStorageFailure
}

data class MultiRoleState(
    val settings: MultiRoleSettings = MultiRoleSettings(),
    val failure: MultiRoleStorageFailure? = null,
) {
    val usable: Boolean get() = failure == null
}
