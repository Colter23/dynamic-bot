package top.colter.dynamic.draw

import kotlin.test.BeforeTest
import kotlin.test.Test
import top.colter.dynamic.DrawOrnament
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.CardAttachment
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicContentTagType
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.ImageAttachment
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaReference
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.SourceUpdateReference
import top.colter.dynamic.core.data.VideoAttachment
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
                content = DynamicContent.text("Demo content"),
                attachments = listOf(
                    ImageAttachment(
                        images = listOf(
                            ImageItem(testMedia("https://example.com/pic.png", MediaKind.IMAGE)),
                        ),
                    ),
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
                content = DynamicContent(
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
        )
        renderToOutput("dynamic_content_nodes.png", update)
    }

    @Test
    fun `test dynamic image grids`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                content = DynamicContent.text("Image grid variants"),
                attachments = listOf(
                    imageAttachment(count = 2, prefix = "two"),
                    imageAttachment(count = 4, prefix = "four"),
                    imageAttachment(count = 9, prefix = "nine"),
                ),
            ),
        )
        renderToOutput("dynamic_image_grids.png", update)
    }

    @Test
    fun `test dynamic video attachment`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                content = DynamicContent.text("Video attachment"),
                attachments = listOf(
                    VideoAttachment(
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
                content = DynamicContent.text("Card attachment"),
                attachments = listOf(
                    CardAttachment(
                        id = "article-demo",
                        cardKind = "article",
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
                content = DynamicContent.text("Original dynamic content"),
                attachments = listOf(
                    VideoAttachment(
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
                content = DynamicContent.text("Repost with embedded origin"),
                references = listOf(
                    SourceUpdateReference(
                        kind = DynamicReferenceKind.ORIGIN,
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
            payload = DynamicPayload(content = DynamicContent.text("Publisher ornament variants")),
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
            payload = DynamicPayload(content = DynamicContent.text("Publisher without banner uses default head")),
        )

        renderToOutput("dynamic_publisher_default_head.png", update)
    }

    @Test
    fun `test minimal dynamic layout`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                title = "Minimal layout demo",
                content = DynamicContent.text("Minimal currently delegates to the default dynamic renderer."),
                attachments = listOf(imageAttachment(count = 3, prefix = "minimal")),
            ),
        )
        renderToOutput(
            fileName = "dynamic_minimal_layout.png",
            update = update,
            config = drawConfig(layout = "minimal"),
        )
    }

    private fun imageAttachment(count: Int, prefix: String): ImageAttachment {
        return ImageAttachment(
            images = (1..count).map { index ->
                ImageItem(
                    image = testMedia("https://example.com/$prefix-image-$index.png", MediaKind.IMAGE),
                    badge = if (index == 1) "$count pics" else null,
                )
            },
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
