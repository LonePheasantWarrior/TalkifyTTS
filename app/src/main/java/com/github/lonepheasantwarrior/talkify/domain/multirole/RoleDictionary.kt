package com.github.lonepheasantwarrior.talkify.domain.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.Role
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId

/**
 * 角色名/别名词典：按首字符索引的字面量匹配器，天然保证确定性。
 *
 * - 同一位置按最长词优先；同长不同角色由上层视为冲突（返回第一个按稳定排序的候选也能
 *   触发冲突，但本实现显式返回全部同长候选，避免静默按配置顺序决胜）；
 * - 包含 ASCII 字母/数字/下划线的名称强制英文词界（前后不得与 ASCII 词字符相接）；
 * - 匹配在 ASCII 小写化的等长文本（[matchText]）上进行，原始偏移不变。
 */
internal class RoleDictionary(roles: List<Role>) {

    data class Entry(
        val roleId: RoleId,
        val roleName: String,
        val normalized: String,
        val requiresAsciiBoundary: Boolean,
    )

    private val byFirstChar: Map<Char, List<Entry>>

    init {
        val entries = ArrayList<Entry>()
        for (role in roles) {
            val names = buildList {
                add(role.name)
                addAll(role.aliases)
            }
            for (name in names) {
                val normalized = MultiRoleValidator.normalizeForMatch(name)
                if (normalized.isEmpty()) continue
                entries += Entry(
                    roleId = role.id,
                    roleName = role.name,
                    normalized = normalized,
                    requiresAsciiBoundary = normalized.any { isAsciiWordChar(it) },
                )
            }
        }
        byFirstChar = entries
            .groupBy { it.normalized[0] }
            .mapValues { (_, list) -> list.sortedByDescending { it.normalized.length } }
    }

    val isEmpty: Boolean get() = byFirstChar.isEmpty()

    /**
     * 在 [matchText] 的 [index]（UTF-16）处匹配角色名。
     * 返回全部同长候选（不同角色同长时上层判冲突），长候选优先。
     */
    fun matchAt(matchText: String, index: Int, endExclusive: Int = matchText.length): List<Entry> {
        if (index !in matchText.indices || index >= endExclusive) return emptyList()
        val candidates = byFirstChar[matchText[index]] ?: return emptyList()
        var bestLength = -1
        val result = ArrayList<Entry>(2)
        for (entry in candidates) {
            val text = entry.normalized
            if (index + text.length > endExclusive) continue
            if (!matchText.regionMatches(index, text, 0, text.length)) continue
            if (entry.requiresAsciiBoundary) {
                if (index > 0 && isAsciiWordChar(matchText[index - 1])) continue
                val after = index + text.length
                if (after < endExclusive && isAsciiWordChar(matchText[after])) continue
            }
            when {
                text.length > bestLength -> {
                    bestLength = text.length
                    result.clear()
                    result += entry
                }
                text.length == bestLength -> result += entry
            }
        }
        return result
    }

    companion object {
        fun isAsciiWordChar(ch: Char): Boolean =
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'

        fun isAsciiWordChar(cp: Int): Boolean = cp in 0x30..0x39 || cp in 0x41..0x5A ||
            cp in 0x61..0x7A || cp == 0x5F

        /** 等长 ASCII 小写化（其他字符原样），保证原文偏移可直接复用。 */
        fun buildMatchText(text: String): String {
            if (text.none { it in 'A'..'Z' }) return text
            val chars = CharArray(text.length)
            for (i in text.indices) {
                val ch = text[i]
                chars[i] = if (ch in 'A'..'Z') ch + ('a' - 'A') else ch
            }
            return String(chars)
        }
    }
}
