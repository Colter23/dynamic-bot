package top.colter.dynamic.draw

import kotlin.test.BeforeTest
import kotlin.test.Test
import org.jetbrains.skia.Color
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
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MediaReference
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.mediaReferences
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.loadTestImage
import top.colter.dynamic.loadTestResource
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testOutput
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.layout.*

private const val TEST_IMAGE_PREFIX = "test://image/"
private const val TEST_EMOJI_PREFIX = "test://emoji/"
private const val PREVIEW_LAYOUTS_PROPERTY = "dynamic.draw.preview.layouts"
private const val PREVIEW_LAYOUTS_ENV = "DYNAMIC_DRAW_PREVIEW_LAYOUTS"
private const val DARK_PREVIEW_THEME_COLORS = "#101624;#24182D;#0D2630"
private val defaultPreviewLayouts = listOf("default", "minimal")

class DrawTest {
    @BeforeTest
    fun init() {
        Dp.factor = 1f
        Fonts.default.loadTextTypeface(loadTestResource("font", "HarmonyOS_SansSC_Medium.ttf").absolutePath)
        Fonts.default.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `test common dynamic previews`() {
        val layouts = previewLayouts()
        commonDynamicPreviews().forEach { preview ->
            layouts.forEach { layout ->
                renderToOutput(
                    fileName = previewFileName(layout, preview.fileName),
                    update = preview.update,
                    config = drawConfig(layout = layout, ornament = preview.ornament),
                )
            }
        }
    }

    @Test
    fun `test link preview draw previews`() {
        val layouts = previewLayouts()
        linkPreviewPreviews().forEach { preview ->
            layouts.forEach { layout ->
                renderLinkPreviewToOutput(
                    fileName = previewFileName(layout, preview.fileName),
                    preview = preview.preview,
                    config = drawConfig(layout = layout, ornament = preview.ornament),
                )
            }
        }
    }

    @Test
    fun `test live open draw previews`() {
        val layouts = previewLayouts()
        liveOpenPreviews().forEach { preview ->
            layouts.forEach { layout ->
                renderLiveToOutput(
                    fileName = previewFileName(layout, preview.fileName),
                    update = preview.update,
                    config = drawConfig(layout = layout, ornament = preview.ornament),
                )
            }
        }
    }

    @Test
    fun `test dark theme draw previews`() {
        val layouts = previewLayouts()
        commonDynamicPreviews().forEach { preview ->
            layouts.forEach { layout ->
                renderToOutput(
                    fileName = previewFileName("dark_$layout", preview.fileName),
                    update = preview.update,
                    config = drawConfig(
                        layout = layout,
                        ornament = preview.ornament,
                        themeColors = DARK_PREVIEW_THEME_COLORS,
                    ),
                )
            }
        }
        linkPreviewPreviews().forEach { preview ->
            layouts.forEach { layout ->
                renderLinkPreviewToOutput(
                    fileName = previewFileName("dark_$layout", preview.fileName),
                    preview = preview.preview,
                    config = drawConfig(
                        layout = layout,
                        ornament = preview.ornament,
                        themeColors = DARK_PREVIEW_THEME_COLORS,
                    ),
                )
            }
        }
        liveOpenPreviews().forEach { preview ->
            layouts.forEach { layout ->
                renderLiveToOutput(
                    fileName = previewFileName("dark_$layout", preview.fileName),
                    update = preview.update,
                    config = drawConfig(
                        layout = layout,
                        ornament = preview.ornament,
                        themeColors = DARK_PREVIEW_THEME_COLORS,
                    ),
                )
            }
        }
    }

    @Test
    fun `test generated theme previews`() {
        renderThemePalettePreviewToOutput()

        val layouts = previewLayouts()
        val update = textImagesCardDynamic()
        themePreviewCases().forEach { themeCase ->
            layouts.forEach { layout ->
                renderToOutput(
                    fileName = previewFileName(layout, "theme_${themeCase.fileName}_dynamic.png"),
                    update = update,
                    config = drawConfig(
                        layout = layout,
                        ornament = DrawOrnament.QRCODE,
                        themeColors = themeCase.colors,
                    ),
                )
            }
        }
    }

    @Test
    fun `test avatar generated theme previews`() {
        val avatarCases = avatarThemePreviewCases()
        renderAvatarThemePalettePreviewToOutput(avatarCases)

        val layouts = previewLayouts()
        avatarCases.forEach { avatarCase ->
            val update = avatarThemeDynamic(avatarCase)
            layouts.forEach { layout ->
                renderToOutput(
                    fileName = previewFileName(layout, "avatar_theme_${avatarCase.fileName}_dynamic.png"),
                    update = update,
                    config = drawConfig(
                        layout = layout,
                        ornament = DrawOrnament.QRCODE,
                        themeColors = avatarCase.colors,
                    ),
                )
            }
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
                ornament = DrawOrnament.QRCODE,
            ),
            DynamicPreview(
                fileName = "preview_03_title_text_images_card.png",
                update = titleTextImagesCardDynamic(),
            ),
            DynamicPreview(
                fileName = "preview_04_text_video_card.png",
                update = textVideoCardDynamic(),
                ornament = DrawOrnament.QRCODE,
            ),
            DynamicPreview(
                fileName = "preview_05_repost_dynamic.png",
                update = repostDynamic(),
            ),
            DynamicPreview(
                fileName = "preview_06_title_long_images.png",
                update = titleLongTextImagesDynamic(),
            ),
        )
    }

