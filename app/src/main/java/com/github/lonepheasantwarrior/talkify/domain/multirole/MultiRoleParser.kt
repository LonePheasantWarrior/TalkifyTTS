package com.github.lonepheasantwarrior.talkify.domain.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile

/**
 * 多角色解析器门面（纯 Kotlin、无 Android/I/O）。
 *
 * 单请求独立解析，不携带上一请求的引号或角色状态；角色表与符号在调用时取
 * 不可变快照。文本超过资源预算时整次退回旁白原文（不截断）。
 */
object MultiRoleParser {

    /**
     * 单请求解析的文本上限（Unicode 码点）。超过时整次使用旁白音色合成完整原文。
     * 阅读软件通常按段落提交；该上限用于约束恶意/异常超长输入下的 CPU 与内存。
     */
    const val MAX_TEXT_CODE_POINTS = 50_000

    fun parse(
        text: String,
        profile: ReadingProfile,
        maxTextCodePoints: Int = MAX_TEXT_CODE_POINTS,
    ): ParsedReading = SpeechPlanBuilder.parse(text, profile, maxTextCodePoints)
}
