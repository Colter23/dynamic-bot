package top.colter.dynamic.draw.layout.default

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.DynamicBlockRole
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.TextBlock

class DefaultDynamicLayoutTest {
    @Test
    fun `root layout should lift additional blocks outside body card`() {
        val body = TextBlock(DynamicContent.text("正文"))
        val additional = additionalCard()

        val groups = splitDynamicBlocksForLayout(listOf(body, additional), DynamicRenderMode.ROOT)

        assertEquals(listOf(body), groups.bodyBlocks)
        assertEquals(listOf(additional), groups.additionalBlocks)
    }

    @Test
    fun `forward layout should keep additional blocks inside forwarded card`() {
        val body = TextBlock(DynamicContent.text("正文"))
        val additional = additionalCard()

        val groups = splitDynamicBlocksForLayout(listOf(body, additional), DynamicRenderMode.FORWARD)

        assertEquals(listOf(body, additional), groups.bodyBlocks)
        assertTrue(groups.additionalBlocks.isEmpty())
    }

    private fun additionalCard(): MediaCardBlock {
        return MediaCardBlock(
            role = DynamicBlockRole.ADDITIONAL,
            style = MediaCardStyle.SMALL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LINK,
                sourceKind = "bilibili.additional.common",
                title = "附加卡片",
            ),
        )
    }
}
