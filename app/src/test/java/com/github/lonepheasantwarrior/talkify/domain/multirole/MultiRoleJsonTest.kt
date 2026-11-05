package com.github.lonepheasantwarrior.talkify.domain.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.DelimiterPair
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleJson
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleLimits
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleStorageFailure
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ProfileId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.Role
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRoleJsonTest {

    private val sample = MultiRoleSettings(
        enabled = true,
        activeProfileId = ProfileId("p_1"),
        profiles = listOf(
            ReadingProfile(
                id = ProfileId("p_1"),
                name = "小说 A",
                providerId = "xiaomi",
                narratorVoice = VoiceRef("narrator"),
                defaultRoleVoice = VoiceRef("default"),
                roles = listOf(
                    Role(
                        id = RoleId("r_1"),
                        name = "张三",
                        aliases = listOf("三哥", "张老三"),
                        voice = VoiceRef("voice_zhang"),
                    ),
                    Role(
                        id = RoleId("r_2"),
                        name = "Alice",
                        aliases = emptyList(),
                        voice = VoiceRef("voice_alice"),
                    ),
                ),
                delimiters = listOf(
                    DelimiterPair(0x201C, 0x201D, true),
                    DelimiterPair(0x0022, 0x0022, false),
                ),
            )
        ),
    )

    private fun decode(raw: String): MultiRoleJson.DecodeResult = MultiRoleJson.decode(raw)

    @Test
    fun `round trip preserves all fields`() {
        val encoded = MultiRoleJson.encode(sample)
        assertTrue(encoded is MultiRoleJson.EncodeResult.Success)
        val json = (encoded as MultiRoleJson.EncodeResult.Success).json
        val decoded = decode(json)
        assertTrue(decoded is MultiRoleJson.DecodeResult.Success)
        assertEquals(sample, (decoded as MultiRoleJson.DecodeResult.Success).settings)
    }

    @Test
    fun `missing optional fields use safe defaults`() {
        val decoded = decode("""{"schemaVersion":1,"profiles":[{"id":"p1","name":"n","providerId":"xiaomi","roles":[]}]}""")
        val settings = (decoded as MultiRoleJson.DecodeResult.Success).settings
        assertEquals(false, settings.enabled)
        assertNull(settings.activeProfileId)
        assertEquals(1, settings.profiles.size)
        assertEquals(MultiRoleSettings.defaultDelimiterPairs(), settings.profiles.first().delimiters)
        assertNull(settings.profiles.first().narratorVoice)
    }

    @Test
    fun `unknown fields are ignored`() {
        val decoded = decode("""{"schemaVersion":1,"unknown":true,"profiles":[]}""")
        assertTrue(decoded is MultiRoleJson.DecodeResult.Success)
    }

    @Test
    fun `future schema version is rejected and not silently reset`() {
        val decoded = decode("""{"schemaVersion":99,"profiles":[]}""")
        assertEquals(
            MultiRoleStorageFailure.UnsupportedVersion(99),
            (decoded as MultiRoleJson.DecodeResult.Failure).failure,
        )
    }

    @Test
    fun `corrupt json is rejected`() {
        assertTrue(decode("{not json") is MultiRoleJson.DecodeResult.Failure)
        assertTrue(decode("[]") is MultiRoleJson.DecodeResult.Failure)
        assertTrue(
            decode("""{"schemaVersion":1,"profiles":[{"id":"","name":"x"}]}""")
                is MultiRoleJson.DecodeResult.Failure
        )
    }

    @Test
    fun `duplicate stable ids are rejected`() {
        assertTrue(
            decode(
                """{"schemaVersion":1,"profiles":[{"id":"p1","name":"a","providerId":"x","roles":[]},{"id":"p1","name":"b","providerId":"x","roles":[]}]}"""
            ) is MultiRoleJson.DecodeResult.Failure
        )
        assertTrue(
            decode(
                """{"schemaVersion":1,"profiles":[{"id":"p1","name":"a","providerId":"x","roles":[{"id":"r1","name":"n","aliases":[]},{"id":"r1","name":"m","aliases":[]}]}]}"""
            ) is MultiRoleJson.DecodeResult.Failure
        )
    }

    @Test
    fun `too many roles are rejected at load`() {
        val roles = (0..MultiRoleLimits.MAX_ROLES_PER_PROFILE)
            .joinToString(",") { """{"id":"r$it","name":"n$it","aliases":[]}""" }
        val raw = """{"schemaVersion":1,"profiles":[{"id":"p1","name":"n","providerId":"x","roles":[$roles]}]}"""
        assertTrue(decode(raw) is MultiRoleJson.DecodeResult.Failure)
    }

    @Test
    fun `too large payload is rejected before parse`() {
        val raw = "x".repeat(MultiRoleLimits.MAX_ENCODED_BYTES + 1)
        assertEquals(
            MultiRoleStorageFailure.TooLarge,
            (decode(raw) as MultiRoleJson.DecodeResult.Failure).failure,
        )
    }

    @Test
    fun `blank persisted value means empty settings`() {
        val decoded = decode("")
        assertEquals(
            MultiRoleSettings(), (decoded as MultiRoleJson.DecodeResult.Success).settings
        )
    }
}
