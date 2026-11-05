package com.github.lonepheasantwarrior.talkify.domain.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ProfileId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId

/** 计划区间类型：旁白或台词。 */
enum class SegmentKind { NARRATION, SPEECH }

/**
 * 预览用分析结果：保留未合并、未附着标点的原始分区与归属理由。
 * 文本始终来自原文 `substring(start, endExclusive)`，不做 trim/过滤。
 */
data class AnalyzedSegment(
    val start: Int,
    val endExclusive: Int,
    val kind: SegmentKind,
    val roleId: RoleId?,
    val roleName: String?,
    val defaultRole: Boolean,
    val voiceId: String,
    val reason: AttributionReason?,
)

/** 实际交给顺序执行器的区间；相邻同角色同音色区间已合并，纯标点已附着。 */
data class SpeechSegment(
    val start: Int,
    val endExclusive: Int,
    val kind: SegmentKind,
    val roleId: RoleId?,
    val voiceId: String,
)

data class PlanWarnings(
    val unclosedQuote: Boolean = false,
    val limitExceeded: Boolean = false,
    val budgetExceeded: Boolean = false,
)

data class SpeechPlan(
    val text: String,
    val segments: List<SpeechSegment>,
    val analysis: List<AnalyzedSegment>,
    val warnings: PlanWarnings,
    val hasReadableContent: Boolean,
)

data class ParsedReading(
    val profileId: ProfileId,
    val plan: SpeechPlan,
    val speechCount: Int,
)

/**
 * 从原始分析区间构建执行计划。
 *
 * 不变量（文档第 4.1 节）：
 * - 有序区间覆盖 `[0, text.length)`、相邻无缝、不重叠；
 * - 每段 `substring(start, endExclusive)` 按序拼接逐 UTF-16 单元等于原文；
 * - 空白/纯标点区间附着到相邻可发声区间（优先左侧），保留每个源字符一次；
 * - 整次请求没有可发声文本时不伪造音频。
 */
object SpeechPlanBuilder {

    fun build(
        text: String,
        analysis: List<AnalyzedSegment>,
        profile: ReadingProfile,
        warnings: PlanWarnings = PlanWarnings(),
    ): SpeechPlan {
        val readable = MultiRoleValidator.hasReadableContent(text)
        if (analysis.isEmpty()) {
            return SpeechPlan(
                text = text,
                segments = emptyList(),
                analysis = emptyList(),
                warnings = warnings,
                hasReadableContent = readable,
            )
        }

        if (!readable) {
            // 没有可发声文本时保留完整原文；服务层按既有契约报错/结束，不伪造音频
            val segment = SpeechSegment(0, text.length, SegmentKind.NARRATION, null, narratorVoice(profile))
            return SpeechPlan(text, listOf(segment), analysis, warnings, hasReadableContent = false)
        }

        val initial = analysis.map {
            SpeechSegment(it.start, it.endExclusive, it.kind, it.roleId, it.voiceId)
        }
        val attached = attachUnreadableSpans(text, initial)
        val merged = mergeAdjacentSameVoice(attached)
        if (!coversExactlyOnce(merged, text.length)) {
            // 防御性兜底：任何内部错误都退回旁白完整原文，绝不截断
            val segment = SpeechSegment(0, text.length, SegmentKind.NARRATION, null, narratorVoice(profile))
            return SpeechPlan(text, listOf(segment), analysis, warnings, hasReadableContent = true)
        }
        return SpeechPlan(text, merged, analysis, warnings, hasReadableContent = true)
    }

