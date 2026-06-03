package top.colter.dynamic.draw.layout.default

import kotlin.test.Test
import kotlin.test.assertEquals
import top.colter.dynamic.core.data.DynamicBlockRole
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.TextBlock

class DefaultDynamicLayoutTest {
    @Test
    fun `layout should keep additional blocks inside body card and place them at bottom`() {
        val body = TextBlock(DynamicContent.text("body"))
        val additional = additionalCard()

        val blocks = orderDynamicBlocksForLayout(listOf(additional, body))

        assertEquals(listOf(body, additional), blocks)
    }

    @Test
    fun `layout should preserve relative order inside body and additional groups`() {
        val body1 = TextBlock(DynamicContent.text("body1"))
        val body2 = TextBlock(DynamicContent.text("body2"))
        val additional1 = additionalCard("additional1")
        val additional2 = additionalCard("additional2")

        val blocks = orderDynamicBlocksForLayout(listOf(additional1, body1, additional2, body2))

        assertEquals(listOf(body1, body2, additional1, additional2), blocks)
    }

    private fun additionalCard(title: String = "additional"): MediaCardBlock {
        return MediaCardBlock(
            role = DynamicBlockRole.ADDITIONAL,
            style = MediaCardStyle.SMALL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LINK,
                sourceKind = "bilibili.additional.common",
                title = title,
            ),
        )
    }
}
