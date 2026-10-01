package com.github.lonepheasantwarrior.talkify.service.provider

/**
 * 文本分块工具：将长文本按句子边界智能分割为多个片段。
 *
 * 所有 Provider 使用统一的分割策略。对超出 [maxLength] 的剩余文本，在
 * `maxLength` 窗口内自后向前按优先级寻找切分点：
 * 1. 句子结尾标点（。！？.!?）之后
 * 2. 句中停顿标点（，、,;；：:）之后
 * 3. 空白字符（空格/换行/制表）之后
 * 4. 窗口内无任何可切点时在 maxLength 处硬切（回退 UTF-16 代理对边界，
 *    不切断 emoji / 扩展汉字）
 *
 * 自后向前的搜索使分块尽量用满 maxLength（减少分块数 = 减少网络请求），
 * 且优先保留完整句子——旧实现在窗口内只检查"当前位置恰为标点"，句末
 * 标点优先策略整体失效，长句被拦腰斩断（P1-1）。
 *
 * 保证：每个片段不超过 [maxLength] 字符；所有片段按序拼接等于原文。
 */
object TextChunkSplitter {

    /**
     * 将长文本按句子边界智能分割，每个片段不超过 [maxLength] 字符
     *
     * @param text 待分割的原始文本
     * @param maxLength 每个片段的最大字符数（UTF-16 code unit）
     * @return 分割后的文本片段列表
     */
    fun split(text: String, maxLength: Int): List<String> {
        require(maxLength > 0) { "maxLength must be positive: $maxLength" }
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0

        while (text.length - start > maxLength) {
            val windowEnd = start + maxLength

            // 自后向前按优先级寻找切分点（切点为 exclusive end，标点留在当前块尾）
            var splitPos = findLatestBy(text, start, windowEnd) { idx -> isSentenceEnd(text, idx) }
            if (splitPos <= start) {
                splitPos = findLatestBy(text, start, windowEnd) { idx -> isMidPause(text, idx) }
            }
            if (splitPos <= start) {
                splitPos = findLatestBy(text, start, windowEnd) { idx -> isWhitespace(text, idx) }
            }
            if (splitPos <= start) {
                splitPos = safeHardCut(text, start, windowEnd)
            }

            chunks.add(text.substring(start, splitPos))
            start = splitPos
        }

        if (start < text.length) {
            chunks.add(text.substring(start))
        }
        return chunks
    }

    /**
     * 在 (start, windowEnd) 内自后向前寻找满足 [predicate] 的字符位置，
     * 返回该字符之后的切分点（idx + 1）；无匹配返回 [start]（表示未找到）
     */
    private inline fun findLatestBy(
        text: String,
        start: Int,
        windowEnd: Int,
        predicate: (Int) -> Boolean
    ): Int {
        for (idx in windowEnd - 1 downTo start + 1) {
            if (predicate(idx)) return idx + 1
        }
        return start
    }

    /**
     * maxLength 处硬切，但不得切断 UTF-16 代理对（emoji/扩展汉字等，
     * P2-B5）：切点落在低位代理上时回退一位，把整个代理对留给下一块
     */
    private fun safeHardCut(text: String, start: Int, cut: Int): Int {
        return if (cut > start + 1 && cut < text.length &&
            Character.isLowSurrogate(text[cut]) && Character.isHighSurrogate(text[cut - 1])
        ) {
            cut - 1
        } else {
            cut
        }
    }

    private fun isSentenceEnd(text: String, index: Int): Boolean {
        return when (text[index]) {
            '。', '！', '？', '.', '!', '?' -> true
            else -> false
        }
    }

    private fun isMidPause(text: String, index: Int): Boolean {
        return when (text[index]) {
            '，', '、', ',', ';', '；', '：', ':' -> true
            else -> false
        }
    }

    private fun isWhitespace(text: String, index: Int): Boolean {
        return when (text[index]) {
            ' ', '\n', '\t' -> true
            else -> false
        }
    }
}