    /** 旁白完整原文回退（预算超限/解析失败时使用；绝不截断）。 */
    fun narrationFallback(
        text: String,
        profile: ReadingProfile,
        warnings: PlanWarnings,
    ): SpeechPlan {
        val voice = narratorVoice(profile)
        val analysis = if (text.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AnalyzedSegment(
                    start = 0,
                    endExclusive = text.length,
                    kind = SegmentKind.NARRATION,
                    roleId = null,
                    roleName = null,
                    defaultRole = false,
                    voiceId = voice,
                    reason = null,
                )
            )
        }
        val segments = if (text.isEmpty()) emptyList() else {
            listOf(SpeechSegment(0, text.length, SegmentKind.NARRATION, null, voice))
        }
        return SpeechPlan(
            text = text,
            segments = segments,
            analysis = analysis,
            warnings = warnings,
            hasReadableContent = MultiRoleValidator.hasReadableContent(text),
        )
    }

    fun narratorVoice(profile: ReadingProfile): String = profile.narratorVoice?.voiceId.orEmpty()

    private fun buildInitialSpans(
        text: String,
        speechSpans: List<IntRange>,
        profile: ReadingProfile,
        attributions: List<RoleAttributor.Result>,
    ): List<AnalyzedSegment> {
        val result = ArrayList<AnalyzedSegment>(speechSpans.size * 2 + 1)
        var cursor = 0
        val narrator = narratorVoice(profile)
        val defaultVoice = profile.defaultRoleVoice?.voiceId.orEmpty()
        val rolesById = profile.roles.associateBy { it.id }
        for ((index, span) in speechSpans.withIndex()) {
            if (span.first < cursor) continue
            if (span.first > cursor) {
                result += AnalyzedSegment(
                    start = cursor,
                    endExclusive = span.first,
                    kind = SegmentKind.NARRATION,
                    roleId = null,
                    roleName = null,
                    defaultRole = false,
                    voiceId = narrator,
                    reason = null,
                )
            }
            val attribution = attributions.getOrNull(index)
            val role = attribution?.roleId?.let { rolesById[it] }
            result += AnalyzedSegment(
                start = span.first,
                endExclusive = span.last + 1,
                kind = SegmentKind.SPEECH,
                roleId = role?.id,
                roleName = role?.name,
                defaultRole = role == null,
                voiceId = role?.voice?.voiceId?.takeIf { it.isNotBlank() } ?: defaultVoice,
                reason = attribution?.reason ?: AttributionReason.NO_EVIDENCE,
            )
            cursor = span.last + 1
        }
        if (cursor < text.length) {
            result += AnalyzedSegment(
                start = cursor,
                endExclusive = text.length,
                kind = SegmentKind.NARRATION,
                roleId = null,
                roleName = null,
                defaultRole = false,
                voiceId = narrator,
                reason = null,
            )
        }
        return result
    }

    private fun attachUnreadableSpans(text: String, segments: List<SpeechSegment>): List<SpeechSegment> {
        val result = ArrayList<SpeechSegment>(segments.size)
        var pendingStart: Int? = null
        for (segment in segments) {
            val spanText = text.substring(segment.start, segment.endExclusive)
            if (!MultiRoleValidator.hasReadableContent(spanText)) {
                if (result.isNotEmpty()) {
                    val last = result.removeAt(result.lastIndex)
                    result += last.copy(endExclusive = segment.endExclusive)
                } else if (pendingStart == null) {
                    pendingStart = segment.start
                }
            } else {
                result += segment.copy(start = pendingStart ?: segment.start)
                pendingStart = null
            }
        }
        if (result.isEmpty() && pendingStart != null) {
            // 全部为纯标点：保留完整区间，由 hasReadableContent 判断不合成
            result += SpeechSegment(pendingStart, text.length, SegmentKind.NARRATION, null, "")
        }
        return result
    }

    private fun mergeAdjacentSameVoice(segments: List<SpeechSegment>): List<SpeechSegment> {
        val result = ArrayList<SpeechSegment>(segments.size)
        for (segment in segments) {
            val last = result.lastOrNull()
            if (last != null &&
                last.endExclusive == segment.start &&
                last.voiceId == segment.voiceId &&
                last.roleId == segment.roleId &&
                last.kind == segment.kind
            ) {
                result[result.lastIndex] = last.copy(endExclusive = segment.endExclusive)
            } else {
                result += segment
            }
        }
        return result
    }

    private fun coversExactlyOnce(segments: List<SpeechSegment>, textLength: Int): Boolean {
        var cursor = 0
        for (segment in segments) {
            if (segment.start != cursor || segment.endExclusive <= segment.start) return false
            cursor = segment.endExclusive
        }
        return cursor == textLength
    }

    /** 解析入口：扫描 + 证据 + 归属 + 计划。 */
    fun parse(
        text: String,
        profile: ReadingProfile,
        maxTextCodePoints: Int = MultiRoleParser.MAX_TEXT_CODE_POINTS,
    ): ParsedReading {
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints > maxTextCodePoints) {
            return ParsedReading(
                profileId = profile.id,
                plan = narrationFallback(
                    text, profile,
                    PlanWarnings(budgetExceeded = true),
                ),
                speechCount = 0,
            )
        }
        val scan = QuoteScanner.scan(text, profile.delimiters)
        if (scan.limitExceeded) {
            return ParsedReading(
                profileId = profile.id,
                plan = narrationFallback(
                    text, profile,
                    PlanWarnings(limitExceeded = true),
                ),
                speechCount = 0,
            )
        }
        val warnings = PlanWarnings(unclosedQuote = scan.unclosedTail)
        val spans = scan.speechSpans
        if (spans.isEmpty()) {
            return ParsedReading(
                profileId = profile.id,
                plan = narrationFallback(text, profile, warnings),
                speechCount = 0,
            )
        }
        val dictionary = RoleDictionary(profile.roles)
        val extractor = AttributionEvidenceExtractor(dictionary)
        val matchText = RoleDictionary.buildMatchText(text)

        // 每个 gap 只分析一次，左右两侧共用同一份证据，避免各台词独立搜索后互相争抢
        val speechEvidences = List(spans.size) { mutableListOf<AttributionEvidenceExtractor.Evidence>() }
        var totalCandidates = 0
        val gapCount = spans.size + 1
        for (gapIndex in 0 until gapCount) {
            val gapStart = if (gapIndex == 0) 0 else spans[gapIndex - 1].last + 1
            val gapEnd = if (gapIndex == spans.size) text.length else spans[gapIndex].first
            val hasLeft = gapIndex > 0
            val hasRight = gapIndex < spans.size
            val evidence = extractor.analyzeGap(text, matchText, gapStart, gapEnd, hasLeft, hasRight)
            totalCandidates += evidence.candidateCount
            if (totalCandidates > MAX_EVIDENCE_CANDIDATES) {
                return ParsedReading(
                    profileId = profile.id,
                    plan = narrationFallback(
                        text, profile,
                        warnings.copy(limitExceeded = true),
                    ),
                    speechCount = spans.size,
                )
            }
            if (hasLeft) speechEvidences[gapIndex - 1] += evidence.forLeft
            if (hasRight) speechEvidences[gapIndex] += evidence.forRight
        }

        val attributor = RoleAttributor(RoleAttributor.validRoleIds(profile.roles))
        val attributions = speechEvidences.map { attributor.attribute(it) }
        val analysis = buildInitialSpans(text, spans, profile, attributions)
        val plan = build(text, analysis, profile, warnings)
        return ParsedReading(profileId = profile.id, plan = plan, speechCount = spans.size)
    }

    private const val MAX_EVIDENCE_CANDIDATES = 4096
}
