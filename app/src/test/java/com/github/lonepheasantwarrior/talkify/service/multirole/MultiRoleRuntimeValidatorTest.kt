package com.github.lonepheasantwarrior.talkify.service.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssueCode
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleState
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ProfileId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.Role
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.VoiceCapabilityReason
import com.github.lonepheasantwarrior.talkify.service.provider.VoiceOutputCapability
import com.github.lonepheasantwarrior.talkify.testutil.FakeTtsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRoleRuntimeValidatorTest {

    private fun profile(): ReadingProfile = ReadingProfile(
        id = ProfileId("p"),
        name = "方案",
        providerId = "fake",
        narratorVoice = VoiceRef("narrator"),
        defaultRoleVoice = VoiceRef("default"),
        roles = listOf(
            Role(RoleId("r1"), "张三", aliases = emptyList(), voice = VoiceRef("zhang")),
            Role(RoleId("r2"), "李四", aliases = emptyList(), voice = VoiceRef("li")),
        ),
    )

    @Test
    fun `all known and identical formats pass`() {
        val provider = FakeTtsProvider { _, _, _ -> }
        val result = MultiRoleRuntimeValidator.validateWithProvider(
            profile(), provider, FakeTtsProvider.FakeConfig()
        )
        assertTrue(result.ok)
        assertEquals(AudioConfig(sampleRate = 24000), result.audioConfig)
    }

    @Test
    fun `unknown capability blocks multi role`() {
        val provider = FakeTtsProvider { _, _, _ -> }
        provider.capability = { VoiceOutputCapability.Unknown }
        val result = MultiRoleRuntimeValidator.validateWithProvider(
            profile(), provider, FakeTtsProvider.FakeConfig()
        )
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == MultiRoleIssueCode.OUTPUT_FORMAT_UNKNOWN })
    }

    @Test
    fun `format mismatch blocks multi role`() {
        val provider = FakeTtsProvider { _, _, _ -> }
        provider.capability = { voiceId ->
            if (voiceId == "li") {
                VoiceOutputCapability.Known(AudioConfig(sampleRate = 32000))
            } else {
                VoiceOutputCapability.Known(AudioConfig(sampleRate = 24000))
            }
        }
        val result = MultiRoleRuntimeValidator.validateWithProvider(
            profile(), provider, FakeTtsProvider.FakeConfig()
        )
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == MultiRoleIssueCode.OUTPUT_FORMAT_MISMATCH })
    }

    @Test
    fun `unsupported voice and provider reason are mapped`() {
        val provider = FakeTtsProvider { _, _, _ -> }
        provider.capability = { voiceId ->
            if (voiceId == "li") {
                VoiceOutputCapability.Unsupported(VoiceCapabilityReason.VOICE_UNSUPPORTED)
            } else {
                VoiceOutputCapability.Unsupported(VoiceCapabilityReason.OUTPUT_FORMAT_UNKNOWN)
            }
        }
        val result = MultiRoleRuntimeValidator.validateWithProvider(
            profile(), provider, FakeTtsProvider.FakeConfig()
        )
        assertTrue(result.issues.any { it.code == MultiRoleIssueCode.VOICE_UNAVAILABLE })
        assertTrue(result.issues.any { it.code == MultiRoleIssueCode.OUTPUT_FORMAT_UNKNOWN })
    }

    @Test
    fun `unconfigured provider blocks`() {
        val provider = FakeTtsProvider { _, _, _ -> }
        provider.configured = false
        val result = MultiRoleRuntimeValidator.validateWithProvider(
            profile(), provider, FakeTtsProvider.FakeConfig()
        )
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == MultiRoleIssueCode.PROVIDER_NOT_CONFIGURED })
    }

    @Test
    fun `static issue is surfaced`() {
        val provider = FakeTtsProvider { _, _, _ -> }
        val broken = profile().copy(narratorVoice = null)
        val result = MultiRoleRuntimeValidator.validateWithProvider(
            broken, provider, FakeTtsProvider.FakeConfig()
        )
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == MultiRoleIssueCode.VOICE_MISSING })
    }

    @Test
    fun `azure capability is explicitly unsupported`() {
        val azure = com.github.lonepheasantwarrior.talkify.service.provider.impl.AzureProvider()
        val capability = azure.getVoiceOutputCapability(
            com.github.lonepheasantwarrior.talkify.domain.model.AzureConfig(voiceId = "zh-CN-XiaoxiaoNeural"),
            "zh-CN-XiaoxiaoNeural",
        )
        assertTrue(capability is VoiceOutputCapability.Unsupported)
    }
}

class EffectiveTtsTargetResolverTest {

    private fun profile(): ReadingProfile = ReadingProfile(
        id = ProfileId("p"),
        name = "方案",
        providerId = "xiaomi",
        narratorVoice = VoiceRef("narrator"),
        defaultRoleVoice = VoiceRef("default"),
    )

    @Test
    fun `disabled uses main provider and no fallback`() {
        val target = EffectiveTtsTargetResolver.resolve(
            "azure", MultiRoleState(settings = MultiRoleSettings())
        )
        assertEquals("azure", target.providerId)
        assertNull(target.profile)
        assertNull(target.fallback)
    }

    @Test
    fun `unreadable settings fall back to main with reason`() {
        val target = EffectiveTtsTargetResolver.resolve("azure", null)
        assertEquals("azure", target.providerId)
        assertEquals(
            EffectiveTtsTargetResolver.FallbackReason.SETTINGS_UNREADABLE,
            target.fallback?.reason,
        )
    }

    @Test
    fun `enabled without active profile falls back`() {
        val target = EffectiveTtsTargetResolver.resolve(
            "azure",
            MultiRoleState(settings = MultiRoleSettings(enabled = true)),
        )
        assertEquals("azure", target.providerId)
        assertEquals(
            EffectiveTtsTargetResolver.FallbackReason.NO_ACTIVE_PROFILE,
            target.fallback?.reason,
        )
    }

    @Test
    fun `valid active profile uses its provider even when main differs`() {
        val profile = profile()
        val settings = MultiRoleSettings(
            enabled = true,
            activeProfileId = profile.id,
            profiles = listOf(profile),
        )
        val target = EffectiveTtsTargetResolver.resolve("azure", MultiRoleState(settings = settings))
        assertEquals("xiaomi", target.providerId)
        assertNotNull(target.profile)
        assertNull(target.fallback)
    }

    @Test
    fun `invalid active profile falls back to main`() {
        val broken = profile().copy(narratorVoice = null)
        val settings = MultiRoleSettings(
            enabled = true,
            activeProfileId = broken.id,
            profiles = listOf(broken),
        )
        val target = EffectiveTtsTargetResolver.resolve("azure", MultiRoleState(settings = settings))
        assertEquals("azure", target.providerId)
        assertEquals(
            EffectiveTtsTargetResolver.FallbackReason.PROFILE_INVALID,
            target.fallback?.reason,
        )
    }
}
