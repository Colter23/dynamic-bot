package top.colter.dynamic.listener

import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicStats
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicTemplateRendererTest {
    private val renderer = DynamicTemplateRenderer()

    @Test
    fun shouldRenderKnownPlaceholdersAndKeepUnknownPlaceholders() {
        val rendered = renderer.render(
            "{publisher.name}\n{dynamic.title}\n{dynamic.text}\n{stats.like}\n{unknown.value}",
            demoDynamic(),
        )

        assertEquals(
            "Demo UP\nDemo Title\nDemo content\n10\n{unknown.value}",
            rendered,
        )
    }

    @Test
    fun shouldRenderMissingValuesAsBlank() {
        val rendered = renderer.render(
            "title={dynamic.title}, notice={dynamic.notice}, text={dynamic.text}",
            demoDynamic().copy(title = null, notice = null, content = null),
        )

        assertEquals("title=, notice=, text=", rendered)
    }

    private fun demoDynamic(): Dynamic {
        return Dynamic(
            platform = PublisherPlatform("bilibili", "BiliBili", "https://www.bilibili.com", ""),
            dynamicId = "dynamic-1",
            publisher = Publisher(
                id = 1,
                platform = "bilibili",
                userId = "123",
                name = "Demo UP",
                state = 1,
                face = LazyImage("https://example.com/face.png"),
                createTime = 1,
                createUser = 1,
            ),
            time = 1_710_000_000,
            link = "https://t.bilibili.com/dynamic-1",
            title = "Demo Title",
            content = DynamicContent("Demo content", listOf(DynamicContentNodeText("Demo content"))),
            stats = DynamicStats(like = "10", comment = "2", forward = "1"),
        )
    }
}
