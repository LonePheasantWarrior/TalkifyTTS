package com.github.lonepheasantwarrior.talkify.domain.model.multirole

/**
 * 多角色配置的纯 Kotlin 校验。
 *
 * 这里只做与 Android/供应商无关的结构校验；供应商注册、配置就绪、音色可用性
 * 与输出 PCM 格式一致性由 `MultiRoleRuntimeValidator` 在运行期补充。
 */
object MultiRoleValidator {

    const val RESERVED_NARRATOR_NAME = "旁白"
    const val RESERVED_DEFAULT_ROLE_NAME = "默认角色"

    private val reservedNormalizedNames = setOf(
        normalizeForMatch(RESERVED_NARRATOR_NAME),
        normalizeForMatch(RESERVED_DEFAULT_ROLE_NAME),
    )

    fun codepointCount(text: String): Int = text.codePointCount(0, text.length)

    /**
     * 匹配归一化：去两端 Unicode 空白，ASCII A-Z 逐字符小写；其他字符逐码点精确比较。
     * 不改变长度，因此可用同一算法做词典查重与原文匹配。
     */
    fun normalizeForMatch(raw: String): String {
        var start = 0
        var end = raw.length
        while (start < end) {
            val cp = raw.codePointAt(start)
            if (!isUnicodeWhitespace(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = raw.codePointBefore(end)
            if (!isUnicodeWhitespace(cp)) break
            end -= Character.charCount(cp)
        }
        if (start == 0 && end == raw.length && raw.none { it in 'A'..'Z' }) return raw
        val sb = StringBuilder(end - start)
        var i = start
        while (i < end) {
            val ch = raw[i]
            sb.append(if (ch in 'A'..'Z') ch + ('a' - 'A') else ch)
            i++
        }
        return sb.toString()
    }

    fun isUnicodeWhitespace(cp: Int): Boolean =
        Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    fun validate(settings: MultiRoleSettings): List<MultiRoleIssue> {
        val issues = mutableListOf<MultiRoleIssue>()

        if (settings.profiles.size > MultiRoleLimits.MAX_PROFILES) {
            issues += MultiRoleIssue(MultiRoleIssueCode.PROFILE_COUNT_LIMIT)
        }

        val seenProfileNames = mutableSetOf<String>()
        for (profile in settings.profiles) {
            val nameNormalized = normalizeForMatch(profile.name)
            if (profile.name.isBlank()) {
                issues += MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_EMPTY, profile.id)
            } else if (codepointCount(profile.name.trim()) > MultiRoleLimits.MAX_NAME_CODE_POINTS) {
                issues += MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_TOO_LONG, profile.id)
            }
            if (nameNormalized.isNotEmpty() && !seenProfileNames.add(nameNormalized)) {
                issues += MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_DUPLICATE, profile.id)
            }
            issues += validateProfile(profile, settings.profiles)
        }

