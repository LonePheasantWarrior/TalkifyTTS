package com.github.lonepheasantwarrior.talkify.domain.model.multirole

/** 预设符号对目录。`label` 仅用于设置界面展示，不参与匹配。 */
object MultiRoleDelimiterPresets {
    val ALL: List<DelimiterPreset> = listOf(
        DelimiterPreset(0x201C, 0x201D, "“ ”"),
        DelimiterPreset(0x300C, 0x300D, "「 」"),
        DelimiterPreset(0x0022, 0x0022, "\" \""),
        DelimiterPreset(0x300E, 0x300F, "『 』"),
        DelimiterPreset(0x3010, 0x3011, "【 】"),
        DelimiterPreset(0xFF08, 0xFF09, "（ ）"),
        DelimiterPreset(0x300A, 0x300B, "《 》"),
        DelimiterPreset(0x3014, 0x3015, "〔 〕"),
    )

    fun matches(pair: DelimiterPair): DelimiterPreset? =
        ALL.firstOrNull { it.open == pair.open && it.close == pair.close }
}
