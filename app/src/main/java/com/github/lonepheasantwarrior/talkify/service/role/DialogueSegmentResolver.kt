package com.github.lonepheasantwarrior.talkify.service.role

import com.github.lonepheasantwarrior.talkify.domain.model.DialogueModeConfig
import com.github.lonepheasantwarrior.talkify.domain.model.RoleRule
import com.github.lonepheasantwarrior.talkify.domain.model.VoiceBinding

/**
 * 切分结果 → 发音人绑定 的解析器（纯 Kotlin，可单测）
 *
 * 优先级（与需求一致）：
 * 1. 显式标记 `<<人物>>` 命中的角色规则
 * 2. 角色规则按人物名 / 正则匹配（说话人名，或对话前旁白文本）
 * 3. 默认对话发音人（引号内）
 * 4. 默认旁白发音人（引号外）
 */
object DialogueSegmentResolver {

    /**
     * @param segments [DialogueSegmenter.segment] 的输出
     * @param config 对话模式配置
     * @return 每段对应的发音人绑定；长度与输入一致
     */
    fun resolve(segments: List<DialogueSegment>, config: DialogueModeConfig): List<ResolvedSegment> {
        return segments.map { segment ->
            val binding = when (segment.kind) {
                SegmentKind.NARRATOR -> config.narrator
                SegmentKind.DIALOGUE -> resolveDialogueBinding(segment, config)
            }
            ResolvedSegment(segment = segment, binding = binding)
        }
    }

    private fun resolveDialogueBinding(segment: DialogueSegment, config: DialogueModeConfig): VoiceBinding {
        // 1) / 2) 角色规则
        matchRule(segment, config.rules)?.let { return it }

        // 3) 默认对话
        return config.dialogue
    }

    /**
     * 匹配角色规则。
     *
     * 说话人显式标记存在时只按「说话人名」匹配；否则对 contextBefore 匹配
     * （覆盖「张三说：“……”」前缀写法）。未配置完整（isUsable=false）的规则跳过。
     */
    private fun matchRule(segment: DialogueSegment, rules: List<RoleRule>): VoiceBinding? {
        val speaker = segment.speaker
        for (rule in rules) {
            if (!rule.isUsable) continue
            if (speaker != null) {
                if (ruleMatchesText(rule, speaker)) return rule.binding
            } else if (segment.contextBefore.isNotEmpty() && ruleMatchesText(rule, segment.contextBefore)) {
                return rule.binding
            }
        }
        return null
    }

    private fun ruleMatchesText(rule: RoleRule, target: String): Boolean {
        return if (rule.isRegex) {
            runCatching { Regex(rule.pattern).containsMatchIn(target) }.getOrDefault(false)
        } else {
            target.contains(rule.pattern)
        }
    }
}

/**
 * 解析结果：一段文本 + 它应使用的发音人绑定
 *
 * @param segment 切分段
 * @param binding 该段使用的发音人绑定（供应商 + 音色 + 风格指令）
 */
data class ResolvedSegment(
    val segment: DialogueSegment,
    val binding: VoiceBinding
)
