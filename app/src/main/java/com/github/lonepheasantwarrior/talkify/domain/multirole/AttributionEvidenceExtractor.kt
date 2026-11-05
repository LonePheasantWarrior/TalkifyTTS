package com.github.lonepheasantwarrior.talkify.domain.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId

/**
 * 从台词外的相邻旁白片段提取可解释的说话人证据。
 *
 * 只做文档第 5.2 节定义的有限语法，不做任意正则或宽泛 `contains(name)`：
 * - 中文：`角色 + 说话动词`、`角色 + 对/向 + 另一个角色 + 说话动词`（前一个角色是说话人）；
 * - 英文：`角色 + 空白 + 动词`、`动词 + 空白 + 角色`，英文名遵守词界且大小写无关；
 * - 名称前只允许真实窗口起点、空白、标点/引号边界或明确连接词 `然后/接着/但/而`。
 *
 * 所有区间使用原始 UTF-16 偏移；证据只来自紧邻台词的 gap，最多扫描台词侧 80 个码点。
 */
internal class AttributionEvidenceExtractor(
    private val dictionary: RoleDictionary,
) {

    enum class Direction { LEFT, RIGHT, BOTH }

    enum class Rule { POSTPOSED, PREPOSED, SHARED_COLON, SHARED_COMMA }

    data class Evidence(
        val speakerRoleId: RoleId,
        val speakerName: String,
        val direction: Direction,
        val rule: Rule,
        val speakerStart: Int,
        val speakerEndExclusive: Int,
        val labelStart: Int,
        val labelEndExclusive: Int,
    )

    data class GapEvidence(
        val forLeft: List<Evidence> = emptyList(),
        val forRight: List<Evidence> = emptyList(),
        val candidateCount: Int = 0,
    )

    private data class Label(
        val roleId: RoleId,
        val roleName: String,
        val start: Int,
        val endExclusive: Int,
    )

    private data class Window(
        val start: Int,
        val endExclusive: Int,
        val truncatedStart: Boolean,
    )

    /**
     * 分析一个 gap。`hasLeft` / `hasRight` 表示 gap 左侧/右侧是否存在台词。
     * 返回的证据已按方向绑定到对应台词，由 [RoleAttributor] 统一裁决。
     */
    fun analyzeGap(
        text: String,
        matchText: String,
        gapStart: Int,
        gapEndExclusive: Int,
        hasLeft: Boolean,
        hasRight: Boolean,
    ): GapEvidence {
        if (gapStart >= gapEndExclusive) return GapEvidence()
        val boundaries = findStrongBoundaries(text, gapStart, gapEndExclusive)
        return if (boundaries.isNotEmpty()) {
            analyzeGapWithBoundaries(
                text, matchText, gapStart, gapEndExclusive, hasLeft, hasRight,
                boundaries.first(), boundaries.last(),
            )
        } else {
            when {
                hasLeft && hasRight -> analyzeSharedGap(text, matchText, gapStart, gapEndExclusive)
                hasRight -> {
                    val window = takeLastCodePoints(text, gapStart, gapEndExclusive, MAX_SIDE_CODE_POINTS)
                    GapEvidence(
                        forRight = analyzePreposed(
                            text, matchText,
                            realWindowStart = gapStart,
                            window = window,
                            windowTruncatedAtStart = window.truncatedStart,
                            boundaryEnd = gapEndExclusive,
                        ),
                    )
                }
                hasLeft -> GapEvidence(
                    forLeft = analyzePostposed(
                        text, matchText, gapStart,
                        trailingEnd = gapEndExclusive,
                        window = takeFirstCodePoints(text, gapStart, gapEndExclusive, MAX_SIDE_CODE_POINTS),
                    ),
                )
                else -> GapEvidence()
            }
        }
    }

    private fun analyzeGapWithBoundaries(
        text: String,
        matchText: String,
        gapStart: Int,
        gapEndExclusive: Int,
        hasLeft: Boolean,
        hasRight: Boolean,
        firstBoundary: Boundary,
        lastBoundary: Boundary,
    ): GapEvidence {
        val left = if (hasLeft && firstBoundary.index > gapStart) {
            analyzePostposed(
                text, matchText, gapStart,
                trailingEnd = firstBoundary.index,
                window = takeFirstCodePoints(text, gapStart, firstBoundary.index, MAX_SIDE_CODE_POINTS),
            )
        } else {
            emptyList()
        }
        val rightStart = lastBoundary.index + lastBoundary.width
        val right = if (hasRight && rightStart < gapEndExclusive) {
            analyzePreposed(
                text, matchText,
                realWindowStart = rightStart,
                window = takeLastCodePoints(text, rightStart, gapEndExclusive, MAX_SIDE_CODE_POINTS),
                windowTruncatedAtStart = takeLastCodePoints(
                    text, rightStart, gapEndExclusive, MAX_SIDE_CODE_POINTS
                ).truncatedStart,
                boundaryEnd = gapEndExclusive,
            )
        } else {
            emptyList()
        }
        return GapEvidence(left, right, left.size + right.size)
    }

    private fun analyzeSharedGap(
        text: String,
        matchText: String,
        gapStart: Int,
        gapEndExclusive: Int,
    ): GapEvidence {
        val gapCodePoints = text.codePointCount(gapStart, gapEndExclusive)
        if (gapCodePoints > MAX_SIDE_CODE_POINTS) return GapEvidence()
        var labelStart = gapStart
        while (labelStart < gapEndExclusive) {
            val cp = text.codePointAt(labelStart)
            if (!MultiRoleValidator.isUnicodeWhitespace(cp)) break
            labelStart += Character.charCount(cp)
        }
        val label = parseLabelAt(text, matchText, labelStart, gapEndExclusive) ?: return GapEvidence()
        if (label.endExclusive > gapEndExclusive) return GapEvidence()
        if (!isAllowedRoleStart(text, labelStart, realWindowStart = gapStart)) return GapEvidence()

        var tail = label.endExclusive
        while (tail < gapEndExclusive) {
            val cp = text.codePointAt(tail)
            if (!MultiRoleValidator.isUnicodeWhitespace(cp)) break
            tail += Character.charCount(cp)
        }
        if (tail >= gapEndExclusive) return GapEvidence()
        val punctuation = text.codePointAt(tail)
        val punctWidth = Character.charCount(punctuation)
        var after = tail + punctWidth
        while (after < gapEndExclusive) {
            val cp = text.codePointAt(after)
            if (!MultiRoleValidator.isUnicodeWhitespace(cp)) return GapEvidence()
            after += Character.charCount(cp)
        }
        val rule = when (punctuation) {
            ':'.code, '：'.code -> Rule.SHARED_COLON
            ','.code, '，'.code, '、'.code -> Rule.SHARED_COMMA
            else -> return GapEvidence()
        }
        val evidence = Evidence(
            speakerRoleId = label.roleId,
            speakerName = label.roleName,
            direction = if (rule == Rule.SHARED_COMMA) Direction.BOTH else Direction.RIGHT,
            rule = rule,
            speakerStart = label.start,
            speakerEndExclusive = label.endExclusive,
            labelStart = label.start,
            labelEndExclusive = label.endExclusive,
        )
        return if (rule == Rule.SHARED_COMMA) {
            GapEvidence(forLeft = listOf(evidence), forRight = listOf(evidence), candidateCount = 1)
        } else {
            GapEvidence(forRight = listOf(evidence), candidateCount = 1)
        }
    }

    /**
     * 后置标签：左台词闭引号与标签之间只允许空白/逗号/顿号，标签后到强边界/段落末
     * 只允许空白和句末标点。窗口从 gap 起点（靠近台词）取前 80 码点。
     */
    private fun analyzePostposed(
        text: String,
        matchText: String,
        gapStart: Int,
        trailingEnd: Int,
        window: Window,
    ): List<Evidence> {
        // 窗口在尾部被截断时无法证明“标签后只有空白/句末标点”，保守不使用
        if (window.endExclusive < trailingEnd) return emptyList()
        val evidence = ArrayList<Evidence>(1)
        var pos = window.start
        while (pos < window.endExclusive) {
            val label = parseLabelAt(text, matchText, pos, window.endExclusive)
            if (label != null &&
                leadingOnlyWhitespaceOrComma(text, gapStart, label.start) &&
                trailingOnlyWhitespaceAndTerminal(text, label.endExclusive, trailingEnd)
            ) {
                evidence += Evidence(
                    speakerRoleId = label.roleId,
                    speakerName = label.roleName,
                    direction = Direction.LEFT,
                    rule = Rule.POSTPOSED,
                    speakerStart = label.start,
                    speakerEndExclusive = label.endExclusive,
                    labelStart = label.start,
                    labelEndExclusive = label.endExclusive,
                )
                // 同一段只接受一个后置标签；后续候选一定包含前一个标签文本，结构不再成立
                break
            }
            pos += Character.charCount(text.codePointAt(pos))
        }
        return evidence
    }

    /**
     * 前置标签：标签后到右侧开引号之间只允许空白和至多一个冒号/逗号。
     * 窗口从 gap 末尾（靠近台词）取后 80 码点；标签前可存在其他旁白文字，但中文名
     * 仍须处在受支持的语法位置（空白/标点/连接词之后），且不能从人工窗口起点截断。
     */
    private fun analyzePreposed(
        text: String,
        matchText: String,
        realWindowStart: Int,
        window: Window,
        windowTruncatedAtStart: Boolean,
        boundaryEnd: Int,
    ): List<Evidence> {
        val evidence = ArrayList<Evidence>(1)
        var pos = window.start
        while (pos < window.endExclusive) {
            val label = parseLabelAt(text, matchText, pos, window.endExclusive)
            if (label != null && label.endExclusive <= window.endExclusive) {
                val startsAtArtificialEdge = windowTruncatedAtStart && pos == window.start
                val precedingOk = !startsAtArtificialEdge &&
                    isAllowedRoleStart(text, label.start, realWindowStart)
                if (precedingOk &&
                    trailingOnlyWhitespaceAndOneConnector(text, label.endExclusive, boundaryEnd)
                ) {
                    evidence += Evidence(
                        speakerRoleId = label.roleId,
                        speakerName = label.roleName,
                        direction = Direction.RIGHT,
                        rule = Rule.PREPOSED,
                        speakerStart = label.start,
                        speakerEndExclusive = label.endExclusive,
                        labelStart = label.start,
                        labelEndExclusive = label.endExclusive,
                    )
                    break
                }
            }
            pos += Character.charCount(text.codePointAt(pos))
        }
        return evidence
    }

    // ==================== 标签解析 ====================

    private fun parseLabelAt(
        text: String,
        matchText: String,
        start: Int,
        endExclusive: Int,
    ): Label? {
        if (start < 0 || start >= endExclusive || start >= matchText.length) return null

        // 英文倒装：动词 + 至少一个空白 + 角色
        parseEnglishReverseLabel(matchText, start, endExclusive)?.let { return it }

        val roleEntries = dictionary.matchAt(matchText, start, endExclusive)
        if (roleEntries.isEmpty()) return null
        // 校验保证名称/别名归一化后唯一；同长多角色属异常配置，按歧义放弃
        if (roleEntries.size > 1) return null
        val role = roleEntries.first()
        val roleEnd = start + role.normalized.length

        // 角色 + 对/向 + 另一个角色 + 动词（整体优先，受话人不会被单独抽成说话人）
        var cursor = skipWhitespace(text, roleEnd, endExclusive)
        if (cursor < endExclusive) {
            val connector = text.codePointAt(cursor)
            if (connector == '对'.code || connector == '向'.code) {
                cursor = skipWhitespace(text, cursor + Character.charCount(connector), endExclusive)
                val receiverEntries = dictionary.matchAt(matchText, cursor, endExclusive)
                if (receiverEntries.size == 1) {
                    val receiver = receiverEntries.first()
                    var afterReceiver = skipWhitespace(
                        text, cursor + receiver.normalized.length, endExclusive
                    )
                    val verb = matchVerb(text, afterReceiver, endExclusive)
                    if (verb != null) {
                        return Label(role.roleId, role.roleName, start, verb.endExclusive)
                    }
                }
            }
        }

        val simple = parseSimpleForwardLabel(text, matchText, start, endExclusive, role)
        return simple
    }

    private fun parseSimpleForwardLabel(
        text: String,
        matchText: String,
        start: Int,
        endExclusive: Int,
        role: RoleDictionary.Entry,
    ): Label? {
        val roleEnd = start + role.normalized.length
        val afterWhitespace = skipWhitespace(text, roleEnd, endExclusive)
        val verb = matchVerb(text, afterWhitespace, endExclusive) ?: return null
        // 英文角色与动词之间至少一个空白字符
        if (verb.isEnglish && afterWhitespace == roleEnd) return null
        return Label(role.roleId, role.roleName, start, verb.endExclusive)
    }

    private fun parseEnglishReverseLabel(
        matchText: String,
        start: Int,
        endExclusive: Int,
    ): Label? {
        val verb = matchVerb(matchText, start, endExclusive) ?: return null
        if (!verb.isEnglish) return null
        var cursor = verb.endExclusive
        var whitespace = 0
        while (cursor < endExclusive) {
            val cp = matchText.codePointAt(cursor)
            if (!MultiRoleValidator.isUnicodeWhitespace(cp)) break
            cursor += Character.charCount(cp)
            whitespace++
        }
        if (whitespace == 0) return null
        val entries = dictionary.matchAt(matchText, cursor, endExclusive)
        if (entries.size != 1) return null
        val role = entries.first()
        return Label(role.roleId, role.roleName, start, cursor + role.normalized.length)
    }

    /** 中文/英文说话动词，最长优先；英文要求词界。 */
    private fun matchVerb(text: String, start: Int, endExclusive: Int): VerbMatch? {
        var best: VerbMatch? = null
        for (verb in CHINESE_VERBS) {
            if (start + verb.length <= endExclusive && text.regionMatches(start, verb, 0, verb.length)) {
                if (best == null || verb.length > best.endExclusive - start) {
                    best = VerbMatch(start + verb.length, isEnglish = false)
                }
            }
        }
        for (verb in ENGLISH_VERBS) {
            if (start + verb.length > endExclusive) continue
            if (!text.regionMatches(start, verb, 0, verb.length, ignoreCase = true)) continue
            if (start > 0 && RoleDictionary.isAsciiWordChar(text[start - 1])) continue
            val after = start + verb.length
            if (after < endExclusive && RoleDictionary.isAsciiWordChar(text[after])) continue
            if (best == null || verb.length > best.endExclusive - start) {
                best = VerbMatch(after, isEnglish = true)
            }
        }
        return best
    }

    private data class VerbMatch(val endExclusive: Int, val isEnglish: Boolean)

    // ==================== 上下文与边界 ====================

    private fun isAllowedRoleStart(text: String, start: Int, realWindowStart: Int): Boolean {
        if (start == realWindowStart) return true
        if (start <= 0) return true
        val previous = text.codePointBefore(start)
        if (MultiRoleValidator.isUnicodeWhitespace(previous)) return true
        if (isPunctuation(previous)) return true
        // 明确连接词：然后 / 接着 / 但 / 而
        for (connector in CONNECTORS) {
            if (start >= connector.length &&
                text.regionMatches(start - connector.length, connector, 0, connector.length)
            ) {
                return true
            }
        }
        return false
    }

    private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }

    private fun leadingOnlyWhitespaceOrComma(text: String, start: Int, endExclusive: Int): Boolean {
        var i = start
        while (i < endExclusive) {
            val cp = text.codePointAt(i)
            if (!MultiRoleValidator.isUnicodeWhitespace(cp) &&
                cp != ','.code && cp != '，'.code && cp != '、'.code
            ) {
                return false
            }
            i += Character.charCount(cp)
        }
        return true
    }

    private fun trailingOnlyWhitespaceAndTerminal(text: String, start: Int, endExclusive: Int): Boolean {
        var i = start
        while (i < endExclusive) {
            val cp = text.codePointAt(i)
            if (!MultiRoleValidator.isUnicodeWhitespace(cp) && !isTerminalPunctuation(cp)) {
                return false
            }
            i += Character.charCount(cp)
        }
        return true
    }

    private fun trailingOnlyWhitespaceAndOneConnector(text: String, start: Int, endExclusive: Int): Boolean {
        var connectorCount = 0
        var i = start
        while (i < endExclusive) {
            val cp = text.codePointAt(i)
            if (MultiRoleValidator.isUnicodeWhitespace(cp)) {
                i += Character.charCount(cp)
                continue
            }
            if (cp == ':'.code || cp == '：'.code || cp == ','.code || cp == '，'.code) {
                connectorCount++
                if (connectorCount > 1) return false
                i += Character.charCount(cp)
                continue
            }
            return false
        }
        return true
    }

    private fun skipWhitespace(text: String, start: Int, endExclusive: Int): Int {
        var i = start
        while (i < endExclusive) {
            val cp = text.codePointAt(i)
            if (!MultiRoleValidator.isUnicodeWhitespace(cp)) break
            i += Character.charCount(cp)
        }
        return i
    }

    // ==================== 强边界与窗口 ====================

    private data class Boundary(val index: Int, val width: Int, val cp: Int)

    private fun findStrongBoundaries(text: String, start: Int, endExclusive: Int): List<Boundary> {
        val result = ArrayList<Boundary>()
        var i = start
        while (i < endExclusive) {
            val cp = text.codePointAt(i)
            if (isStrongBoundary(cp)) result += Boundary(i, Character.charCount(cp), cp)
            i += Character.charCount(cp)
        }
        return result
    }

    private fun takeFirstCodePoints(text: String, start: Int, endExclusive: Int, max: Int): Window {
        var i = start
        var count = 0
        while (i < endExclusive && count < max) {
            i += Character.charCount(text.codePointAt(i))
            count++
        }
        return Window(start, i, truncatedStart = false)
    }

    private fun takeLastCodePoints(text: String, start: Int, endExclusive: Int, max: Int): Window {
        var i = endExclusive
        var count = 0
        while (i > start && count < max) {
            val cp = text.codePointBefore(i)
            i -= Character.charCount(cp)
            count++
        }
        return Window(i, endExclusive, truncatedStart = i > start)
    }

    private companion object {
        const val MAX_SIDE_CODE_POINTS = 80

        val CHINESE_VERBS = listOf(
            "低声说", "轻声说", "说道", "问道", "答道", "喊道", "叫道", "笑道", "叹道",
            "回答", "说", "道", "问", "答", "喊", "叫",
        ).sortedByDescending { it.length }

        val ENGLISH_VERBS = listOf(
            "whispered", "shouted", "answered", "replied", "asked", "says", "asks",
            "replies", "answers", "said",
        ).sortedByDescending { it.length }

        val CONNECTORS = listOf("然后", "接着", "但", "而")

        fun isTerminalPunctuation(cp: Int): Boolean = when (cp) {
            '。'.code, '！'.code, '？'.code, '；'.code,
            '.'.code, '!'.code, '?'.code, ';'.code,
            '…'.code, '．'.code,
            '\n'.code, '\r'.code, 0x2028, 0x2029 -> true
            else -> false
        }

        fun isStrongBoundary(cp: Int): Boolean = when (cp) {
            '。'.code, '！'.code, '？'.code, '；'.code,
            '.'.code, '!'.code, '?'.code, ';'.code,
            '．'.code, '\n'.code, '\r'.code, 0x2028, 0x2029 -> true
            else -> false
        }
    }
}
