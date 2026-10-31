package com.github.lonepheasantwarrior.talkify.service.role

import com.github.lonepheasantwarrior.talkify.domain.model.DialogueModeConfig
import com.github.lonepheasantwarrior.talkify.domain.model.RoleRule
import com.github.lonepheasantwarrior.talkify.domain.model.VoiceBinding
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DialogueSegmentResolver] 单元测试：优先级
 * 显式 `<<人物>>` 标记 > 角色规则 > 默认对话 > 默认旁白
 */
class DialogueSegmentResolverTest {

    private val narrator = VoiceBinding(providerId = "xiaomi", voiceId = "白桦", displayName = "白桦")
    private val dialogue = VoiceBinding(providerId = "xiaomi", voiceId = "冰糖", displayName = "冰糖")
    private val zhang = VoiceBinding(providerId = "azure", voiceId = "zh-CN-YunxiNeural", displayName = "云希")

    private fun config(
        rules: List<RoleRule> = emptyList(),
        narrator: VoiceBinding = this.narrator,
        dialogue: VoiceBinding = this.dialogue
    ) = DialogueModeConfig(enabled = true, narrator = narrator, dialogue = dialogue, rules = rules)

    @Test
    fun `旁白段使用默认旁白绑定`() {
        val segments = listOf(DialogueSegment(SegmentKind.NARRATOR, "夜色渐深。"))
        val resolved = DialogueSegmentResolver.resolve(segments, config())

        assertEquals(narrator, resolved[0].binding)
    }

    @Test
    fun `对话段未命中规则时使用默认对话绑定`() {
        val segments = listOf(
            DialogueSegment(SegmentKind.DIALOGUE, "你好。", speaker = null, contextBefore = "某人：")
        )
        val resolved = DialogueSegmentResolver.resolve(segments, config(rules = emptyList()))

        assertEquals(dialogue, resolved[0].binding)
    }

    @Test
    fun `显式说话人标记命中同名规则`() {
        val rules = listOf(RoleRule(id = 1, name = "张三", pattern = "张三", binding = zhang))
        val segments = listOf(
            DialogueSegment(SegmentKind.DIALOGUE, "我走了。", speaker = "张三", contextBefore = "")
        )
        val resolved = DialogueSegmentResolver.resolve(segments, config(rules = rules))

        assertEquals(zhang, resolved[0].binding)
    }

    @Test
    fun `无标记时规则匹配对话前文（人名+说）`() {
        val rules = listOf(RoleRule(id = 1, name = "张三", pattern = "张三", binding = zhang))
        val segments = listOf(
            DialogueSegment(SegmentKind.DIALOGUE, "我走了。", speaker = null, contextBefore = "张三说：")
        )
        val resolved = DialogueSegmentResolver.resolve(segments, config(rules = rules))

        assertEquals(zhang, resolved[0].binding)
    }

    @Test
    fun `正则规则匹配对话前文`() {
        val rules = listOf(
            RoleRule(id = 1, name = "张家", pattern = "(张三|老张)", isRegex = true, binding = zhang)
        )
        val segments = listOf(
            DialogueSegment(SegmentKind.DIALOGUE, "嗯。", speaker = null, contextBefore = "老张点头：")
        )
        val resolved = DialogueSegmentResolver.resolve(segments, config(rules = rules))

        assertEquals(zhang, resolved[0].binding)
    }

    @Test
    fun `显式标记优先于前文规则`() {
        // 前文写着「李四说」，但显式标记是张三 → 张三的规则生效
        val zhangRule = RoleRule(id = 1, name = "张三", pattern = "张三", binding = zhang)
        val liRule = RoleRule(id = 2, name = "李四", pattern = "李四", binding = VoiceBinding("openAI", "onyx", "Onyx"))
        val segments = listOf(
            DialogueSegment(SegmentKind.DIALOGUE, "我走了。", speaker = "张三", contextBefore = "李四说：")
        )
        val resolved = DialogueSegmentResolver.resolve(segments, config(rules = listOf(zhangRule, liRule)))

        assertEquals(zhang, resolved[0].binding)
    }

    @Test
    fun `规则匹配失败回退默认对话`() {
        val rules = listOf(RoleRule(id = 1, name = "张三", pattern = "张三", binding = zhang))
        val segments = listOf(
            DialogueSegment(SegmentKind.DIALOGUE, "你好。", speaker = "王五", contextBefore = "王五说：")
        )
        val resolved = DialogueSegmentResolver.resolve(segments, config(rules = rules))

        assertEquals(dialogue, resolved[0].binding)
    }

    @Test
    fun `未配置完整的规则被跳过`() {
        val badRule = RoleRule(id = 1, name = "空绑定", pattern = "张三", binding = VoiceBinding())
        val segments = listOf(
            DialogueSegment(SegmentKind.DIALOGUE, "我走了。", speaker = "张三", contextBefore = "")
        )
        val resolved = DialogueSegmentResolver.resolve(segments, config(rules = listOf(badRule)))

        assertEquals(dialogue, resolved[0].binding)
    }

    @Test
    fun `非法正则不会抛异常并回退默认对话`() {
        val badRegexRule = RoleRule(id = 1, name = "坏正则", pattern = "([", isRegex = true, binding = zhang)
        val segments = listOf(
            DialogueSegment(SegmentKind.DIALOGUE, "嗯。", speaker = null, contextBefore = "张三说：")
        )
        val resolved = DialogueSegmentResolver.resolve(segments, config(rules = listOf(badRegexRule)))

        assertEquals(dialogue, resolved[0].binding)
    }

    @Test
    fun `混合段落按序解析`() {
        val rules = listOf(RoleRule(id = 1, name = "张三", pattern = "张三", binding = zhang))
        val segments = listOf(
            DialogueSegment(SegmentKind.NARRATOR, "张三推门。"),
            DialogueSegment(SegmentKind.DIALOGUE, "我回来了。", speaker = null, contextBefore = "张三推门。"),
            DialogueSegment(SegmentKind.NARRATOR, "屋里很安静。"),
            DialogueSegment(SegmentKind.DIALOGUE, "……", speaker = null, contextBefore = "")
        )
        val resolved = DialogueSegmentResolver.resolve(segments, config(rules = rules))

        assertEquals(narrator, resolved[0].binding)
        assertEquals(zhang, resolved[1].binding)   // 前文含「张三」→ 规则命中
        assertEquals(narrator, resolved[2].binding)
        assertEquals(dialogue, resolved[3].binding) // 无前文 → 默认对话
    }
}
