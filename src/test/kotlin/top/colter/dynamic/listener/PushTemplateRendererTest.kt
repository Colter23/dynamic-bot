package top.colter.dynamic.listener

import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicMedia
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaPic
import top.colter.dynamic.core.data.DynamicMediaVideo
import top.colter.dynamic.core.data.DynamicMediaVideoStats
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.LiveChange
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.LiveStatusUpdate
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformKind
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            drawImage = LazyImage("D:/tmp/draw.png"),
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
                "https://www.bilibili.com/video/BV1",
                "https://example.com/card",
                "https://example.com/content-link",
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

    private fun demoDynamic(): Dynamic {
        return Dynamic(
            platform = PlatformDescriptor(
                id = "bilibili",
                name = "BiliBili",
                homepage = "https://www.bilibili.com",
                iconUri = "",
                kind = PlatformKind.PUBLISHER,
            ),
            dynamicId = "dynamic-1",
            publisher = Publisher(
                id = 1,
                platformId = "bilibili",
                type = PublisherType.USER,
                externalId = "123",
                name = "Demo UP",
                state = EntityState.ACTIVE,
                face = LazyImage("https://example.com/face.png"),
                createTime = 1,
                createUser = 1,
            ),
            time = 1_710_000_000,
            link = "https://t.bilibili.com/dynamic-1",
            title = "Demo Title",
            content = DynamicContent(
                text = "Demo content",
                contentNodes = listOf(
                    DynamicContentNodeText("Demo content"),
                    DynamicContentNodeLink("link", url = "https://example.com/content-link"),
                ),
            ),
            media = DynamicMedia(
                pics = listOf(
                    DynamicMediaPic(LazyImage("https://example.com/pic-a.png"), width = 100, height = 100),
                    DynamicMediaPic(LazyImage("https://example.com/pic-b.png"), width = 100, height = 100),
                ),
                video = DynamicMediaVideo(
                    id = "BV1",
                    title = "video",
                    description = "desc",
                    cover = LazyImage("https://example.com/cover.png"),
                    duration = "01:00",
                    badge = "video",
                    stats = DynamicMediaVideoStats(play = "1", danmaku = "2", like = "3"),
                    link = "https://www.bilibili.com/video/BV1",
                ),
                card = DynamicMediaCard(
                    id = "card-1",
                    type = "article",
                    title = "card",
                    description = "desc",
                    badge = "card",
                    cover = LazyImage("https://example.com/card.png"),
                    link = "https://example.com/card",
                ),
            ),
        )
    }

    private fun demoLiveEnded(): LiveStatusUpdate {
        val dynamic = demoDynamic()
        return LiveStatusUpdate(
            platform = dynamic.platform,
            publisher = dynamic.publisher,
            roomId = "456",
            time = 1_710_003_600,
            title = "Live title",
            area = "Games",
            cover = LazyImage("https://example.com/live.png"),
            link = "https://live.bilibili.com/456",
            status = LiveStatus.CLOSE,
            previousStatus = LiveStatus.OPEN,
            change = LiveChange.ENDED,
            startedAt = 1_710_000_000,
            endedAt = 1_710_003_600,
        )
    }
}
