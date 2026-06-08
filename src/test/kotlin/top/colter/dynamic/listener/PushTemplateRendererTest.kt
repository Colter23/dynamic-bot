package top.colter.dynamic.listener

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisherInfo

class PushTemplateRendererTest {
    private val renderer = PushTemplateRenderer()

    @Test
    fun shouldRenderTextPlaceholdersAndKeepUnknownPlaceholders() {
        val chains = renderer.render(
            "{name} {uid} {did} {time} {content} {link} {unknown}",
            demoDynamic(),
            drawImage = null,
        )

        val text = chains.single().content.single().fallbackText
        assertTrue(text.contains("Demo UP 123 dynamic-1"))
        assertTrue(text.contains("Demo content"))
        assertTrue(text.contains("https://t.bilibili.com/dynamic-1"))
        assertTrue(text.contains("{unknown}"))
    }

    @Test
    fun shouldRenderEpochSecondTime() {
        val text = renderer.render("{time}", demoDynamic(), drawImage = null)
            .single()
            .content
            .single()
            .fallbackText

        assertTrue(text.startsWith("2024"))
        assertFalse(text.startsWith("+"))
    }

    @Test
    fun shouldInsertDrawAndImagesAtTemplatePosition() {
        val chains = renderer.render(
            "before {draw} middle {images} after",
            demoDynamic(),
            drawImage = MediaRef("D:/tmp/draw.png", MediaKind.IMAGE),
        )

        val contents = chains.single().content
        assertEquals("before ", contents[0].fallbackText)
        assertEquals("D:/tmp/draw.png", (contents[1] as MessageContent.Image).image.uri)
        assertEquals(" middle ", contents[2].fallbackText)
        assertEquals("https://example.com/pic-a.png", (contents[3] as MessageContent.Image).image.uri)
        assertEquals("https://example.com/pic-b.png", (contents[4] as MessageContent.Image).image.uri)
        assertEquals(" after", contents[5].fallbackText)
    }

    @Test
    fun shouldDropDrawPlaceholderWhenDrawFails() {
        val chains = renderer.render(
            "{draw}\n{name}",
            demoDynamic(),
            drawImage = null,
        )

        assertEquals("Demo UP", chains.single().content.single().fallbackText)
    }

    @Test
    fun shouldRenderAdditionalLinksDistinctFromMainLink() {
        val chains = renderer.render("{links}", demoDynamic(), drawImage = null)

        assertEquals(
            listOf(
                "https://example.com/content-link",
                "https://www.bilibili.com/video/BV1",
                "https://example.com/card",
            ).joinToString("\n"),
            chains.single().content.single().fallbackText,
        )
    }

    @Test
    fun shouldSplitLiteralLineBreakAndConversationSeparator() {
        val chains = renderer.render(
            "hello\\n{name}\\r{link}",
            demoDynamic(),
            drawImage = null,
        )

        assertEquals(2, chains.size)
        assertEquals("hello\nDemo UP", chains[0].content.single().fallbackText)
        assertEquals("https://t.bilibili.com/dynamic-1", chains[1].content.single().fallbackText)
    }

    @Test
    fun shouldTrimBoundaryLineBreaksFromEveryMessage() {
        val chains = renderer.render(
            "\r\n{name}\r\n\\r\r\n{link}\r\n",
            demoDynamic(),
            drawImage = null,
        )

        assertEquals(2, chains.size)
        assertEquals("Demo UP", chains[0].content.single().fallbackText)
        assertEquals("https://t.bilibili.com/dynamic-1", chains[1].content.single().fallbackText)
    }

    @Test
    fun shouldRenderMergedForwardBlockWithNodes() {
        val chains = renderer.render(
            "head {name}{>>}完整文字：\\n{content}\\r全部原图：\\n{images}{<<}tail",
            demoDynamic(),
            drawImage = null,
        )

        assertEquals(3, chains.size)
        assertEquals("head Demo UP", chains[0].content.single().fallbackText)
        val forward = assertIs<MessageContent.Forward>(chains[1].content.single())
        assertEquals("Demo UP 的原始内容", forward.title)
        assertEquals("Demo UP", forward.sourceName)
        assertEquals("123", forward.nodes[0].senderId)
        assertEquals(1_710_000_000, forward.nodes[0].time)
        assertEquals("完整文字：\nDemo contentlink", forward.nodes[0].batches.single().content.single().fallbackText)
        assertEquals("全部原图：\n", forward.nodes[1].batches.single().content[0].fallbackText)
        assertEquals("https://example.com/pic-a.png", (forward.nodes[1].batches.single().content[1] as MessageContent.Image).image.uri)
        assertEquals("tail", chains[2].content.single().fallbackText)
    }

    @Test
    fun shouldTrimTrailingLineBreakBeforeAdjacentMergedForwardBlock() {
        val chains = renderer.render(
            "{draw}\n{name} 发布了新动态\n{link}\n\n{>>}{name}@{uid}\\n{time}\\n\\n{content}\\r{images}{<<}",
            demoDynamic(),
            drawImage = MediaRef("D:/tmp/draw.png", MediaKind.IMAGE),
        )

        assertEquals(2, chains.size)
        val firstContents = chains[0].content
        assertEquals("D:/tmp/draw.png", (firstContents[0] as MessageContent.Image).image.uri)
        val text = firstContents[1].fallbackText
        assertEquals("\nDemo UP 发布了新动态\nhttps://t.bilibili.com/dynamic-1", text)
        assertFalse(text.endsWith("\n"))
        assertIs<MessageContent.Forward>(chains[1].content.single())
    }

