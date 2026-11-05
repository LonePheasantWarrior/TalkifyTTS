package com.github.lonepheasantwarrior.talkify.domain.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.DelimiterPair
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssueCode
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleLimits
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ProfileId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.Role
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRoleValidatorTest {

    private fun role(name: String, aliases: List<String> = emptyList()): Role = Role(
        id = RoleId("r_$name"),
        name = name,
        aliases = aliases,
        voice = VoiceRef("v_$name"),
    )

    private fun profile(
        name: String = "方案",
        roles: List<Role> = emptyList(),
        delimiters: List<DelimiterPair> = MultiRoleSettings.defaultDelimiterPairs(),
    ): ReadingProfile = ReadingProfile(
        id = ProfileId("p_$name"),
        name = name,
        providerId = "xiaomi",
        narratorVoice = VoiceRef("narrator"),
        defaultRoleVoice = VoiceRef("default"),
        roles = roles,
        delimiters = delimiters,
    )

    @Test
    fun `normalization trims unicode whitespace and lowercases ascii only`() {
        assertEquals("alice", MultiRoleValidator.normalizeForMatch("  Alice\u3000"))
        assertEquals("Ä", MultiRoleValidator.normalizeForMatch("Ä"))
        assertEquals("Ａ", MultiRoleValidator.normalizeForMatch("Ａ"))
        assertEquals("abc", MultiRoleValidator.normalizeForMatch("ABC"))
    }

    @Test
    fun `default delimiters are three enabled pairs`() {
        val pairs = MultiRoleSettings.defaultDelimiterPairs()
        assertEquals(3, pairs.size)
        assertTrue(pairs.all { it.enabled })
    }

    @Test
    fun `valid profile passes`() {
        val p = profile(roles = listOf(role("张三", listOf("三哥")), role("李四")))
        assertTrue(MultiRoleValidator.validateProfile(p).isEmpty())
    }

    @Test
    fun `duplicate role names and aliases are rejected case insensitively`() {
        assertTrue(
            MultiRoleValidator.validateProfile(
                profile(roles = listOf(role("Alice"), role("alice")))
            ).any { it.code == MultiRoleIssueCode.NAME_DUPLICATE }
        )
        assertTrue(
            MultiRoleValidator.validateProfile(
                profile(roles = listOf(role("Alice", listOf("alice"))))
            ).any { it.code == MultiRoleIssueCode.NAME_DUPLICATE }
        )
        assertTrue(
            MultiRoleValidator.validateProfile(
                profile(roles = listOf(role("Alice"), role("Bob", listOf("alice"))))
            ).any { it.code == MultiRoleIssueCode.NAME_DUPLICATE }
        )
    }

    @Test
    fun `reserved names are rejected`() {
        assertTrue(
            MultiRoleValidator.validateProfile(profile(roles = listOf(role("旁白"))))
                .any { it.code == MultiRoleIssueCode.NAME_RESERVED }
        )
        assertTrue(
            MultiRoleValidator.validateProfile(profile(roles = listOf(role("默认角色"))))
                .any { it.code == MultiRoleIssueCode.NAME_RESERVED }
        )
    }

    @Test
    fun `missing voices are reported`() {
        val p = profile().copy(narratorVoice = null)
        assertTrue(MultiRoleValidator.validateProfile(p)
            .any { it.code == MultiRoleIssueCode.VOICE_MISSING })
    }

    @Test
    fun `profile name must be non-empty and unique`() {
        val settings = MultiRoleSettings(
            profiles = listOf(profile("同名"), profile("同名"))
        )
        val issues = MultiRoleValidator.validate(settings)
        assertTrue(issues.any { it.code == MultiRoleIssueCode.PROFILE_NAME_DUPLICATE })
        assertTrue(
            MultiRoleValidator.validate(MultiRoleSettings(profiles = listOf(profile(" "))))
                .any { it.code == MultiRoleIssueCode.PROFILE_NAME_EMPTY }
        )
    }

    @Test
    fun `enabled requires existing valid active profile`() {
        val settings = MultiRoleSettings(enabled = true, activeProfileId = null)
        assertTrue(MultiRoleValidator.validate(settings)
            .any { it.code == MultiRoleIssueCode.ENABLED_REQUIRES_VALID_PROFILE })

        val broken = profile(roles = listOf(role("张三"))).copy(narratorVoice = null)
        val enabledBroken = MultiRoleSettings(
            enabled = true,
            activeProfileId = broken.id,
            profiles = listOf(broken),
        )
        assertTrue(MultiRoleValidator.validate(enabledBroken)
            .any { it.code == MultiRoleIssueCode.ENABLED_REQUIRES_VALID_PROFILE })
    }

    @Test
    fun `resource limits are enforced`() {
        val tooManyProfiles = MultiRoleSettings(
            profiles = (0..MultiRoleLimits.MAX_PROFILES).map { profile("p$it") }
        )
        assertTrue(MultiRoleValidator.validate(tooManyProfiles)
            .any { it.code == MultiRoleIssueCode.PROFILE_COUNT_LIMIT })

        val tooManyRoles = (0..MultiRoleLimits.MAX_ROLES_PER_PROFILE)
            .map { role("r$it") }
        assertTrue(MultiRoleValidator.validateProfile(profile(roles = tooManyRoles))
            .any { it.code == MultiRoleIssueCode.ROLE_COUNT_LIMIT })

        val tooManyAliases = (0..MultiRoleLimits.MAX_ALIASES_PER_ROLE)
            .map { "a$it" }
        assertTrue(MultiRoleValidator.validateProfile(profile(roles = listOf(role("r", tooManyAliases))))
            .any { it.code == MultiRoleIssueCode.ROLE_ALIAS_COUNT_LIMIT })

        val tooLong = "长".repeat(MultiRoleLimits.MAX_NAME_CODE_POINTS + 1)
        assertTrue(MultiRoleValidator.validateProfile(profile(roles = listOf(role(tooLong))))
            .any { it.code == MultiRoleIssueCode.ROLE_NAME_TOO_LONG })
    }

    @Test
    fun `delimiter validity rejects whitespace letters digits controls and surrogates`() {
        val invalid = listOf(
            ' '.code, '\n'.code, 'a'.code, '1'.code, 0x0007,
            0xD800, 0xDFFF, -1, Character.MAX_CODE_POINT + 1,
        )
        invalid.forEach { cp ->
            assertFalse("cp=$cp", MultiRoleValidator.isValidDelimiterCodePoint(cp))
        }
        assertTrue(MultiRoleValidator.isValidDelimiterCodePoint('“'.code))
        assertTrue(MultiRoleValidator.isValidDelimiterCodePoint('"'.code))
        assertTrue(MultiRoleValidator.isValidDelimiterCodePoint("😀".codePointAt(0)))
    }

    @Test
    fun `delimiter conflicts and duplicates are rejected`() {
        val conflict = listOf(
            DelimiterPair('“'.code, '”'.code, enabled = true),
            DelimiterPair('“'.code, '」'.code, enabled = true),
        )
        assertTrue(MultiRoleValidator.validateDelimiters(conflict)
            .any { it.code == MultiRoleIssueCode.DELIMITER_CONFLICT })

        val duplicate = listOf(
            DelimiterPair('「'.code, '」'.code, enabled = true),
            DelimiterPair('「'.code, '」'.code, enabled = true),
        )
        assertTrue(MultiRoleValidator.validateDelimiters(duplicate)
            .any { it.code == MultiRoleIssueCode.DELIMITER_DUPLICATE })

        val none = listOf(DelimiterPair('“'.code, '”'.code, enabled = false))
        assertTrue(MultiRoleValidator.validateDelimiters(none)
            .any { it.code == MultiRoleIssueCode.DELIMITER_NONE_ENABLED })
    }

    @Test
    fun `symmetric delimiter may repeat only within its own pair`() {
        val pairs = listOf(
            DelimiterPair('"'.code, '"'.code, enabled = true),
            DelimiterPair('「'.code, '」'.code, enabled = true),
        )
        assertTrue(MultiRoleValidator.validateDelimiters(pairs).isEmpty())
    }

    @Test
    fun `readable content uses unicode letters and digits`() {
        assertTrue(MultiRoleValidator.hasReadableContent("123"))
        assertTrue(MultiRoleValidator.hasReadableContent("\uD840\uDC00"))
        assertTrue(MultiRoleValidator.hasReadableContent("中文"))
        assertFalse(MultiRoleValidator.hasReadableContent("“”，。！"))
        assertFalse(MultiRoleValidator.hasReadableContent("😀"))
    }
}