    private fun liveOpenPreviews(): List<DynamicPreview> {
        return listOf(
            DynamicPreview(
                fileName = "live_preview_01_open.png",
                update = liveOpenUpdate(),
                ornament = DrawOrnament.QRCODE,
            ),
        )
    }

    private fun linkPreviewPreviews(): List<LinkPreviewCase> {
        return listOf(
            LinkPreviewCase(
                fileName = "link_preview_01_video_cover.png",
                preview = videoLinkPreview(),
            ),
            LinkPreviewCase(
                fileName = "link_preview_02_live_qrcode.png",
                preview = liveLinkPreview(),
                ornament = DrawOrnament.QRCODE,
            ),
            LinkPreviewCase(
                fileName = "link_preview_03_user_banner.png",
                preview = userLinkPreview(),
            ),
            LinkPreviewCase(
                fileName = "link_preview_04_long_no_cover.png",
                preview = longNoCoverLinkPreview(),
                ornament = DrawOrnament.QRCODE,
            ),
            LinkPreviewCase(
                fileName = "link_preview_05_article_cover.png",
                preview = articleLinkPreview(),
            ),
        )
    }

    private fun videoLinkPreview(): LinkPreview {
        return LinkPreview(
            platformId = PlatformId.of("bilibili"),
            kind = LinkKinds.VIDEO,
            id = "BV1Preview001",
            url = "https://www.bilibili.com/video/BV1Preview001",
            title = "链接解析绘图模块重构记录",
            description = "这是一条通过链接解析得到的视频预览，主要观察封面、标题、简介、时长和播放数据在媒体卡片里的组合效果。",
            badge = "视频",
            cover = imageMedia("bg1.jpg", MediaKind.COVER),
            publisher = previewPublisher(
                id = "link-video-up",
                name = "链接解析观察员",
                header = "header1.png",
            ),
            metrics = listOf(
                DynamicMetric(key = "play", raw = 246000, display = "24.6万"),
                DynamicMetric(key = "danmaku", raw = 1832, display = "1832"),
                DynamicMetric(key = "like", raw = 37000, display = "3.7万"),
            ),
            durationSeconds = 9 * 60 + 36,
        )
    }

    private fun liveLinkPreview(): LinkPreview {
        return LinkPreview(
            platformId = PlatformId.of("bilibili"),
            kind = LinkKinds.LIVE,
            id = "23058",
            url = "https://live.bilibili.com/23058",
            title = "今晚一起看动态卡片排版",
            description = "绘图 / 开发杂谈",
            badge = "直播中",
            cover = imageMedia("bg1.jpg", MediaKind.COVER),
            publisher = previewPublisher(
                id = "link-live-up",
                name = "云端调试台",
                header = "header2.png",
                avatar = "avatar1.jpg",
                withPendant = false,
            ),
            metrics = listOf(
                DynamicMetric(key = "online", raw = 12800, display = "1.3万"),
                DynamicMetric(key = "follow", raw = 560000, display = "56万"),
            ),
            liveStatus = LiveStatus.OPEN,
            liveStartedAtEpochSeconds = 1L,
        )
    }

