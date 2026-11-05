package com.github.lonepheasantwarrior.talkify.domain.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.DelimiterPair
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator

/**
 * 在原始文本上寻找**最外层**完整台词符号对。
 *
 * 规则（文档第 4.2 节）：
 * - 用栈按码点遍历；开闭不同的符号与栈顶配对，允许不同对嵌套；
 * - 开闭相同的对称符号（英文 `"`）在栈顶为同类时闭合，否则开启，不支持自嵌套；
 * - 多余闭符作为普通原文；最外层未闭合时，从其开符起到结尾统一按旁白处理；
 * - 嵌套深度 32、最外层台词 4096 为硬上限，超限整请求退回旁白。
 *
 * 纯 Kotlin，无 Android/I/O 依赖。
 */
object QuoteScanner {

    const val MAX_SPEECH_SPANS = 4096
    const val MAX_NESTING_DEPTH = 32

    data class Result(
        /** 最外层完整台词区间（UTF-16 偏移，左闭右开），按起点升序。 */
        val speechSpans: List<IntRange>,
        val unclosedTail: Boolean,
        val limitExceeded: Boolean,
    )

    private data class Frame(
        val pair: DelimiterPair,
        val speechStart: Int,
    )

    fun scan(text: String, delimiters: List<DelimiterPair>): Result {
        val enabled = delimiters.filter { it.enabled }
        if (enabled.isEmpty() || text.isEmpty()) {
            return Result(emptyList(), unclosedTail = false, limitExceeded = false)
        }
        // 校验保证同一码点只属于一个符号对；这里仍以确定性顺序兜底，避免异常配置导致非确定结果
        val openers = HashMap<Int, DelimiterPair>()
        for (pair in enabled) {
            openers.putIfAbsent(pair.open, pair)
        }

        val stack = ArrayList<Frame>(8)
        val spans = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            val top = stack.lastOrNull()

            // 先判闭合：开闭相同的符号在同类栈顶时闭合，否则开启
            if (top != null && cp == top.pair.close) {
                val frame = stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) {
                    spans += frame.speechStart until (i + width)
                    if (spans.size > MAX_SPEECH_SPANS) {
                        return Result(emptyList(), unclosedTail = false, limitExceeded = true)
                    }
                }
                i += width
                continue
            }

            // 非常规配置下同一码点可能既是开符又是闭符；仅当栈顶不匹配时按开符处理
            val opener = openers[cp]
            if (opener != null) {
                if (stack.size >= MAX_NESTING_DEPTH) {
                    return Result(emptyList(), unclosedTail = false, limitExceeded = true)
                }
                stack += Frame(opener, speechStart = i)
                i += width
                continue
            }

            // 多余闭符（或普通字符）原样保留
            i += width
        }

        val unclosed = stack.isNotEmpty()
        if (unclosed) {
            // 最外层未闭合：其后的所有完整嵌套对都属于这段旁白尾巴
            val outerStart = stack.first().speechStart
            spans.removeAll { it.first >= outerStart }
        }
        return Result(
            speechSpans = spans,
            unclosedTail = unclosed,
            limitExceeded = false,
        )
    }

    /** 便于调用方构造只含旁白的计划。 */
    fun readableDelimiterCount(delimiters: List<DelimiterPair>): Int =
        delimiters.count {
            it.enabled &&
                MultiRoleValidator.isValidDelimiterCodePoint(it.open) &&
                MultiRoleValidator.isValidDelimiterCodePoint(it.close)
        }
}
