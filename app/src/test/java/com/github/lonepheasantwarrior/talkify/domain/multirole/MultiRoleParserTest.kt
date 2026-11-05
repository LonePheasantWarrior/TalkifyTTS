package com.github.lonepheasantwarrior.talkify.domain.multirole

import com.github.lonepheasantwarrior.talkify.domain.model.multirole.DelimiterPair
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ProfileId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.Role
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.RoleId
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.VoiceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRoleParserTest {

    private fun profile(
        vararg roles: Pair<String, List<String>>,
        delimiters: List<DelimiterPair> = MultiRoleSettings.defaultDelimiterPairs(),
    ): ReadingProfile = ReadingProfile(
        id = ProfileId("p_test"),
        name = "测试方案",
        providerId = "xiaomi",
        narratorVoice = VoiceRef("narrator"),
        defaultRoleVoice = VoiceRef("default"),
        roles = roles.map { (name, aliases) ->
            Role(
                id = RoleId("r_$name"),
                name = name,
                aliases = aliases,
                voice = VoiceRef("voice_$name"),
            )
        },
        delimiters = delimiters,
    )

    private fun speechRoles(text: String, profile: ReadingProfile): List<String> =
        MultiRoleParser.parse(text, profile).plan.analysis
            .filter { it.kind == SegmentKind.SPEECH }
            .map { it.roleName ?: "默认角色" }

    private fun assertPlanInvariants(text: String, plan: SpeechPlan) {
        var cursor = 0
        for (segment in plan.segments) {
            assertEquals(cursor, segment.start)
            assertTrue(segment.endExclusive > segment.start)
            cursor = segment.endExclusive
            // 区间必须位于合法 UTF-16 边界，不能切断代理对
            if (segment.start > 0) {
                assertFalse(Character.isLowSurrogate(text[segment.start]))
            }
            if (segment.endExclusive < text.length) {
                assertFalse(Character.isLowSurrogate(text[segment.endExclusive]))
            }
        }
        assertEquals(text.length, cursor)
        assertEquals(text, plan.segments.joinToString("") {
            text.substring(it.start, it.endExclusive)
        })
    }

    @Test
    fun `left and right labels`() {
        val p = profile("张三" to emptyList())
        assertEquals(listOf("张三"), speechRoles("张三说：“你好。”", p))
        assertEquals(listOf("张三"), speechRoles("“你好。”张三说。", p))
    }

    @Test
    fun `double addressee uses first role`() {
        val p = profile("张三" to emptyList(), "李四" to emptyList())
        assertEquals(listOf("张三"), speechRoles("张三对李四说：“走。”", p))
    }

    @Test
    fun `unknown subject falls back to default`() {
        val p = profile("张三" to emptyList())
        assertEquals(listOf("默认角色"), speechRoles("他对张三说：“走。”", p))
    }

    @Test
    fun `adjacent speeches attributed separately`() {
        val p = profile("张三" to emptyList(), "李四" to emptyList())
        assertEquals(
            listOf("张三", "李四"),
            speechRoles("张三说：“走吧。”李四答道：“好。”", p),
        )
    }

    @Test
    fun `strong boundary separates evidence`() {
        val p = profile("张三" to emptyList(), "李四" to emptyList())
        assertEquals(
            listOf("张三", "李四"),
            speechRoles("“走。”张三说。李四说：“好。”", p),
        )
    }

    @Test
    fun `interrupted speech keeps speaker on both`() {
        val p = profile("李四" to emptyList())
        assertEquals(
            listOf("李四", "李四"),
            speechRoles("“我不走。”李四说，“我要留下。”", p),
        )
    }

    @Test
    fun `conflicting sides fall back to default`() {
        val p = profile("张三" to emptyList(), "李四" to emptyList())
        assertEquals(listOf("默认角色"), speechRoles("张三说：“走。”李四说。", p))
    }

    @Test
    fun `no clue falls back to default`() {
        val p = profile("张三" to emptyList(), "李四" to emptyList())
        assertEquals(listOf("默认角色"), speechRoles("“听我的。”", p))
        assertEquals(listOf("默认角色"), speechRoles("张三看了看李四：“走吧。”", p))
    }

    @Test
    fun `pronoun does not cross strong boundary`() {
        val p = profile("张三" to emptyList())
        assertEquals(listOf("默认角色"), speechRoles("张三走过来。他说：“走。”", p))
    }

    @Test
    fun `longest name wins`() {
        val p = profile("张三" to emptyList(), "张三丰" to emptyList())
        assertEquals(listOf("张三丰"), speechRoles("张三丰说：“走。”", p))
    }

    @Test
    fun `fake substring does not match`() {
        val p = profile("张三" to emptyList())
        assertEquals(listOf("默认角色"), speechRoles("老张三说：“走。”", p))
    }

    @Test
    fun `alias resolves to role`() {
        val p = profile("张三" to listOf("三哥"))
        assertEquals(listOf("张三"), speechRoles("三哥说：“走。”", p))
    }

    @Test
    fun `english forward and reverse`() {
        val p = profile("Alice" to emptyList())
        assertEquals(listOf("Alice"), speechRoles("Alice said, \"Go.\"", p))
        assertEquals(listOf("Alice"), speechRoles("\"Go,\" said Alice.", p))
        assertEquals(listOf("默认角色"), speechRoles("Malice said, \"Go.\"", p))
    }

    @Test
    fun `nested quotes only outermost speech`() {
        val p = profile("张三" to emptyList())
        val text = "「他说：『快走』。」"
        val reading = MultiRoleParser.parse(text, p)
        assertEquals(1, reading.speechCount)
        assertEquals(listOf("默认角色"), reading.plan.analysis.filter { it.kind == SegmentKind.SPEECH }
            .map { it.roleName ?: "默认角色" })
        assertPlanInvariants(text, reading.plan)
    }

    @Test
    fun `mixed punctuation emoji newline preserved`() {
        val p = profile("张三" to emptyList(), "李四" to emptyList())
        val text = "\n“🤔 走！”张三说。\n\n“……”\n李四说：“好。”\n"
        val reading = MultiRoleParser.parse(text, p)
        assertPlanInvariants(text, reading.plan)
        assertEquals(listOf("张三", "默认角色", "李四"), speechRoles(text, p))
    }

    @Test
    fun `unclosed outer quote makes tail narration`() {
        val p = profile("张三" to emptyList(), "李四" to emptyList())
        val text = "张三说：“走。”李四说：“留下。"
        val reading = MultiRoleParser.parse(text, p)
        assertPlanInvariants(text, reading.plan)
        assertTrue(reading.plan.warnings.unclosedQuote)
        // 第一段完整台词仍正常处理；尾部（含未闭合开符）整段旁白
        assertEquals(listOf("张三"), reading.plan.analysis.filter { it.kind == SegmentKind.SPEECH }
            .map { it.roleName ?: "默认角色" })
        assertTrue(reading.plan.segments.last().voiceId == "narrator")
    }

    @Test
    fun `extra closing quote is plain text`() {
        val p = profile("张三" to emptyList())
        val text = "多余”闭符。张三说：“走。”"
        val reading = MultiRoleParser.parse(text, p)
        assertPlanInvariants(text, reading.plan)
        assertEquals(listOf("张三"), speechRoles(text, p))
    }

    @Test
    fun `empty quote is preserved and attached`() {
        val p = profile("张三" to emptyList())
        val text = "“”张三说：“”"
        val reading = MultiRoleParser.parse(text, p)
        assertPlanInvariants(text, reading.plan)
        assertTrue(reading.plan.hasReadableContent)
        // 标点/引号区间必须附着而不是丢弃
        assertEquals(text, reading.plan.segments.joinToString("") {
            text.substring(it.start, it.endExclusive)
        })
    }

    @Test
    fun `numeric speech content is readable`() {
        val p = profile("张三" to emptyList())
        assertEquals(listOf("张三"), speechRoles("张三说：“123。”", p))
        val plan = MultiRoleParser.parse("张三说：“123。”", p).plan
        assertTrue(plan.hasReadableContent)
    }

    @Test
    fun `supplementary plane content is readable`() {
        val p = profile("张三" to emptyList())
        val text = "张三说：“𠀀𠀁。”" // U+20000 / U+20001
        val reading = MultiRoleParser.parse(text, p)
        assertTrue(reading.plan.hasReadableContent)
        assertPlanInvariants(text, reading.plan)
        assertEquals(listOf("张三"), speechRoles(text, p))
    }

    @Test
    fun `same voice adjacent segments merge but text preserved`() {
        val p = profile("张三" to listOf("三哥"))
        // 相邻两句同角色/同音色（中间无旁白文本）应合并为一次供应商调用
        val text = "“走。”“好。”"
        val analysis = listOf(
            AnalyzedSegment(0, 3, SegmentKind.SPEECH, RoleId("r_张三"), "张三", false, "voice_张三", AttributionReason.LEFT_LABEL),
            AnalyzedSegment(3, 6, SegmentKind.SPEECH, RoleId("r_张三"), "张三", false, "voice_张三", AttributionReason.LEFT_LABEL),
        )
        val plan = SpeechPlanBuilder.build(text, analysis, p)
        assertEquals(1, plan.segments.size)
        assertEquals(0, plan.segments.first().start)
        assertEquals(text.length, plan.segments.first().endExclusive)
        // 解析器不会跨旁白合并：旁白音色与角色音色不同时保持两段台词
        val parsed = MultiRoleParser.parse("张三说：“走。”三哥说：“好。”", p).plan
        assertEquals(2, parsed.segments.count { it.kind == SegmentKind.SPEECH })
    }

    @Test
    fun `symmetric english quotes do not self nest`() {
        val p = profile("Alice" to emptyList())
        val text = "\"Go,\" said Alice. \"Wait,\" said Alice."
        val reading = MultiRoleParser.parse(text, p)
        assertPlanInvariants(text, reading.plan)
        assertEquals(listOf("Alice", "Alice"), speechRoles(text, p))
    }

    @Test
    fun `deterministic regardless of role order`() {
        val a = profile("张三" to emptyList(), "李四" to emptyList())
        val b = profile("李四" to emptyList(), "张三" to emptyList())
        val text = "张三对李四说：“走。”"
        assertEquals(speechRoles(text, a), speechRoles(text, b))
    }

    @Test
    fun `over budget falls back to narration without truncation`() {
        val p = profile("张三" to emptyList())
        val text = "张三说：“走。”"
        val reading = MultiRoleParser.parse(text, p, maxTextCodePoints = 2)
        assertTrue(reading.plan.warnings.budgetExceeded)
        assertEquals(1, reading.plan.segments.size)
        assertEquals(text, reading.plan.segments.first().let { text.substring(it.start, it.endExclusive) })
    }

    @Test
    fun `preposed label just beyond 80 code point window is still found from quote side`() {
        val p = profile("张三" to emptyList())
        val text = "旁".repeat(120) + "，张三说：“好。”"
        assertEquals(listOf("张三"), speechRoles(text, p))
    }

    @Test
    fun `postposed label in long prefix remains usable`() {
        val p = profile("张三" to emptyList())
        val text = "“好。”张三说。" + "后续旁白".repeat(30)
        assertEquals(listOf("张三"), speechRoles(text, p))
    }

    @Test
    fun `too many outermost speech spans fall back to narration`() {
        val p = profile("张三" to emptyList())
        val text = (0..4100).joinToString("") { "“a”" }
        val reading = MultiRoleParser.parse(text, p)
        assertTrue(reading.plan.warnings.limitExceeded)
        assertEquals(1, reading.plan.segments.size)
        assertEquals(text.length, reading.plan.segments.first().endExclusive)
    }

    @Test
    fun `excessive nesting falls back to narration`() {
        val p = profile("张三" to emptyList())
        val text = "「".repeat(40) + "内容" + "」".repeat(40)
        val reading = MultiRoleParser.parse(text, p)
        assertTrue(reading.plan.warnings.limitExceeded)
        assertEquals(1, reading.plan.segments.size)
    }

    @Test
    fun `different evidence from both sides resolves to default`() {
        val p = profile("张三" to emptyList(), "李四" to emptyList())
        assertEquals(listOf("默认角色"), speechRoles("张三说：“走。”李四说。", p))
        // 标签后是逗号但没有后续台词：结构不完整，保守回退
        assertEquals(listOf("默认角色"), speechRoles("“走。”张三说，", p))
    }

    @Test
    fun `punctuation only text preserves full source and is not treated as audio`() {
        val p = profile("张三" to emptyList())
        val text = "“……”——“。”"
        val reading = MultiRoleParser.parse(text, p)
        assertFalse(reading.plan.hasReadableContent)
        assertPlanInvariants(text, reading.plan)
    }

    @Test
    fun `random text invariants`() {
        val p = profile("张三" to emptyList(), "李四" to emptyList(), "Alice" to listOf("Al"))
        val alphabet = "张三李四说：对向，。！？；\"“”「」『』【】\n abcAlcie😀𠀀".codePoints().toArray()
        val random = kotlin.random.Random(20261105)
        repeat(300) {
            val length = random.nextInt(0, 120)
            val sb = StringBuilder()
            repeat(length) {
                sb.appendCodePoint(alphabet[random.nextInt(alphabet.size)])
            }
            val text = sb.toString()
            val reading = MultiRoleParser.parse(text, p)
            assertPlanInvariants(text, reading.plan)
            assertTrue(reading.plan.analysis.all { it.start in 0..text.length && it.endExclusive <= text.length })
        }
    }
}