    private fun userLinkPreview(): LinkPreview {
        return LinkPreview(
            platformId = PlatformId.of("bilibili"),
            kind = LinkKinds.USER,
            id = "10086",
            url = "https://space.bilibili.com/10086",
            title = "可可萝优妮-KokoroUni",
            description = "Bilibili 用户 10086",
            badge = "用户",
            cover = imageMedia("header2.png", MediaKind.COVER),
            publisher = previewPublisher(
                id = "10086",
                name = "可可萝优妮-KokoroUni",
                header = "header2.png",
                avatar = "avatar1.jpg",
            ),
        )
    }

    private fun longNoCoverLinkPreview(): LinkPreview {
        return LinkPreview(
            platformId = PlatformId.of("bilibili"),
            kind = "article",
            id = "cv-preview-long",
            url = "https://www.bilibili.com/read/cv-preview-long",
            title = "一篇关于动态转发绘图的长说明",
            description = "这条链接没有封面，所以测试会把标题提到动态标题位置，并把较长的简介作为正文绘制。这样可以观察链接解析结果在没有封面时是否仍然有完整的信息层级：先看到标题，再读到正文，最后通过一个紧凑的小卡片知道它来自哪个页面。这里故意写得稍长一些，避免小媒体卡片把正文裁得过碎。",
            badge = "专栏",
            publisher = previewPublisher(
                id = "link-article-up",
                name = "文档整理站",
                header = "header1.png",
                withPendant = false,
            ),
            metrics = listOf(
                DynamicMetric(key = "like", raw = 9200, display = "9200"),
                DynamicMetric(key = "comment", raw = 316, display = "316"),
            ),
        )
    }

    private fun articleLinkPreview(): LinkPreview {
        return LinkPreview(
            platformId = PlatformId.of("bilibili"),
            kind = "article",
            id = "cv-preview-cover",
            url = "https://www.bilibili.com/read/cv-preview-cover",
            title = "默认布局边距调整记录",
            description = "整理作者卡片、二维码区域、媒体卡片和转发动态卡片的最新视觉调整。",
            badge = "专栏",
            cover = imageMedia("bg1.jpg", MediaKind.COVER),
            publisher = previewPublisher(
                id = "link-cover-up",
                name = "排版备忘录",
                header = "header2.png",
            ),
            metrics = listOf(
                DynamicMetric(key = "like", raw = 18000, display = "1.8万"),
                DynamicMetric(key = "favorite", raw = 8200, display = "8200"),
            ),
        )
    }

