package top.colter.dynamic.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.colter.dynamic.PlatformTemplates
import top.colter.dynamic.PushTemplates
import top.colter.dynamic.TemplateOverrides
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.event.SourceUpdateDeliveryMode

class MessageTemplateEngineTest {
    @Test
    fun `模板按用途再按平台逐层继承`() {
        val templates = PushTemplates(dynamic = "全局基础", linkParsing = TemplateOverrides(dynamic = "全局解析"),
            platforms = listOf(PlatformTemplates("weibo", base = TemplateOverrides(dynamic = "平台基础"),
                linkParsing = TemplateOverrides(dynamic = "平台解析"))))
        fun select(value: PushTemplates, purpose: SourceUpdateDeliveryMode = SourceUpdateDeliveryMode.LINK_PREVIEW) =
            TemplateSelector.select(value, "WEIBO", TemplateContentKind.DYNAMIC, purpose).template
        assertEquals("平台解析", select(templates))
        val withoutPlatformPurpose = templates.copy(platforms = templates.platforms.map { it.copy(linkParsing = TemplateOverrides()) })
        assertEquals("全局解析", select(withoutPlatformPurpose))
        assertEquals("平台基础", select(withoutPlatformPurpose.copy(linkParsing = TemplateOverrides())))
        assertEquals("全局基础", select(templates.copy(platforms = emptyList(), linkParsing = TemplateOverrides())))
        assertEquals("平台基础", select(templates, SourceUpdateDeliveryMode.SUBSCRIPTION))
    }

    @Test
    fun `视频依赖是顺序边界且大小本身不触发下载`() {
        val plan = MessageTemplateEngine.compile("前言\\r大小：{size}\\r{video}\\r结尾")
        assertEquals(1, plan.boundary)
        assertEquals(listOf("大小：{size}", "{video}", "结尾"), plan.steps.drop(plan.boundary).map { it.value })
        assertFalse(MessageTemplateEngine.compile("{size}").requestsVideo)
        val forward = MessageTemplateEngine.compile("前言{>>}{content}\\r{video}{<<}结尾")
        assertEquals(1, forward.boundary)
        assertTrue(forward.steps[1].forward)
    }

    @Test
    fun `缺失资源清理标签但保留未知字段和有意义的文字`() {
        val missing = linkedSetOf<String>()
        val unknown = linkedSetOf<String>()
        val result = MessageTemplateEngine.render(MessageTemplateEngine.compile("标题：{title} 大小：{size}\\n{video}\\n{unknown}").steps,
            TemplateSender("1", "测试"), resolve = { key, _ ->
                if (key == "title") listOf(MessageContent.Text("内容")) else null
            }, missing = { missing += it }, unknown = { unknown += it })
        val text = result.single().content.joinToString("") { it.fallbackText }
        assertTrue("标题：内容" in text)
        assertFalse("大小" in text)
        assertTrue("{unknown}" in text)
        assertEquals(setOf("unknown"), unknown)
        assertEquals(setOf("size", "video"), missing)
    }

    @Test
    fun `手填合并转发全体占位符也不能生成全体通知`() {
        val batches = MessageTemplateEngine.render(MessageTemplateEngine.compile("{>>}{atAll}\\r正文{<<}").steps,
            TemplateSender("1", "测试"), resolve = { _, _ -> listOf(MessageContent.MentionAll("")) })
        val forward = batches.single().content.single() as MessageContent.Forward
        assertEquals(1, forward.nodes.size)
        assertTrue(forward.nodes.single().batches.single().content.none { it is MessageContent.MentionAll })
    }

    @Test
    fun `清理缺失字段不会修改正文中的表情标点和换行`() {
        val bodies = listOf("😀😀", "请看这里：", "literal content:", "https://example.com/a:", " / 正文； ", "第一行：\n\n😀😀")
        for (body in bodies) {
            for (template in listOf("{content}{size}", "{content} 大小：{size}", "{size}{content}")) {
                val batches = MessageTemplateEngine.render(MessageTemplateEngine.compile(template).steps,
                    TemplateSender("1", "测试"), resolve = { key, _ ->
                        if (key == "content") listOf(MessageContent.Text(body)) else null
                    })
                assertEquals(body, batches.single().content.single().fallbackText, "模板=$template，正文=$body")
            }
        }
    }

    @Test
    fun `缺失标签清理保留媒体顺序且不会再次解析字段值`() {
        val body = "😀：{video}\u0000TEMPLATE_MISSING\u0000"
        val image = MessageContent.Image("", top.colter.dynamic.core.data.MediaRef("image.png"))
        val batches = MessageTemplateEngine.render(
            MessageTemplateEngine.compile("{>>}{content} 大小：{size}\\r{images}封面：{cover}{<<}").steps,
            TemplateSender("1", "测试"), resolve = { key, _ -> when (key) {
                "content" -> listOf(MessageContent.Text(body))
                "images" -> listOf(image)
                else -> null
            } },
        )
        val nodes = (batches.single().content.single() as MessageContent.Forward).nodes
        assertEquals(body, nodes[0].batches.single().content.single().fallbackText)
        assertEquals(listOf(image), nodes[1].batches.single().content)
    }

    @Test
    fun `拒绝嵌套与不完整合并转发语法`() {
        listOf("{>>}{>>}x{<<}{<<}", "{<<}", "{>>}").forEach { input ->
            assertFailsWith<IllegalArgumentException> { MessageTemplateEngine.compile(input) }
        }
    }
}
