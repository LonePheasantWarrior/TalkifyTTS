package com.github.lonepheasantwarrior.talkify.service.role

/**
 * 对话模式文本切分器（纯 Kotlin，无 Android 依赖，可单测）
 *
 * 在合成前本地完成「旁白 / 对话」切分，不依赖模型自己识别角色。
 *
 * 规则：
 * 1. 引号对：“”、「」、『』、‘’、""
 *    - 引号外 → [SegmentKind.NARRATOR]（旁白）
 *    - 引号内（最外层）→ [SegmentKind.DIALOGUE]（对话）
 *    - 嵌套引号只认最外层，内层引号字符原样保留
 *    - 未闭合引号：剩余文本并入当前对话段（保守策略，避免整段变旁白）
 * 2. 显式说话人标记：`<<人物名>>`
 *    - 标记本身不朗读，从输出文本中移除
 *    - 标记对其后紧邻的第一段对话生效，之后回落到未标记状态
 * 3. 每段对话携带 [DialogueSegment.contextBefore]（紧邻其前的旁白原文），
 *    供角色规则按「人名 + 说/道/问」前缀匹配说话人
 * 4. 空白 / 纯标点段被丢弃；[readQuotes] = false 时去除最外层引号字符
 *
 * 输出保证：所有段文本按顺序拼接 ≈ 原文去掉引号字符与说话人标记后的文本。
 */
object DialogueSegmenter {

    /** 引号对（开引号 → 闭引号） */
    private val QUOTE_PAIRS: List<Pair<Char, Char>> = listOf(
        '“' to '”',
        '「' to '」',
        '『' to '』',
        '‘' to '’',
        '"' to '"'
    )

    private val OPEN_TO_CLOSE: Map<Char, Char> = QUOTE_PAIRS.toMap()
    private val CLOSE_QUOTES: Set<Char> = QUOTE_PAIRS.map { it.second }.toSet()

    /** 显式说话人标记：<<人物名>>，名字限 1~24 个非尖括号字符 */
    private val SPEAKER_MARKER = Regex("<<([^<>\\n]{1,24})>>")

    /**
     * @param text 待切分文本（通常为阅读软件传入的一个段落）
     * @param readQuotes true 时保留最外层引号字符一起朗读
     * @return 切分结果（可能为空：全文都是空白/标点时）
     */
    fun segment(text: String, readQuotes: Boolean): List<DialogueSegment> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<DialogueSegment>()
        val buffer = StringBuilder()
        var currentKind = SegmentKind.NARRATOR

        // 引号嵌套栈：记录未闭合的开引号字符
        val openStack = ArrayDeque<Char>()
        // 当前对话段的说话人（来自 <<...>> 标记；null 表示未标记）
        var pendingSpeaker: String? = null

        var i = 0
        while (i < text.length) {
            val c = text[i]

            // 1) 显式说话人标记
            if (c == '<' && i + 1 < text.length && text[i + 1] == '<') {
                val match = SPEAKER_MARKER.find(text, i)
                if (match != null && match.range.first == i) {
                    pendingSpeaker = match.groupValues[1].trim()
                    i += match.value.length
                    continue
                }
            }

            // 2) 闭引号（必须先于开引号判断：ASCII 双引号同时是开/闭引号）
            val top = openStack.lastOrNull()
            val isClosing = c in CLOSE_QUOTES && top != null && OPEN_TO_CLOSE[top] == c
            if (isClosing) {
                if (openStack.size > 1 || readQuotes) {
                    // 嵌套闭引号 / 要求朗读引号时保留字符
                    buffer.append(c)
                }
                openStack.removeLast()
                if (openStack.isEmpty()) {
                    // 对话 → 旁白边界：回填占位段文本
                    val last = segments.last()
                    segments[segments.lastIndex] = last.copy(text = buffer.toString().trim())
                    buffer.setLength(0)
                    currentKind = SegmentKind.NARRATOR
                }
                i++
                continue
            }

            // 3) 开引号
            val close = OPEN_TO_CLOSE[c]
            if (close != null) {
                if (currentKind == SegmentKind.NARRATOR) {
                    // 旁白 → 对话边界：先落盘旁白，再放对话占位段（闭引号时回填文本）
                    val contextBefore = buffer.toString().trim()
                    if (contextBefore.isNotEmpty()) {
                        segments.add(
                            DialogueSegment(kind = SegmentKind.NARRATOR, text = contextBefore)
                        )
                    }
                    buffer.setLength(0)
                    currentKind = SegmentKind.DIALOGUE
                    segments.add(
                        DialogueSegment(
                            kind = SegmentKind.DIALOGUE,
                            text = "",
                            speaker = pendingSpeaker,
                            contextBefore = contextBefore
                        )
                    )
                    pendingSpeaker = null
                }
                openStack.addLast(c)
                if (readQuotes || openStack.size > 1) {
                    // readQuotes：保留最外层引号；嵌套引号始终保留
                    buffer.append(c)
                }
                i++
                continue
            }

            // 4) 普通字符
            buffer.append(c)
            i++
        }

        // 收尾：缓冲区剩余文本
        val remaining = buffer.toString().trim()
        val lastIdx = segments.lastIndex
        if (openStack.isNotEmpty()) {
            // 未闭合引号：剩余文本并入对话段
            if (lastIdx >= 0 && segments[lastIdx].kind == SegmentKind.DIALOGUE) {
                val last = segments[lastIdx]
                val merged = (last.text + remaining).trim()
                segments[lastIdx] = last.copy(text = merged)
            } else if (remaining.isNotEmpty()) {
                segments.add(DialogueSegment(kind = SegmentKind.DIALOGUE, text = remaining))
            }
        } else if (remaining.isNotEmpty()) {
            segments.add(DialogueSegment(kind = SegmentKind.NARRATOR, text = remaining))
        }

        return segments.filter { segment ->
            segment.text.isNotBlank() && segment.text.any { it.isLetterOrDigit() }
        }
    }
}

/** 段类型 */
enum class SegmentKind {
    /** 旁白（引号外） */
    NARRATOR,

    /** 对话（引号内） */
    DIALOGUE
}

/**
 * 切分后的段落
 *
 * @param kind 段类型
 * @param text 段文本（已去除说话人标记；readQuotes=false 时不含最外层引号）
 * @param speaker 显式说话人名（来自 `<<人物名>>` 标记），null 表示未标记
 * @param contextBefore 对话段紧邻其前的旁白原文（供角色规则匹配「人名+说」前缀）；
 *                      旁白段为空字符串
 */
data class DialogueSegment(
    val kind: SegmentKind,
    val text: String,
    val speaker: String? = null,
    val contextBefore: String = ""
)