        if (settings.activeProfileId != null && settings.activeProfile() == null) {
            issues += MultiRoleIssue(MultiRoleIssueCode.ACTIVE_PROFILE_MISSING)
        }
        if (settings.enabled) {
            val active = settings.activeProfile()
            if (active == null) {
                issues += MultiRoleIssue(MultiRoleIssueCode.ENABLED_REQUIRES_VALID_PROFILE)
            } else {
                val activeIssues = validateProfile(active, settings.profiles)
                if (activeIssues.isNotEmpty()) {
                    issues += MultiRoleIssue(MultiRoleIssueCode.ENABLED_REQUIRES_VALID_PROFILE, active.id)
                }
            }
        }
        return issues
    }

    fun validateProfile(
        profile: ReadingProfile,
        allProfiles: List<ReadingProfile> = listOf(profile),
    ): List<MultiRoleIssue> {
        val issues = mutableListOf<MultiRoleIssue>()

        if (profile.name.isBlank()) {
            issues += MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_EMPTY, profile.id)
        } else if (codepointCount(profile.name.trim()) > MultiRoleLimits.MAX_NAME_CODE_POINTS) {
            issues += MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_TOO_LONG, profile.id)
        }
        val duplicateProfileName = allProfiles.count {
            normalizeForMatch(it.name) == normalizeForMatch(profile.name)
        } > 1
        if (profile.name.isNotBlank() && duplicateProfileName) {
            issues += MultiRoleIssue(MultiRoleIssueCode.PROFILE_NAME_DUPLICATE, profile.id)
        }

        if (profile.roles.size > MultiRoleLimits.MAX_ROLES_PER_PROFILE) {
            issues += MultiRoleIssue(MultiRoleIssueCode.ROLE_COUNT_LIMIT, profile.id)
        }
        val voiceRefs = buildList {
            add(profile.narratorVoice)
            add(profile.defaultRoleVoice)
            addAll(profile.roles.map { it.voice })
        }
        if (voiceRefs.any { it == null || it.voiceId.isBlank() }) {
            issues += MultiRoleIssue(MultiRoleIssueCode.VOICE_MISSING, profile.id)
        }

        val usedNames = mutableMapOf<String, RoleId>()
        for (role in profile.roles) {
            issues += validateRoleName(role, profile, usedNames)
            if (role.aliases.size > MultiRoleLimits.MAX_ALIASES_PER_ROLE) {
                issues += MultiRoleIssue(MultiRoleIssueCode.ROLE_ALIAS_COUNT_LIMIT, profile.id, role.id)
            }
            for (alias in role.aliases) {
                issues += validateAlias(alias, role, profile, usedNames)
            }
            if (role.voice == null || role.voice.voiceId.isBlank()) {
                issues += MultiRoleIssue(MultiRoleIssueCode.VOICE_MISSING, profile.id, role.id)
            }
        }

        issues += validateDelimiters(profile.delimiters, profile.id)
        return issues
    }

    private fun validateRoleName(
        role: Role,
        profile: ReadingProfile,
        usedNames: MutableMap<String, RoleId>,
    ): List<MultiRoleIssue> {
        val issues = mutableListOf<MultiRoleIssue>()
        if (role.name.isBlank()) {
            issues += MultiRoleIssue(MultiRoleIssueCode.ROLE_NAME_EMPTY, profile.id, role.id)
            return issues
        }
        if (codepointCount(role.name.trim()) > MultiRoleLimits.MAX_NAME_CODE_POINTS) {
            issues += MultiRoleIssue(MultiRoleIssueCode.ROLE_NAME_TOO_LONG, profile.id, role.id)
        }
        val normalized = normalizeForMatch(role.name)
        when {
            normalized in reservedNormalizedNames ->
                issues += MultiRoleIssue(MultiRoleIssueCode.NAME_RESERVED, profile.id, role.id)
            usedNames.putIfAbsent(normalized, role.id) != null ->
                issues += MultiRoleIssue(MultiRoleIssueCode.NAME_DUPLICATE, profile.id, role.id, "name")
        }
        return issues
    }

    private fun validateAlias(
        alias: String,
        role: Role,
        profile: ReadingProfile,
        usedNames: MutableMap<String, RoleId>,
    ): List<MultiRoleIssue> {
        val issues = mutableListOf<MultiRoleIssue>()
        if (alias.isBlank()) {
            issues += MultiRoleIssue(MultiRoleIssueCode.ROLE_ALIAS_EMPTY, profile.id, role.id)
            return issues
        }
        if (codepointCount(alias.trim()) > MultiRoleLimits.MAX_NAME_CODE_POINTS) {
            issues += MultiRoleIssue(MultiRoleIssueCode.ROLE_ALIAS_TOO_LONG, profile.id, role.id)
        }
        val normalized = normalizeForMatch(alias)
        when {
            normalized in reservedNormalizedNames ->
                issues += MultiRoleIssue(MultiRoleIssueCode.NAME_RESERVED, profile.id, role.id)
            // 同一角色内重复别名、别名与自身名称重复都视为重复
            usedNames.putIfAbsent(normalized, role.id) != null ->
                issues += MultiRoleIssue(MultiRoleIssueCode.NAME_DUPLICATE, profile.id, role.id, "alias")
        }
        return issues
    }

    fun validateDelimiters(
        delimiters: List<DelimiterPair>,
        profileId: ProfileId? = null,
    ): List<MultiRoleIssue> {
        val issues = mutableListOf<MultiRoleIssue>()
        for (pair in delimiters) {
            if (!isValidDelimiterCodePoint(pair.open) || !isValidDelimiterCodePoint(pair.close)) {
                issues += MultiRoleIssue(
                    MultiRoleIssueCode.DELIMITER_INVALID_CODE_POINT,
                    profileId,
                    field = "${pair.open}:${pair.close}",
                )
            }
        }

        val enabled = delimiters.filter { it.enabled }
        if (enabled.isEmpty()) {
            issues += MultiRoleIssue(MultiRoleIssueCode.DELIMITER_NONE_ENABLED, profileId)
        }
        if (enabled.size > MultiRoleLimits.MAX_ENABLED_DELIMITERS) {
            issues += MultiRoleIssue(MultiRoleIssueCode.DELIMITER_TOO_MANY, profileId)
        }

        // 完全相同的符号对重复启用
        val seenPairs = mutableSetOf<Pair<Int, Int>>()
        for (pair in enabled) {
            if (!seenPairs.add(pair.open to pair.close)) {
                issues += MultiRoleIssue(MultiRoleIssueCode.DELIMITER_DUPLICATE, profileId)
            }
        }

        // 同一码点不能出现在不同启用符号对中（同一对开闭相同除外）
        val ownerByChar = mutableMapOf<Int, Pair<Int, Int>>()
        for (pair in enabled) {
            val pairKey = pair.open to pair.close
            for (cp in listOf(pair.open, pair.close)) {
                if (!isValidDelimiterCodePoint(cp)) continue
                val owner = ownerByChar.putIfAbsent(cp, pairKey)
                if (owner != null && owner != pairKey) {
                    issues += MultiRoleIssue(MultiRoleIssueCode.DELIMITER_CONFLICT, profileId)
                }
            }
        }
        return issues.distinctBy { it.code to it.field }
    }

    fun isValidDelimiterCodePoint(cp: Int): Boolean {
        if (cp < 0 || cp > Character.MAX_CODE_POINT) return false
        if (cp in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) return false
        if (isUnicodeWhitespace(cp)) return false
        if (Character.isISOControl(cp)) return false
        if (Character.isLetter(cp) || Character.isDigit(cp)) return false
        return true
    }

    /** 是否为可朗读字符：任意 Unicode 码点的字母或数字（含补充平面）。 */
    fun hasReadableContent(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp) || Character.isDigit(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    fun isValidRoleId(id: RoleId): Boolean = id.value.isNotBlank()

    fun isValidProfileId(id: ProfileId): Boolean = id.value.isNotBlank()

    /** 稳定随机标识；不使用下标、名称或时间戳。 */
    fun newProfileId(): ProfileId = ProfileId("p_" + java.util.UUID.randomUUID())

    fun newRoleId(): RoleId = RoleId("r_" + java.util.UUID.randomUUID())
}
