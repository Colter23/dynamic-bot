package top.colter.dynamic.draw

import kotlin.test.BeforeTest
import kotlin.test.Test
import top.colter.dynamic.DrawOrnament
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicContentTagType
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaReference
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.mediaReferences
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.loadTestResource
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testOutput
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey
import top.colter.skiko.Dp
import top.colter.skiko.FontUtils
import top.colter.skiko.data.Ratio

class DrawTest {
    @BeforeTest
    fun init() {
        Dp.factor = 1f
        FontUtils.loadTypeface(loadTestResource("font", "HarmonyOS_SansSC_Medium.ttf").absolutePath)
        FontUtils.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `test dynamic`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                blocks = listOf(
                    textBlock("Demo content"),
                    ImageGridBlock(
                        images = listOf(ImageItem(testMedia("https://example.com/pic.png", MediaKind.IMAGE))),
                    )
                ),
            ),
        )
        renderToOutput("test1.png", update)
    }

    @Test
    fun `test dynamic content nodes`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                title = "Content node demo",
                blocks = listOf(
                    TextBlock(
                        DynamicContent(
                            nodes = listOf(
                                DynamicContentNodeText("Plain text "),
                                DynamicContentNodeEmoji(
                                    text = "[tv_doge]",
                                    image = testMedia("https://example.com/emoji/tv_doge.png", MediaKind.EMOJI),
                                ),
                                DynamicContentNodeLink(
                                    text = " link",
                                    icon = testMedia("https://example.com/icon.png", MediaKind.IMAGE),
                                    url = "https://example.com",
                                ),
                                DynamicContentNodeMention(
                                    text = " @demo",
                                    publisherKey = testPublisherKey(externalId = "mention-demo"),
                                    url = "https://example.com/mention",
                                ),
                                DynamicContentNodeTag(
                                    text = " #topic",
                                    tagType = DynamicContentTagType.TOPIC,
                                    externalId = "topic-demo",
                                    url = "https://example.com/topic",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        renderToOutput("dynamic_content_nodes.png", update)
    }

    @Test
    fun `test dynamic image grids`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                blocks = listOf(
                    textBlock("Image grid variants"),
                    imageBlock(count = 2, prefix = "two"),
                    imageBlock(count = 4, prefix = "four"),
                    imageBlock(count = 9, prefix = "nine"),
                ),
            ),
        )
        renderToOutput("dynamic_image_grids.png", update)
    }

    @Test
    fun `test dynamic video attachment`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                blocks = listOf(
                    textBlock("Video attachment"),
                    videoBlock(
                        id = "video-demo",
                        title = "Demo video title",
                        description = "A rendered video card with cover, badge, duration, and metrics.",
                        cover = testMedia("https://example.com/video-cover.jpg", MediaKind.COVER),
                        durationSeconds = 83,
                        badge = "Video",
                        metrics = listOf(
                            DynamicMetric(key = "play", raw = 12000, display = "1.2w"),
                            DynamicMetric(key = "danmaku", raw = 345, display = "345"),
                        ),
                    ),
                ),
            ),
        )
        renderToOutput("dynamic_video.png", update)
    }

    @Test
    fun `test dynamic card attachment`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                blocks = listOf(
                    textBlock("Card attachment"),
                    cardBlock(
                        id = "article-demo",
                        kind = DynamicMediaCardKind.ARTICLE,
                        sourceKind = "article",
                        title = "Demo article card",
                        description = "A rendered card with a banner cover and badge.",
                        badge = "Article",
                        cover = testMedia("https://example.com/article-cover.jpg", MediaKind.COVER),
                        coverRatio = Ratio.BANNER,
                    ),
                ),
            ),
        )
        renderToOutput("dynamic_card.png", update)
    }

    @Test
    fun `test dynamic origin reference`() {
        val origin = testDynamicUpdate(
            externalId = "origin-dynamic",
            publisher = demoPublisher("origin"),
            payload = DynamicPayload(
                blocks = listOf(
                    textBlock("Original dynamic content"),
                    videoBlock(
                        id = "origin-video",
                        title = "Forward mode video card",
                        description = "Rendered through the embedded origin dynamic path.",
                        cover = testMedia("https://example.com/origin-video-cover.jpg", MediaKind.COVER),
                        durationSeconds = 3723,
                        badge = "Replay",
                    ),
                ),
            ),
        )
        val update = testDynamicUpdate(
            externalId = "repost-dynamic",
            payload = DynamicPayload(
                blocks = listOf(
                    textBlock("Repost with embedded origin"),
                    RepostBlock(
                        referenceKind = DynamicReferenceKind.ORIGIN,
                        key = origin.key,
                        link = origin.link,
                        embedded = origin,
                    ),
                ),
            ),
        )
        renderToOutput("dynamic_origin_reference.png", update)
    }

    @Test
    fun `test dynamic publisher ornaments`() {
        val publisher = demoPublisher("ornament").copy(official = "BILIBILI_A.png")
        val update = testDynamicUpdate(
            publisher = publisher,
            payload = DynamicPayload(blocks = listOf(textBlock("Publisher ornament variants"))),
        )

        renderToOutput(
            fileName = "dynamic_publisher_qrcode.png",
            update = update,
            config = drawConfig(ornament = DrawOrnament.QRCODE),
        )
        renderToOutput(
            fileName = "dynamic_publisher_none.png",
            update = update,
            config = drawConfig(ornament = DrawOrnament.NONE),
        )
    }

    @Test
    fun `test dynamic publisher default head`() {
        val publisher = testPublisherInfo(
            key = testPublisherKey(externalId = "publisher-default-head"),
            name = "demo-default-head",
            avatar = testMedia("https://example.com/default-head-avatar.jpg", MediaKind.AVATAR),
            banner = null,
        )
        val update = testDynamicUpdate(
            publisher = publisher,
            payload = DynamicPayload(blocks = listOf(textBlock("Publisher without banner uses default head"))),
        )

        renderToOutput("dynamic_publisher_default_head.png", update)
    }

    @Test
    fun `test minimal dynamic layout`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                title = "Minimal layout demo",
                blocks = listOf(
                    textBlock("Minimal currently delegates to the default dynamic renderer."),
                    imageBlock(count = 3, prefix = "minimal"),
                ),
            ),
        )
        renderToOutput(
            fileName = "dynamic_minimal_layout.png",
            update = update,
            config = drawConfig(layout = "minimal"),
        )
    }

    private fun textBlock(text: String): TextBlock {
        return TextBlock(DynamicContent.text(text))
    }

    private fun imageBlock(count: Int, prefix: String): ImageGridBlock {
        return ImageGridBlock(
            images = (1..count).map { index ->
                ImageItem(
                    image = testMedia("https://example.com/$prefix-image-$index.png", MediaKind.IMAGE),
                    badge = if (index == 1) "$count pics" else null,
                )
            },
        )
    }

    private fun videoBlock(
        id: String,
        title: String,
        description: String,
        cover: top.colter.dynamic.core.data.MediaRef,
        durationSeconds: Long,
        badge: String,
        metrics: List<DynamicMetric> = emptyList(),
        style: MediaCardStyle = MediaCardStyle.LARGE,
    ): MediaCardBlock {
        return cardBlock(
            id = id,
            kind = DynamicMediaCardKind.VIDEO,
            sourceKind = "video",
            title = title,
            description = description,
            cover = cover,
            durationSeconds = durationSeconds,
            badge = badge,
            metrics = metrics,
            style = style,
        )
    }

    private fun cardBlock(
        id: String,
        kind: DynamicMediaCardKind,
        sourceKind: String,
        title: String,
        description: String,
        badge: String,
        cover: top.colter.dynamic.core.data.MediaRef,
        coverRatio: Float? = null,
        durationSeconds: Long? = null,
        metrics: List<DynamicMetric> = emptyList(),
        style: MediaCardStyle = MediaCardStyle.LARGE,
    ): MediaCardBlock {
        return MediaCardBlock(
            style = style,
            card = DynamicMediaCard(
                kind = kind,
                sourceKind = sourceKind,
                id = id,
                title = title,
                description = description,
                badge = badge,
                cover = cover,
                coverRatio = coverRatio,
                durationSeconds = durationSeconds,
                metrics = metrics,
            ),
        )
    }

    private fun demoPublisher(suffix: String): PublisherInfo {
        return testPublisherInfo(
            key = testPublisherKey(externalId = "publisher-$suffix"),
            name = "demo-$suffix",
            avatar = testMedia("https://example.com/avatar-$suffix.jpg", MediaKind.AVATAR),
            banner = testMedia("https://example.com/banner-$suffix.jpg", MediaKind.COVER),
            pendant = testMedia("https://example.com/pendant-$suffix.png", MediaKind.AVATAR),
        )
    }

    private fun renderToOutput(
        fileName: String,
        update: SourceUpdate,
        config: DrawConfig = drawConfig(),
    ) {
        cacheMedia(update)
        val img = renderDynamicImage(update = update, config = config)
        testOutput.resolve(fileName).writeBytes(img.encodeToData()!!.bytes)
    }

    private fun drawConfig(
        layout: String = "default",
        ornament: DrawOrnament = DrawOrnament.LOGO,
    ): DrawConfig {
        return DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            settings = DrawSettings(layout = layout, ornament = ornament),
        )
    }

    private fun cacheMedia(update: SourceUpdate) {
        update.mediaReferences().forEach { reference ->
            DynamicImageCache.put(reference.media, testImageBytes(reference))
        }
    }

    private fun testImageBytes(reference: MediaReference): ByteArray {
        val uri = reference.media.uri
        val resource = when {
            "pendant" in uri -> "image" to "pendant.png"
            "banner" in uri -> "image" to "banner.jpg"
            reference.kind == MediaKind.EMOJI -> "emoji" to "[tv_doge].png"
            reference.kind == MediaKind.COVER -> "image" to "bg1.jpg"
            else -> "image" to "avatar.jpg"
        }
        return loadTestResource(resource.first, resource.second).readBytes()
    }
}