    @Test
    fun shouldNormalizeWrappedEscapeMarkers() {
        val chains = renderer.render(
            "{>>}line\\\\" + "\n    nnext\\\\" + "\n    rlast{<<}",
            demoDynamic(),
            drawImage = null,
        )

        val forward = assertIs<MessageContent.Forward>(chains.single().content.single())
        assertEquals(
            listOf("line\nnext", "last"),
            forward.nodes.map { it.batches.single().content.single().fallbackText },
        )
    }

    @Test
    fun shouldTrimBoundaryLineBreaksFromMergedForwardNodes() {
        val chains = renderer.render(
            "{>>}\r\n{content}\r\n\\r\r\n{images}\r\n{<<}",
            demoDynamic(),
            drawImage = null,
        )

        val forward = assertIs<MessageContent.Forward>(chains.single().content.single())
        assertEquals("Demo contentlink", forward.nodes[0].batches.single().content.single().fallbackText)
        val imageContents = forward.nodes[1].batches.single().content
        assertEquals(2, imageContents.size)
        assertEquals("https://example.com/pic-a.png", (imageContents[0] as MessageContent.Image).image.uri)
        assertEquals("https://example.com/pic-b.png", (imageContents[1] as MessageContent.Image).image.uri)
    }

    @Test
    fun shouldSplitConversationOutsideMergedForwardBlockOnly() {
        val chains = renderer.render(
            "first\\r{>>}node-a\\rnode-b{<<}\\rlast",
            demoDynamic(),
            drawImage = null,
        )

        assertEquals(3, chains.size)
        assertEquals("first", chains[0].content.single().fallbackText)
        val forward = assertIs<MessageContent.Forward>(chains[1].content.single())
        assertEquals(listOf("node-a", "node-b"), forward.nodes.map { it.batches.single().content.single().fallbackText })
        assertEquals("last", chains[2].content.single().fallbackText)
    }

    @Test
    fun shouldRejectInvalidMergedForwardBlocks() {
        assertFailsWith<IllegalArgumentException> {
            PushTemplateRenderer.validateForwardBlockSyntax("{>>}missing end")
        }
        assertFailsWith<IllegalArgumentException> {
            PushTemplateRenderer.validateForwardBlockSyntax("{<<}")
        }
        assertFailsWith<IllegalArgumentException> {
            PushTemplateRenderer.validateForwardBlockSyntax("{>>}outer {>>} inner {<<}{<<}")
        }
    }

    @Test
    fun shouldDetectDrawPlaceholder() {
        val dynamic = demoDynamic()
        assertTrue(renderer.requiresDraw("{draw}\n{name}", dynamic))
        assertFalse(renderer.requiresDraw("{images}\n{name}", dynamic))
    }

    @Test
    fun shouldNotSplitLiveEndedOnConversationSeparator() {
        val chains = renderer.render("{name}\\r{duration}", demoLiveEnded(), drawImage = null)

        assertEquals(1, chains.size)
        assertTrue(chains.single().content.single().fallbackText.contains("\\r"))
    }

    private fun demoDynamic(): SourceUpdate {
        return testDynamicUpdate(
            publisher = testPublisherInfo(name = "Demo UP"),
            externalId = "dynamic-1",
            payload = DynamicPayload(
                title = "Demo Title",
                blocks = listOf(
                    TextBlock(
                        DynamicContent(
                            listOf(
                                DynamicContentNodeText("Demo content"),
                                DynamicContentNodeLink("link", url = "https://example.com/content-link"),
                            ),
                        ),
                    ),
                    ImageGridBlock(
                        images = listOf(
                            ImageItem(testMedia("https://example.com/pic-a.png", MediaKind.IMAGE), width = 100, height = 100),
                            ImageItem(testMedia("https://example.com/pic-b.png", MediaKind.IMAGE), width = 100, height = 100),
                        ),
                    ),
                    MediaCardBlock(
                        style = MediaCardStyle.LARGE,
                        card = DynamicMediaCard(
                            kind = DynamicMediaCardKind.VIDEO,
                            id = "BV1",
                            title = "video",
                            description = "desc",
                            cover = testMedia("https://example.com/cover.png", MediaKind.COVER),
                            durationSeconds = 60,
                            badge = "video",
                            metrics = listOf(
                                DynamicMetric(key = "play", display = "1"),
                                DynamicMetric(key = "danmaku", display = "2"),
                                DynamicMetric(key = "like", display = "3"),
                            ),
                            link = "https://www.bilibili.com/video/BV1",
                        ),
                    ),
                    MediaCardBlock(
                        style = MediaCardStyle.LARGE,
                        card = DynamicMediaCard(
                            kind = DynamicMediaCardKind.ARTICLE,
                            sourceKind = "article",
                            id = "card-1",
                            title = "card",
                            description = "desc",
                            badge = "card",
                            cover = testMedia("https://example.com/card.png", MediaKind.COVER),
                            link = "https://example.com/card",
                        ),
                    ),
                ),
            ),
        ).copy(
            occurredAtEpochSeconds = 1_710_000_000,
            link = "https://t.bilibili.com/dynamic-1",
        )
    }

    private fun demoLiveEnded(): SourceUpdate {
        return testDynamicUpdate(
            publisher = testPublisherInfo(name = "Demo UP"),
            eventType = SourceEventType.LIVE_ENDED,
            externalId = "live-456-ended",
            payload = LivePayload(
                roomId = "456",
                title = "Live title",
                area = "Games",
                cover = testMedia("https://example.com/live.png", MediaKind.COVER),
                status = LiveStatus.CLOSE,
                previousStatus = LiveStatus.OPEN,
                startedAtEpochSeconds = 1_710_000_000,
                endedAtEpochSeconds = 1_710_003_600,
            ),
        ).copy(
            occurredAtEpochSeconds = 1_710_003_600,
            link = "https://live.bilibili.com/456",
        )
    }
}
