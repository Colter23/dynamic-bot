package top.colter.dynamic.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.draw.LinkPreviewRenderer

class LinkPreviewTemplateRendererTest {
    private val preview = LinkPreview(
        platformId = PlatformId.of("weibo"), kind = LinkKinds.VIDEO, id = "123",
        url = "https://example.com/123", title = "测试视频",
    )

    @Test
    fun `绘图失败保留文本且同次渲染只尝试一次`() = runBlocking {
        var draws = 0
        val renderer = LinkPreviewTemplateRenderer(LinkPreviewRenderer {
            draws++
            error("图片不可用")
        })

        val result = renderer.renderPreviewResult("{draw}\\r{title}\\r{draw}\\r{link}", preview)

        assertEquals(listOf("测试视频", "https://example.com/123"), result.batches.map { it.content.single().fallbackText })
        assertTrue(result.diagnostics.drawFailed)
        assertEquals(setOf("draw"), result.diagnostics.missingPlaceholders)
        assertEquals(1, draws)
    }

    @Test
    fun `绘图取消直接传递不能降级为成功`(): Unit = runBlocking {
        val renderer = LinkPreviewTemplateRenderer(LinkPreviewRenderer { throw CancellationException("cancelled") })

        assertFailsWith<CancellationException> { renderer.renderPreviewResult("{draw}{title}", preview) }
    }
}
