package com.github.lonepheasantwarrior.talkify.service.multirole

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssue
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssueCode
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import com.github.lonepheasantwarrior.talkify.service.provider.VoiceCapabilityReason
import com.github.lonepheasantwarrior.talkify.service.provider.VoiceOutputCapability

/**
 * 运行期方案校验：供应商已注册、配置就绪、每个实际使用音色可用，
 * 且所有音色的输出 PCM 格式完全一致。
 */
object MultiRoleRuntimeValidator {

    data class Result(
        val issues: List<MultiRoleIssue>,
        val audioConfig: AudioConfig?,
    ) {
        val ok: Boolean get() = issues.isEmpty() && audioConfig != null
    }

    /** UI/检查页使用：创建临时 Provider 后释放，不泄漏资源。 */
    fun validateProfile(
        context: Context,
        profile: ReadingProfile,
    ): Result {
        val staticIssues = MultiRoleValidator.validateProfile(profile, listOf(profile))
        val provider = TtsProviderFactory.createProvider(profile.providerId)
            ?: return Result(
                staticIssues + MultiRoleIssue(
                    MultiRoleIssueCode.PROVIDER_UNKNOWN,
                    profile.id,
                ),
                null,
            )
        return try {
            val config = TtsProviderFactory.createConfigRepository(profile.providerId, context)
                ?.getConfig(profile.providerId)
            validateWithProvider(profile, provider, config, staticIssues)
        } catch (e: Exception) {
            Result(
                staticIssues + MultiRoleIssue(
                    MultiRoleIssueCode.PROVIDER_NOT_CONFIGURED,
                    profile.id,
                ),
                null,
            )
        } finally {
            try {
                provider.release()
            } catch (_: Exception) {
            }
        }
    }

    fun validateWithProvider(
        profile: ReadingProfile,
        provider: TtsProviderApi,
        config: BaseProviderConfig?,
        staticIssues: List<MultiRoleIssue> = MultiRoleValidator.validateProfile(
            profile, listOf(profile)
        ),
    ): Result {
        if (staticIssues.any { it.code == MultiRoleIssueCode.PROVIDER_UNKNOWN }) {
            return Result(staticIssues, null)
        }
        if (config == null || !provider.isConfigured(config)) {
            val code = if (profile.providerId == ProviderIds.LocalModel.providerId) {
                MultiRoleIssueCode.MODEL_NOT_READY
            } else {
                MultiRoleIssueCode.PROVIDER_NOT_CONFIGURED
            }
            return Result(staticIssues + MultiRoleIssue(code, profile.id), null)
        }

        val issues = staticIssues.toMutableList()
        val formats = mutableListOf<AudioConfig>()
        val voices = buildList {
            profile.narratorVoice?.let { add(it.voiceId) }
            profile.defaultRoleVoice?.let { add(it.voiceId) }
            addAll(profile.roles.mapNotNull { role -> role.voice?.voiceId })
        }.filter { it.isNotBlank() }.distinct()

        for (voiceId in voices) {
            val capability = try {
                provider.getVoiceOutputCapability(config, voiceId)
            } catch (_: Exception) {
                VoiceOutputCapability.Unknown
            }
            when (capability) {
                is VoiceOutputCapability.Known -> formats += capability.audioConfig
                is VoiceOutputCapability.Unsupported -> {
                    val code = when (capability.reason) {
                        VoiceCapabilityReason.VOICE_UNSUPPORTED -> MultiRoleIssueCode.VOICE_UNAVAILABLE
                        VoiceCapabilityReason.PROVIDER_UNSUPPORTED,
                        VoiceCapabilityReason.OUTPUT_FORMAT_UNKNOWN,
                        VoiceCapabilityReason.OUTPUT_FORMAT_VARIES -> MultiRoleIssueCode.OUTPUT_FORMAT_UNKNOWN
                    }
                    issues += MultiRoleIssue(code, profile.id, field = voiceId)
                }
                VoiceOutputCapability.Unknown ->
                    issues += MultiRoleIssue(MultiRoleIssueCode.OUTPUT_FORMAT_UNKNOWN, profile.id, field = voiceId)
            }
        }
        val distinctFormats = formats.distinct()
        if (distinctFormats.size > 1) {
            issues += MultiRoleIssue(MultiRoleIssueCode.OUTPUT_FORMAT_MISMATCH, profile.id)
        }
        val audioConfig = distinctFormats.singleOrNull()?.takeIf { issues.isEmpty() }
        return Result(issues, audioConfig)
    }
}
