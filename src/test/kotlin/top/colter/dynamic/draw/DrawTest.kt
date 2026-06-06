package top.colter.dynamic.draw

import kotlin.test.BeforeTest
import kotlin.test.Test
import top.colter.dynamic.DrawOrnament
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.DynamicBlockRole
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNode
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
import top.colter.dynamic.core.data.MediaRef
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
import top.colter.skiko.Fonts
import top.colter.skiko.data.Ratio

private const val TEST_IMAGE_PREFIX = "test://image/"
private const val TEST_EMOJI_PREFIX = "test://emoji/"

class DrawTest {
    @BeforeTest
    fun init() {
        Dp.factor = 1f
        Fonts.default.loadTextTypeface(loadTestResource("font", "HarmonyOS_SansSC_Medium.ttf").absolutePath)
        Fonts.default.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `test common dynamic previews`() {
        commonDynamicPreviews().forEach { preview ->
            renderToOutput(preview.fileName, preview.update, preview.config ?: drawConfig())
        }
    }

    private fun commonDynamicPreviews(): List<DynamicPreview> {
        return listOf(
            DynamicPreview(
                fileName = "preview_01_title_short_text.png",
                update = titleShortTextDynamic(),
            ),
            DynamicPreview(
                fileName = "preview_02_text_images_card.png",
                update = textImagesCardDynamic(),
                config = drawConfig(ornament = DrawOrnament.QRCODE),
            ),
//            DynamicPreview(
//                fileName = "preview_03_title_text_images_card.png",
//                update = titleTextImagesCardDynamic(),
//            ),
//            DynamicPreview(
//                fileName = "preview_04_text_video_card.png",
//                update = textVideoCardDynamic(),
//                config = drawConfig(ornament = DrawOrnament.QRCODE),
//            ),
//            DynamicPreview(
//                fileName = "preview_05_repost_dynamic.png",
//                update = repostDynamic(),
//            ),
//            DynamicPreview(
//                fileName = "preview_06_title_long_images.png",
//                update = titleLongTextImagesDynamic(),
//            ),
        )
    }

    private fun titleShortTextDynamic(): SourceUpdate {
        return testDynamicUpdate(
            publisher = previewPublisher(
                id = "preview-short",
                name = "可可萝优妮-KokoroUni",
                header = "header2.png",
            ),
            externalId = "preview-title-short-text",
            payload = DynamicPayload(
                title = "今晚八点开播",
                blocks = listOf(
                    richTextBlock(
                        text("准备了新的动态绘制效果预览，今晚一起看成片。"),
                        emoji("[热词系列_知识增加]"),
                        text(" 记得带上小零食。"),
                    ),
                ),
            ),
        )
    }

    private fun textImagesCardDynamic(): SourceUpdate {
        return testDynamicUpdate(
            publisher = previewPublisher(
                id = "preview-images-card",
                name = "云边观察员",
                header = "header2.png",
                avatar = "avatar1.jpg",
            ),
            externalId = "preview-text-images-card",
            payload = DynamicPayload(
                blocks = listOf(
                    richTextBlock(
                        text("今天整理了一组绘图模块的组合预览，主要看正文、图片和附加卡片放在一起时的节奏。"),
                        emoji("[阿库娅_不关我事]"),
                        text(" 图片之间的间距、正文和卡片之间的留白都需要一起观察，单独看某个组件反而不容易发现问题。"),
                        topic("绘图预览"),
                    ),
                    imageGrid(count = 3),
                    articleCard(
                        id = "preview-extra-article-small",
                        title = "动态转发工具排版记录",
                        description = "记录这次默认布局的边距、字号、主题色和媒体卡片联动效果。",
                        style = MediaCardStyle.SMALL,
                    ),
                ),
            ),
        )
    }

    private fun titleTextImagesCardDynamic(): SourceUpdate {
        return testDynamicUpdate(
            publisher = previewPublisher(
                id = "preview-title-images-card",
                name = "栗子工坊",
                header = "header1.png",
            ),
            externalId = "preview-title-text-images-card",
            payload = DynamicPayload(
                title = "四月绘图更新说明",
                blocks = listOf(
                    richTextBlock(
                        text("这次更新把标题、正文、图文内容和卡片放到同一张动态里检查。"),
                        emoji("[热词系列_大展宏兔]"),
                        text(" 如果标题字号变大后仍然舒服，说明正文缩放区间大致是稳的。"),
                        link("查看完整记录"),
                    ),
                    imageGrid(count = 4),
                    articleCard(
                        id = "preview-extra-article-large",
                        title = "默认布局视觉调整汇总",
                        description = "包含作者卡片、转发动态、二维码区域、媒体卡片和正文自适应字号。",
                        style = MediaCardStyle.LARGE,
                    ),
                ),
            ),
        )
    }

    private fun textVideoCardDynamic(): SourceUpdate {
        return testDynamicUpdate(
            publisher = previewPublisher(
                id = "preview-video-card",
                name = "薄荷放映室",
                header = "header2.png",
                avatar = "avatar1.jpg",
            ),
            externalId = "preview-text-video-card",
            payload = DynamicPayload(
                blocks = listOf(
                    richTextBlock(
                        text("新视频已经上传啦，封面和附加卡片一起看看效果。"),
                        emoji("[阿库娅_生气]"),
                    ),
                    videoCard(
                        id = "preview-video",
                        title = "默认布局从零打磨到能看的全过程",
                        description = "这一期主要聊绘图模块的设计取舍，以及为什么边距和字号会影响转发动态的阅读体验。",
                        badge = "视频",
                        style = MediaCardStyle.LARGE,
                    ),
                    musicCard(
                        id = "preview-extra-music",
                        title = "夜间调试用背景音乐",
                        description = "音乐 · 轻快 · 循环播放",
                    ),
                ),
            ),
        )
    }

    private fun repostDynamic(): SourceUpdate {
        val origin = testDynamicUpdate(
            publisher = previewPublisher(
                id = "preview-origin",
                name = "原图发布者",
                header = "header1.png",
                avatar = "avatar1.jpg",
                withPendant = false,
            ),
            externalId = "preview-origin-dynamic",
            payload = DynamicPayload(
                title = "原动态的完整内容",
                blocks = listOf(
                    richTextBlock(
                        text("这是被转发的原始动态，里面有正文、表情和一个小卡片。"),
                        emoji("[热词系列_知识增加]"),
                        text(" 用它来观察转发卡片里的作者栏、正文和附加内容是否协调。"),
                    ),
                    articleCard(
                        id = "preview-origin-card",
                        title = "原动态里的附加说明",
                        description = "这里模拟原作者附带的专栏卡片，用来检查嵌套卡片的空间感。",
                        style = MediaCardStyle.SMALL,
                    ),
                ),
            ),
        )

        return testDynamicUpdate(
            publisher = previewPublisher(
                id = "preview-repost",
                name = "转发观察员",
                header = "header2.png",
            ),
            externalId = "preview-repost-dynamic",
            payload = DynamicPayload(
                blocks = listOf(
                    richTextBlock(
                        text("转发一下这个版本的效果，重点看原动态作者栏和正文之间的距离。"),
                        emoji("[阿库娅_不关我事]"),
                        mention("原图发布者"),
                    ),
                    RepostBlock(
                        referenceKind = DynamicReferenceKind.ORIGIN,
                        key = origin.key,
                        link = origin.link,
                        embedded = origin,
                    ),
                ),
            ),
        )
    }

    private fun titleLongTextImagesDynamic(): SourceUpdate {
        return testDynamicUpdate(
            publisher = previewPublisher(
                id = "preview-long-images",
                name = "长文记录本",
                header = "header1.png",
            ),
            externalId = "preview-title-long-images",
            payload = DynamicPayload(
                title = "长文字和多图的阅读密度观察",
                blocks = listOf(
                    richTextBlock(
                        text("这条动态故意写得稍微长一些，用来观察自适应字号在长文本下的表现。"),
                        emoji("[热词系列_大展宏兔]"),
                        text(" 当正文超过几百字后，图片区域、作者卡片和标题之间的比例会变得更敏感；如果字号太小，聊天窗口里会显得像一整块灰色文字，如果字号太大，又会让整张图被拉得过长。"),
                        text(" 所以这里把多图也放进来，顺便检查九宫格附近的上下边距是否自然。"),
                    ),
                    imageGrid(count = 9),
                ),
            ),
        )
    }

    private data class DynamicPreview(
        val fileName: String,
        val update: SourceUpdate,
        val config: DrawConfig? = null,
    )

    private fun richTextBlock(vararg nodes: DynamicContentNode): TextBlock {
        return TextBlock(DynamicContent(nodes.toList()))
    }

    private fun text(value: String): DynamicContentNodeText {
        return DynamicContentNodeText(value)
    }

    private fun emoji(text: String): DynamicContentNodeEmoji {
        return DynamicContentNodeEmoji(
            text = text,
            image = emojiMedia("$text.png"),
        )
    }

    private fun topic(value: String): DynamicContentNodeTag {
        return DynamicContentNodeTag(
            text = " #$value#",
            tagType = DynamicContentTagType.TOPIC,
            externalId = value,
            url = "https://example.com/topic/$value",
        )
    }

    private fun link(value: String): DynamicContentNodeLink {
        return DynamicContentNodeLink(
            text = " $value",
            url = "https://example.com/preview",
        )
    }

    private fun mention(value: String): DynamicContentNodeMention {
        return DynamicContentNodeMention(
            text = " @$value",
            publisherKey = testPublisherKey(externalId = value),
            url = "https://example.com/publisher/$value",
        )
    }

    private fun imageGrid(count: Int): ImageGridBlock {
        return ImageGridBlock(
            images = (1..count).map { index ->
                ImageItem(
                    image = imageMedia("bg1.jpg", MediaKind.IMAGE),
                    badge = imageBadge(index).takeIf { count > 1 },
                )
            },
        )
    }

    private fun imageBadge(index: Int): String {
        return listOf("图一", "图二", "图三", "图四", "图五", "图六", "图七", "图八", "图九")[index - 1]
    }

    private fun articleCard(
        id: String,
        title: String,
        description: String,
        style: MediaCardStyle,
        role: DynamicBlockRole = DynamicBlockRole.ADDITIONAL,
    ): MediaCardBlock {
        return cardBlock(
            id = id,
            kind = DynamicMediaCardKind.ARTICLE,
            sourceKind = "专栏",
            title = title,
            description = description,
            badge = "专栏",
            cover = imageMedia("bg1.jpg", MediaKind.COVER),
            coverRatio = Ratio.BANNER,
            style = style,
            role = role,
        )
    }

    private fun videoCard(
        id: String,
        title: String,
        description: String,
        badge: String,
        style: MediaCardStyle,
    ): MediaCardBlock {
        return cardBlock(
            id = id,
            kind = DynamicMediaCardKind.VIDEO,
            sourceKind = "视频",
            title = title,
            description = description,
            badge = badge,
            cover = imageMedia("bg1.jpg", MediaKind.COVER),
            durationSeconds = 633,
            metrics = listOf(
                DynamicMetric(key = "play", raw = 12600, display = "一万二"),
                DynamicMetric(key = "danmaku", raw = 358, display = "三百五十八"),
            ),
            style = style,
        )
    }

    private fun musicCard(
        id: String,
        title: String,
        description: String,
    ): MediaCardBlock {
        return cardBlock(
            id = id,
            kind = DynamicMediaCardKind.MUSIC,
            sourceKind = "音乐",
            title = title,
            description = description,
            badge = "音乐",
            cover = imageMedia("bg1.jpg", MediaKind.COVER),
            coverRatio = Ratio.SQUARE,
            style = MediaCardStyle.MINI,
            role = DynamicBlockRole.ADDITIONAL,
        )
    }

    private fun cardBlock(
        id: String,
        kind: DynamicMediaCardKind,
        sourceKind: String,
        title: String,
        description: String,
        badge: String,
        cover: MediaRef,
        coverRatio: Float? = null,
        durationSeconds: Long? = null,
        metrics: List<DynamicMetric> = emptyList(),
        style: MediaCardStyle = MediaCardStyle.LARGE,
        role: DynamicBlockRole = DynamicBlockRole.BODY,
    ): MediaCardBlock {
        return MediaCardBlock(
            style = style,
            role = role,
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

    private fun previewPublisher(
        id: String,
        name: String,
        header: String,
        avatar: String = "avatar.jpg",
        withPendant: Boolean = true,
    ): PublisherInfo {
        return testPublisherInfo(
            key = testPublisherKey(externalId = id),
            name = name,
            avatarBadgeKey = "avatarBadge.official.individual",
            avatar = imageMedia(avatar, MediaKind.AVATAR),
            banner = imageMedia(header, MediaKind.COVER),
            pendant = imageMedia("pendant.png", MediaKind.AVATAR).takeIf { withPendant },
        )
    }

    private fun imageMedia(fileName: String, kind: MediaKind): MediaRef {
        return testMedia(
            uri = "$TEST_IMAGE_PREFIX$fileName",
            kind = kind,
            alt = fileName,
        )
    }

    private fun emojiMedia(fileName: String): MediaRef {
        return testMedia(
            uri = "$TEST_EMOJI_PREFIX$fileName",
            kind = MediaKind.EMOJI,
            alt = fileName,
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
            platform = PlatformDescriptor.of("bilibili", "哔哩哔哩"),
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
            uri.startsWith(TEST_IMAGE_PREFIX) -> "image" to uri.removePrefix(TEST_IMAGE_PREFIX)
            uri.startsWith(TEST_EMOJI_PREFIX) -> "emoji" to uri.removePrefix(TEST_EMOJI_PREFIX)
            reference.kind == MediaKind.EMOJI -> "emoji" to "[热词系列_知识增加].png"
            reference.kind == MediaKind.AVATAR -> "image" to "avatar.jpg"
            reference.kind == MediaKind.COVER -> "image" to "bg1.jpg"
            else -> "image" to "bg1.jpg"
        }
        return loadTestResource(resource.first, resource.second).readBytes()
    }
}