    private fun liveOpenUpdate(): SourceUpdate {
        return testDynamicUpdate(
            publisher = previewPublisher(
                id = "preview-live-open",
                name = "星河直播间",
                header = "header1.png",
                avatar = "avatar1.jpg",
                withPendant = false,
            ),
            externalId = "preview-live-open",
            payload = LivePayload(
                roomId = "23058",
                title = "今晚八点一起看动态绘图效果",
                area = "绘图 / 开发杂谈",
                cover = imageMedia("bg1.jpg", MediaKind.COVER),
                status = LiveStatus.OPEN,
                startedAtEpochSeconds = 1L,
            ),
        ).copy(link = "https://live.bilibili.com/23058")
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
                        text("看到这个评论就想简单讲一下，其实也没啥特别的技巧，毛坯买的是一米三玉米须万用，为了还原立绘那种长发。做法就是前面的头发再烫一遍很蓬松的玉米须，只有烫蓬松了才能做出来层次感。\n" +
                                "然后分区开始做刘海，先分三大块最后再做层次细分。我比较喜欢保留一点毛流感，所以需要两侧刘海要打薄，这样做出来有层次感。其实原图没这么层次感，只是做毛的时候喜欢加一点自己理解进去。做中间这一缕，要想有层次就要从这这块最底下这层发排取一部分作为侧面刘海收进去（其实我中间刘海发量取多了有点挡脸，而且鬓角做多了，其实不用那么多。）边做边喷一点水，过会再喷发胶，还没干的时候用手掐用尖尾梳弄出一点纹理来都可以。后面头发做了一点防炸，但是拍到后面也炸了。。还得再研究下防炸方法。当然我也没什么技巧，纯属为了省钱自学的，可能有些方法并不是很主流"),
                    ),
                    imageGrid(count = 9),
                ),
            ),
        )
    }

    private fun avatarThemeDynamic(avatarCase: AvatarThemePreviewCase): SourceUpdate {
        return testDynamicUpdate(
            publisher = previewPublisher(
                id = "avatar-theme-${avatarCase.fileName}",
                name = "头像主题观察员",
                header = "header1.png",
                avatar = avatarCase.avatarFile,
                withPendant = false,
            ),
            externalId = "avatar-theme-${avatarCase.fileName}",
            payload = DynamicPayload(
                title = "头像自动主题预览",
                blocks = listOf(
                    richTextBlock(
                        text("这条动态使用从 ${avatarCase.avatarFile} 提取出的主题色：${avatarCase.colors}。"),
                        emoji("[热词系列_知识增加]"),
                        text(" 重点看作者名、正文链接、媒体标签和二维码区域是否协调。"),
                    ),
                    imageGrid(count = 2),
                    articleCard(
                        id = "avatar-theme-card-${avatarCase.fileName}",
                        title = "自动主题色效果检查",
                        description = "同一套动态内容搭配不同头像提取色，方便观察浅色、暗色、人物和 logo 头像的差异。",
                        style = MediaCardStyle.SMALL,
                    ),
                ),
            ),
        )
    }

    private data class DynamicPreview(
        val fileName: String,
        val update: SourceUpdate,
        val ornament: DrawOrnament = DrawOrnament.LOGO,
    )

    private data class LinkPreviewCase(
        val fileName: String,
        val preview: LinkPreview,
        val ornament: DrawOrnament = DrawOrnament.LOGO,
    )

    private data class ThemePreviewCase(
        val fileName: String,
        val title: String,
        val colors: String,
    )

    private data class AvatarThemePreviewCase(
        val fileName: String,
        val title: String,
        val avatarFile: String,
        val colors: String,
        val extracted: Boolean,
    )

    private fun themePreviewCases(): List<ThemePreviewCase> {
        return listOf(
            ThemePreviewCase("01_default_pink", "默认粉色", "#FE65A6"),
            ThemePreviewCase("02_cyan", "高亮青色", "#BFFAFF"),
            ThemePreviewCase("03_gold", "高亮金色", "#FFD700"),
            ThemePreviewCase("04_purple", "紫色", "#8B5CF6"),
            ThemePreviewCase("05_teal", "青绿色", "#14B8A6"),
            ThemePreviewCase("06_neutral_gray", "低饱和灰色", "#808080"),
            ThemePreviewCase("07_dark_mix", "暗色渐变", DARK_PREVIEW_THEME_COLORS),
            ThemePreviewCase("08_multi_pink_cyan", "粉青双色", "#FE65A6;#BFFAFF"),
        )
    }

    private fun avatarThemePreviewCases(): List<AvatarThemePreviewCase> {
        val extractor = AvatarThemeExtractor { media ->
            loadTestResource("image", media.uri.removePrefix(TEST_IMAGE_PREFIX)).readBytes()
        }
        return avatarThemeFiles().mapIndexed { index, avatarFile ->
            val colors = extractor.extractColors(imageMedia(avatarFile, MediaKind.AVATAR))
            AvatarThemePreviewCase(
                fileName = "%02d_%s".format(index + 1, avatarFile.substringBeforeLast('.')),
                title = "$avatarFile 自动主题",
                avatarFile = avatarFile,
                colors = colors?.joinToString(";") ?: "#FE65A6",
                extracted = colors != null,
            )
        }
    }

    private fun avatarThemeFiles(): List<String> {
        return listOf("avatar.jpg", "avatar1.jpg") + (3..11).map { "avatar$it.jpg" }
    }

    private fun previewLayouts(): List<String> {
        val configured = System.getProperty(PREVIEW_LAYOUTS_PROPERTY)
            ?: System.getenv(PREVIEW_LAYOUTS_ENV)
            ?: return defaultPreviewLayouts
        return configured
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { defaultPreviewLayouts }
    }

    private fun previewFileName(layout: String, fileName: String): String {
        val safeLayout = layout.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "${safeLayout}_$fileName"
    }

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
        val img = when (update.payload) {
            is DynamicPayload -> renderDynamicImage(update = update, config = config)
            is LivePayload -> renderLiveImage(update = update, config = config)
        }
        testOutput.resolve(fileName).writeBytes(img.encodeToData()!!.bytes)
    }

    private fun renderLinkPreviewToOutput(
        fileName: String,
        preview: LinkPreview,
        config: DrawConfig = drawConfig(),
    ) {
        renderToOutput(
            fileName = fileName,
            update = buildLinkPreviewSourceUpdate(
                preview = preview,
                occurredAtEpochSeconds = 1L,
            ),
            config = config,
        )
    }

    private fun renderLiveToOutput(
        fileName: String,
        update: SourceUpdate,
        config: DrawConfig = drawConfig(),
    ) {
        renderToOutput(fileName = fileName, update = update, config = config)
    }

    private fun renderThemePalettePreviewToOutput() {
        View(
            file = testOutput.resolve("theme_palette_preview.png"),
            modifier = Modifier()
                .width(1200.dp)
                .padding(22.dp)
                .background(Color.makeRGB(246, 248, 252)),
        ) {
            Column(modifier = Modifier().fillMaxWidth()) {
                Text(
                    text = "主题色生成预览",
                    fontSize = 40.dp,
                    color = Color.BLACK,
                    modifier = Modifier().margin(bottom = 8.dp),
                )
                Text(
                    text = "每一行展示输入主题色生成后的背景、卡片、强调色、正文链接和二维码点色。",
                    fontSize = 24.dp,
                    color = Color.makeRGB(82, 88, 100),
                    modifier = Modifier().margin(bottom = 18.dp),
                )
                themePreviewCases().forEach { themeCase ->
                    drawThemePaletteCase(themeCase)
                }
            }
        }
    }

    private fun renderAvatarThemePalettePreviewToOutput(avatarCases: List<AvatarThemePreviewCase>) {
        View(
            file = testOutput.resolve("avatar_theme_palette_preview.png"),
            modifier = Modifier()
                .width(1380.dp)
                .padding(22.dp)
                .background(Color.makeRGB(246, 248, 252)),
        ) {
            Column(modifier = Modifier().fillMaxWidth()) {
                Text(
                    text = "头像自动主题预览",
                    fontSize = 40.dp,
                    color = Color.BLACK,
                    modifier = Modifier().margin(bottom = 8.dp),
                )
                Text(
                    text = "每一行展示头像、提取出的主题色，以及这些颜色映射到绘图主题后的关键色块。",
                    fontSize = 24.dp,
                    color = Color.makeRGB(82, 88, 100),
                    modifier = Modifier().margin(bottom = 18.dp),
                )
                avatarCases.forEach { avatarCase ->
                    drawAvatarThemePaletteCase(avatarCase)
                }
            }
        }
    }

    private fun Layout.drawAvatarThemePaletteCase(avatarCase: AvatarThemePreviewCase) {
        val theme = DrawThemeFactory.fromThemeColorText(avatarCase.colors)
        Column(
            modifier = Modifier()
                .fillMaxWidth()
                .margin(bottom = 18.dp)
                .padding(14.dp)
                .background(
                    gradient = Gradient(
                        LayoutAlignment.LEFT,
                        LayoutAlignment.RIGHT,
                        theme.backgroundColors,
                    ),
                )
                .border(2.dp, 16.dp, theme.borderColor),
        ) {
            Row(
                modifier = Modifier()
                    .fillMaxWidth()
                    .padding(14.dp)
                    .background(theme.cardColor)
                    .border(2.dp, 12.dp, theme.borderColor),
            ) {
                Image(
                    image = loadTestImage("image", avatarCase.avatarFile),
                    ratio = Ratio.SQUARE,
                    modifier = Modifier()
                        .width(92.dp)
                        .height(92.dp)
                        .margin(right = 16.dp)
                        .border(2.dp, 46.dp, theme.borderColor),
                )
                Column(modifier = Modifier().fillMaxWidth()) {
                    Text(
                        text = "${avatarCase.title}  ${avatarCase.colors}  ${theme.mode.name}" +
                            if (avatarCase.extracted) "" else "  未提取，使用默认色预览",
                        fontSize = 24.dp,
                        color = theme.textColor,
                        modifier = Modifier().fillMaxWidth().margin(bottom = 12.dp),
                    )
                    Row(modifier = Modifier().fillMaxWidth()) {
                        drawThemeSwatch("媒体标签", theme.primaryColor, theme.onPrimaryColor, "primary")
                        drawThemeSwatch("可读强调", theme.readableAccentColor, contrastTextColor(theme.readableAccentColor), "readable")
                        drawThemeSwatch("二维码点", theme.qrPointColor, contrastTextColor(theme.qrPointColor), "qrPoint")
                        drawThemeSwatch("卡片底色", theme.cardColor, theme.textColor, "card")
                        drawThemeSwatch("主文本", theme.textColor, contrastTextColor(theme.textColor), "text")
                        drawThemeSwatch("次级文本", theme.secondaryTextColor, contrastTextColor(theme.secondaryTextColor), "secondary")
                    }
                }
            }
        }
    }

    private fun Layout.drawThemePaletteCase(themeCase: ThemePreviewCase) {
        val theme = DrawThemeFactory.fromThemeColorText(themeCase.colors)
        Column(
            modifier = Modifier()
                .fillMaxWidth()
                .margin(bottom = 18.dp)
                .padding(14.dp)
                .background(
                    gradient = Gradient(
                        LayoutAlignment.LEFT,
                        LayoutAlignment.RIGHT,
                        theme.backgroundColors,
                    ),
                )
                .border(2.dp, 16.dp, theme.borderColor),
        ) {
            Column(
                modifier = Modifier()
                    .fillMaxWidth()
                    .padding(14.dp)
                    .background(theme.cardColor)
                    .border(2.dp, 12.dp, theme.borderColor),
            ) {
                Row(modifier = Modifier().fillMaxWidth().margin(bottom = 12.dp)) {
                    Text(
                        text = "${themeCase.title}  ${themeCase.colors}  ${theme.mode.name}",
                        fontSize = 25.dp,
                        color = theme.textColor,
                        modifier = Modifier().fillMaxWidth(),
                    )
                }

                Row(modifier = Modifier().fillMaxWidth().margin(bottom = 12.dp)) {
                    drawThemeSwatch("媒体标签", theme.primaryColor, theme.onPrimaryColor, "primary")
                    drawThemeSwatch("可读强调", theme.readableAccentColor, contrastTextColor(theme.readableAccentColor), "readable")
                    drawThemeSwatch("二维码点", theme.qrPointColor, contrastTextColor(theme.qrPointColor), "qrPoint")
                    drawThemeSwatch("卡片底色", theme.cardColor, theme.textColor, "card")
                    drawThemeSwatch("主文本", theme.textColor, contrastTextColor(theme.textColor), "text")
                    drawThemeSwatch("次级文本", theme.secondaryTextColor, contrastTextColor(theme.secondaryTextColor), "secondary")
                }

                Row(modifier = Modifier().fillMaxWidth()) {
                    Text(
                        text = "正文示例：主题色用于长文本时优先保证阅读。",
                        fontSize = 24.dp,
                        color = theme.textColor,
                    )
                    Text(
                        text = " #话题链接#",
                        fontSize = 24.dp,
                        color = theme.linkColor,
                        modifier = Modifier().margin(left = 8.dp),
                    )
                    Text(
                        text = "  弱化信息",
                        fontSize = 24.dp,
                        color = theme.mutedTextColor,
                        modifier = Modifier().margin(left = 8.dp),
                    )
                }
            }
        }
    }

    private fun Layout.drawThemeSwatch(
        title: String,
        color: Int,
        textColor: Int,
        fieldName: String,
    ) {
        Column(
            modifier = Modifier()
                .width(170.dp)
                .margin(right = 10.dp),
        ) {
            Box(
                alignment = LayoutAlignment.CENTER,
                modifier = Modifier()
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(color)
                    .border(1.dp, 8.dp, contrastTextColor(color).withAlpha(0.22f)),
            ) {
                Text(
                    text = title,
                    fontSize = 20.dp,
                    color = textColor,
                    alignment = LayoutAlignment.CENTER,
                    modifier = Modifier().fillMaxWidth(),
                )
            }
            Text(
                text = "$fieldName ${toHexColor(color)}",
                fontSize = 15.dp,
                color = Color.makeRGB(92, 98, 110),
                maxLinesCount = 1,
                modifier = Modifier().margin(top = 4.dp).fillMaxWidth(),
            )
        }
    }

    private fun contrastTextColor(color: Int): Int {
        return if (relativeLuminance(color) > 0.46) Color.BLACK else Color.WHITE
    }

    private fun drawConfig(
        layout: String = "default",
        ornament: DrawOrnament = DrawOrnament.LOGO,
        themeColors: String = "#FE65A6",
    ): DrawConfig {
        return DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "哔哩哔哩"),
            settings = DrawSettings(
                layout = layout,
                ornament = ornament,
                themeColors = themeColors,
            ),
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
