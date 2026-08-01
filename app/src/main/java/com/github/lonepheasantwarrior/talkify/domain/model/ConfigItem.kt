package com.github.lonepheasantwarrior.talkify.domain.model

data class ConfigItem(
    val key: String,
    val label: String,
    val value: String,
    val isPassword: Boolean = false,
    val isVoiceSelector: Boolean = false,
    val dropdownOptions: List<Pair<String, String>>? = null,
    val placeholder: String? = null,
    /**
     * 辅助说明文字，显示在弹窗编辑器的输入框上方，
     * 用于补充说明输入内容的要求（如风格指令需为一句话描述）。
     */
    val supportingText: String? = null,
    /**
     * 是否使用弹窗式多行文本编辑器。
     * 适用于需要输入大段自然语言描述的配置项（如风格指令），
     * 点击字段后弹出 Material 3 对话框进行完整编辑。
     */
    val isDialogEditor: Boolean = false,
    /**
     * 弹窗编辑器的标题文字。
     * 为空时回退使用 [label]。
     */
    val editorTitle: String? = null,
    /**
     * 指南帮助内容（Markdown 格式）。
     * 当配置项为弹窗式编辑器时，会在编辑弹窗标题旁显示问号按钮，
     * 点击弹出包含此内容的使用指南对话框。为空则不显示问号按钮。
     */
    val guideContent: String? = null
)
