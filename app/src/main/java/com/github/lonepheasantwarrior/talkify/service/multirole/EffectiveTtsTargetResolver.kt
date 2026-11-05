package com.github.lonepheasantwarrior.talkify.service.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssue
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleIssueCode
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleState
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile

/**
 * 生效配置解析：系统发现（语言/音色）与合成入口必须使用与之一致的决策——
 * 开启且方案有效时使用方案供应商，否则使用主界面普通供应商。
 *
 * 这里只做静态选择；供应商配置/音色/格式等运行期问题由
 * [MultiRoleRuntimeValidator] 与请求预检补足并回退。
 */
object EffectiveTtsTargetResolver {

    data class Target(
        val providerId: String,
        val profile: ReadingProfile?,
        val settings: MultiRoleSettings?,
        val fallback: Fallback? = null,
    )

    data class Fallback(
        val reason: FallbackReason,
        val detail: MultiRoleIssue? = null,
    )

    enum class FallbackReason {
        SETTINGS_UNREADABLE,
        NO_ACTIVE_PROFILE,
        PROFILE_INVALID,
        PROVIDER_UNKNOWN,
        PROVIDER_NOT_CONFIGURED,
        MODEL_NOT_READY,
        VOICE_UNAVAILABLE,
        OUTPUT_FORMAT_UNKNOWN,
        OUTPUT_FORMAT_MISMATCH,
    }

    fun resolve(mainProviderId: String, state: MultiRoleState?): Target {
        if (state == null || state.failure != null) {
            return Target(
                providerId = mainProviderId,
                profile = null,
                settings = null,
                fallback = Fallback(FallbackReason.SETTINGS_UNREADABLE),
            )
        }
        val settings = state.settings
        if (!settings.enabled) {
            return Target(mainProviderId, null, settings)
        }
        val active = settings.activeProfile()
            ?: return Target(
                mainProviderId, null, settings,
                Fallback(FallbackReason.NO_ACTIVE_PROFILE),
            )
        val issues = MultiRoleValidator.validateProfile(active, settings.profiles)
        if (issues.isNotEmpty()) {
            return Target(
                mainProviderId, null, settings,
                Fallback(FallbackReason.PROFILE_INVALID, issues.first()),
            )
        }
        return Target(active.providerId, active, settings)
    }

    fun fallbackReasonFor(issue: MultiRoleIssue): FallbackReason = when (issue.code) {
        MultiRoleIssueCode.PROVIDER_UNKNOWN -> FallbackReason.PROVIDER_UNKNOWN
        MultiRoleIssueCode.PROVIDER_NOT_CONFIGURED -> FallbackReason.PROVIDER_NOT_CONFIGURED
        MultiRoleIssueCode.MODEL_NOT_READY -> FallbackReason.MODEL_NOT_READY
        MultiRoleIssueCode.VOICE_UNAVAILABLE -> FallbackReason.VOICE_UNAVAILABLE
        MultiRoleIssueCode.OUTPUT_FORMAT_UNKNOWN -> FallbackReason.OUTPUT_FORMAT_UNKNOWN
        MultiRoleIssueCode.OUTPUT_FORMAT_MISMATCH -> FallbackReason.OUTPUT_FORMAT_MISMATCH
        else -> FallbackReason.PROFILE_INVALID
    }
}
