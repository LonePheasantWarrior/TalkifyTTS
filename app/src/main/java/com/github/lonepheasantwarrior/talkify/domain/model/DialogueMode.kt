package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 对话模式（Dialogue Mode）领域模型
 *
 * 目标：在端侧完成「旁白 / 对话」切分与发音人分配（类比 MultiTTS 的角色朗读），
 * 不依赖大模型自己识别角色。
 *
 * - [VoiceBinding]：一个「发音人绑定」= 供应商 + 音色 + 可选风格指令。
 *   旁白、对话、每条角色规则各自持有一个绑定，可指向不同供应商。
 * - [RoleRule]：简单角色规则（人物名 / 正则 → 指定发音人）。
 * - [DialogueModeConfig]：对话模式总配置，经 DataStore 持久化。
 */

/**
 * 发音人绑定
 *
 * @param providerId 供应商 ID（ProviderIds.providerId），如 "xiaomi"、"azure"
 * @param voiceId 音色 ID（供应商内部标识），如 "白桦"、"zh-CN-XiaoxiaoNeural"
 * @param displayName 展示名（冗余存储，避免每次反查供应商音色表）
 * @param styleInstruction 可选风格指令；仅对支持风格指令的供应商生效
 *                         （小米 MiMo / OpenAI / Google Gemini），其余供应商忽略
 */
data class VoiceBinding(
    val providerId: String = "",
    val voiceId: String = "",
    val displayName: String = "",
    val styleInstruction: String = ""
) {
    /** 是否已配置到可用状态（供应商与音色都非空） */
    val isConfigured: Boolean
        get() = providerId.isNotBlank() && voiceId.isNotBlank()
}

/**
 * 简单角色规则
 *
 * 匹配语义（与 MultiTTS 的角色规则对齐，但做了简化）：
 * - 非正则规则：显式说话人名等于 [pattern]，或对话前的旁白文本包含 [pattern]
 *   （覆盖「张三说：“……”」这类「人名 + 说/道/问」前缀写法）
 * - 正则规则：[pattern] 作为正则，对说话人名或对话前旁白文本做 containsMatchIn
 *
 * @param id 规则唯一 ID（持久化用，System.currentTimeMillis()）
 * @param name 规则展示名，如「张三 → 低沉男声」
 * @param pattern 人物名或正则表达式
 * @param isRegex 是否按正则匹配
 * @param binding 命中后使用的发音人绑定
 */
data class RoleRule(
    val id: Long,
    val name: String,
    val pattern: String,
    val isRegex: Boolean = false,
    val binding: VoiceBinding = VoiceBinding()
) {
    /** 规则是否可用（pattern 非空且绑定已配置） */
    val isUsable: Boolean
        get() = pattern.isNotBlank() && binding.isConfigured
}

/**
 * 对话模式总配置
 *
 * @param enabled 总开关；关闭时走原有「单一发音人」路径
 * @param narrator 旁白发音人（引号外文本）
 * @param dialogue 对话发音人（引号内文本、未被规则命中时）
 * @param readQuotes 是否朗读引号本身（默认不朗读，直接去除引号字符）
 * @param pauseMs 段与段之间的静音停顿（毫秒），0 表示不停顿
 * @param rules 角色规则列表
 */
data class DialogueModeConfig(
    val enabled: Boolean = false,
    val narrator: VoiceBinding = VoiceBinding(),
    val dialogue: VoiceBinding = VoiceBinding(),
    val readQuotes: Boolean = false,
    val pauseMs: Int = DEFAULT_PAUSE_MS,
    val rules: List<RoleRule> = emptyList()
) {
    companion object {
        const val DEFAULT_PAUSE_MS = 120
        const val MAX_PAUSE_MS = 600
    }

    /** 对话模式是否具备可用的最小配置（旁白与对话都已绑定） */
    val isReady: Boolean
        get() = narrator.isConfigured && dialogue.isConfigured
}
