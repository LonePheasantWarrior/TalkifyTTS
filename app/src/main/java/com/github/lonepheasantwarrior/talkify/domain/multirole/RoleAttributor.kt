package com.github.lonepheasantwarrior.talkify.domain.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.Role
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId

/**
 * 确定性归属：汇总每条台词来自左右两侧的有效证据。
 *
 * - 唯一角色（或多个证据指向同一角色）→ 采用；
 * - 不同角色冲突 → 默认角色，不按距离/配置顺序决胜；
 * - 无证据 → 默认角色。
 */
internal class RoleAttributor(
    private val validRoleIds: Set<RoleId>,
) {

    data class Result(
        val roleId: RoleId?,
        val reason: AttributionReason,
        val evidence: List<AttributionEvidenceExtractor.Evidence>,
    )

    fun attribute(
        evidences: List<AttributionEvidenceExtractor.Evidence>,
    ): Result {
        val usable = evidences.filter { it.speakerRoleId in validRoleIds }
            .distinctBy { it.speakerRoleId to it.speakerStart }
        if (usable.isEmpty()) {
            return Result(null, AttributionReason.NO_EVIDENCE, emptyList())
        }
        val speakers = usable.map { it.speakerRoleId }.distinct()
        if (speakers.size > 1) {
            return Result(null, AttributionReason.CONFLICT, usable)
        }
        val directions = usable.map { it.direction }.toSet()
        val reason = when {
            directions.any { it == AttributionEvidenceExtractor.Direction.BOTH } -> AttributionReason.BOTH_SIDES
            AttributionEvidenceExtractor.Direction.LEFT in directions &&
                AttributionEvidenceExtractor.Direction.RIGHT in directions -> AttributionReason.BOTH_SIDES
            AttributionEvidenceExtractor.Direction.RIGHT in directions -> AttributionReason.RIGHT_LABEL
            else -> AttributionReason.LEFT_LABEL
        }
        return Result(speakers.first(), reason, usable)
    }

    companion object {
        fun validRoleIds(roles: List<Role>): Set<RoleId> = roles.map { it.id }.toSet()
    }
}

enum class AttributionReason {
    LEFT_LABEL,
    RIGHT_LABEL,
    BOTH_SIDES,
    CONFLICT,
    NO_EVIDENCE,
}
