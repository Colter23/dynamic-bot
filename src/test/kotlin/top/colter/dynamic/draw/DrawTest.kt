package top.colter.dynamic.draw

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.dynamic.DrawFontSettings
import top.colter.dynamic.DrawOrnament
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.DrawTypographySettings
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
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.mediaReferences
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.plugin.PlatformDrawAssetKeys
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.draw.resource.PlatformDrawAssetResolver
import top.colter.dynamic.loadTestImage
import top.colter.dynamic.loadTestResource
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testOutput
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey
import top.colter.skiko.Dp
import top.colter.skiko.Fonts
import top.colter.skiko.Modifier
import top.colter.skiko.background
import top.colter.skiko.border
import top.colter.skiko.dp
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.height
import top.colter.skiko.margin
import top.colter.skiko.padding
import top.colter.skiko.withAlpha
import top.colter.skiko.width
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.layout.Box
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Image
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Row
import top.colter.skiko.layout.Text
import top.colter.skiko.layout.View

private const val TEST_IMAGE_PREFIX = "test://image/"
private const val TEST_EMOJI_PREFIX = "test://emoji/"
private const val PREVIEW_LAYOUTS_PROPERTY = "dynamic.draw.preview.layouts"
private const val PREVIEW_LAYOUTS_ENV = "DYNAMIC_DRAW_PREVIEW_LAYOUTS"
private const val DEFAULT_THEME_COLORS = "#FE65A6"
private const val DARK_THEME_COLORS = "#101624;#24182D;#0D2630"
private const val DRAW_FONT = "HarmonyOS_SansSC_Medium.ttf" //"HanYiZhongYuanJian.ttf"
private const val EMOJI_FONT = "NotoColorEmoji.ttf"

class DrawTest {

    @BeforeTest
    fun init() {
        Dp.factor = 1.5f
    }

    @Test
    fun `test dynamic previews`() {
        dynamicPreviews().forEach(::renderPreview)
    }

    @Test
    fun `test link and live previews`() {
        linkAndLivePreviews().forEach(::renderPreview)
    }

    @Test
    fun `test theme previews`() {
        renderThemeOverviewToOutput()
        themeDynamicPreviews().forEach(::renderPreview)
    }

    @Test
    fun `draw layout should scale dp factor with draw scale`() {
        val config = drawConfig(
            layout = "default",
            ornament = DrawOrnament.LOGO,
            themeColors = DEFAULT_THEME_COLORS,
        ).let { it.copy(settings = it.settings.copy(scale = 1.5)) }
        val image = renderDynamicImage(titleShortTextDynamic(), config)

        assertEquals(1.5f, Dp.factor)
        assertTrue(image.width > 1000)
    }

    private fun dynamicPreviews(): List<PreviewCase> {
        return listOf(
            PreviewCase(
                fileName = "dynamic_01_title_short_text.png",
                update = titleShortTextDynamic(),
                layout = "default",
                ornament = DrawOrnament.LOGO,
            ),
            PreviewCase(
                fileName = "dynamic_02_text_images_small_card.png",
                update = textImagesSmallCardDynamic(),
                layout = "minimal",
                ornament = DrawOrnament.LOGO,
            ),
            PreviewCase(
                fileName = "dynamic_03_title_images_large_card.png",
                update = titleImagesLargeCardDynamic(),
                layout = "default",
                ornament = DrawOrnament.QRCODE,
            ),
            PreviewCase(
                fileName = "dynamic_04_text_video_extra_card.png",
                update = textVideoExtraCardDynamic(),
                layout = "minimal",
                ornament = DrawOrnament.QRCODE,
            ),
            PreviewCase(
                fileName = "dynamic_05_repost.png",
                update = repostDynamic(),
                layout = "default",
                ornament = DrawOrnament.LOGO,
            ),
            PreviewCase(
                fileName = "dynamic_06_long_text_images_dark.png",
                update = longTextImagesDynamic(),
                layout = "minimal",
                ornament = DrawOrnament.QRCODE,
                themeColors = DARK_THEME_COLORS,
            ),
            PreviewCase(
                fileName = "dynamic_07_emoji_only.png",
                update = emojiOnlyDynamic(),
                layout = "default",
                ornament = DrawOrnament.LOGO,
            ),
        )
    }

    private fun linkAndLivePreviews(): List<PreviewCase> {
        return listOf(
            PreviewCase(
                fileName = "link_01_video.png",
                update = linkPreviewUpdate(videoLinkPreview()),
                layout = "default",
                ornament = DrawOrnament.LOGO,
            ),
            PreviewCase(
                fileName = "link_02_live_room.png",
                update = linkPreviewUpdate(liveLinkPreview()),
                layout = "minimal",
                ornament = DrawOrnament.QRCODE,
            ),
            PreviewCase(
                fileName = "link_03_user_banner.png",
                update = linkPreviewUpdate(userLinkPreview()),
                layout = "default",
                ornament = DrawOrnament.LOGO,
            ),
            PreviewCase(
                fileName = "link_04_long_no_cover.png",
                update = linkPreviewUpdate(longNoCoverLinkPreview()),
                layout = "default",
                ornament = DrawOrnament.QRCODE,
            ),
            PreviewCase(
                fileName = "live_01_open.png",
                update = liveOpenUpdate(),
                layout = "default",
                ornament = DrawOrnament.QRCODE,
            ),
        )
    }

    private fun themeDynamicPreviews(): List<PreviewCase> {
        return listOf(
            PreviewCase(
                fileName = "theme_01_default_pink.png",
                update = themeDynamic("theme-default", "默认粉色主题", "avatar.jpg", "header2.png"),
                layout = "default",
                ornament = DrawOrnament.QRCODE,
                themeColors = DEFAULT_THEME_COLORS,
            ),
            PreviewCase(
                fileName = "theme_02_bright_cyan.png",
                update = themeDynamic("theme-cyan", "亮青色主题", "avatar1.jpg", "header1.png"),
                layout = "default",
                ornament = DrawOrnament.QRCODE,
                themeColors = "#BFFAFF",
            ),
            PreviewCase(
                fileName = "theme_03_dark_head.png",
                update = themeDynamic("theme-dark", "深色主题和亮头图", "avatar8.jpg", "bg1.jpg"),
                layout = "default",
                ornament = DrawOrnament.QRCODE,
                themeColors = DARK_THEME_COLORS,
            ),
            avatarThemePreview("avatar_theme_01_extracted.png", "avatar4.jpg"),
        )
    }

    private fun titleShortTextDynamic(): SourceUpdate {
        return dynamicUpdate(
            id = "preview-title-short-text",
            publisher = previewPublisher(
                id = "short-text-up",
                name = "可可萝优妮-KokoroUni",
                header = "header2.png",
            ),
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

    private fun textImagesSmallCardDynamic(): SourceUpdate {
        return dynamicUpdate(
            id = "preview-text-images-small-card",
            publisher = previewPublisher(
                id = "images-card-up",
                name = "云边观察员",
                header = "header2.png",
                avatar = "avatar1.jpg",
            ),
            payload = DynamicPayload(
                blocks = listOf(
                    richTextBlock(
                        text("今天整理了一组绘图模块的组合预览，主要看正文、图片和附加卡片放在一起时的节奏。"),
                        emoji("[阿库娅_不关我事]"),
                        topic("绘图预览"),
                    ),
                    imageGrid(count = 3),
                    articleCard(
                        id = "small-card",
                        title = "动态转发工具排版记录",
                        description = "记录默认布局、minimal 布局、主题色和媒体卡片联动效果。",
                        style = MediaCardStyle.SMALL,
                    ),
                ),
            ),
        )
    }

    private fun titleImagesLargeCardDynamic(): SourceUpdate {
        return dynamicUpdate(
            id = "preview-title-images-large-card",
            publisher = previewPublisher(
                id = "large-card-up",
                name = "栗子工坊",
                header = "header1.png",
            ),
            payload = DynamicPayload(
                title = "四月绘图更新说明",
                blocks = listOf(
                    richTextBlock(
                        text("这次把标题、正文、图文内容和大媒体卡片放到同一张动态里检查。"),
                        emoji("[热词系列_大展宏兔]"),
                        link("查看完整记录"),
                    ),
                    imageGrid(count = 4),
                    articleCard(
                        id = "large-card",
                        title = "默认布局视觉调整汇总",
                        description = "包含作者卡片、转发动态、二维码区域、媒体卡片和正文自适应字号。",
                        style = MediaCardStyle.LARGE,
                    ),
                ),
            ),
        )
    }

    private fun textVideoExtraCardDynamic(): SourceUpdate {
        return dynamicUpdate(
            id = "preview-text-video-extra-card",
            publisher = previewPublisher(
                id = "video-card-up",
                name = "薄荷放映室",
                header = "header2.png",
                avatar = "avatar1.jpg",
            ),
            payload = DynamicPayload(
                blocks = listOf(
                    richTextBlock(
                        text("新视频已经上传啦，封面、大媒体卡片和迷你附加卡片一起看看效果。"),
                        emoji("[阿库娅_生气]"),
                    ),
                    videoCard(
                        id = "preview-video",
                        title = "默认布局从零打磨到能看的全过程",
                        description = "聊聊绘图模块的设计取舍，以及为什么边距和字号会影响转发动态的阅读体验。",
                        badge = "视频",
                    ),
                    musicCard(
                        id = "preview-music",
                        title = "夜间调试用背景音乐",
                        description = "音乐 · 轻快 · 循环播放",
                    ),
                ),
            ),
        )
    }

    private fun repostDynamic(): SourceUpdate {
        val origin = dynamicUpdate(
            id = "preview-origin-dynamic",
            publisher = previewPublisher(
                id = "origin-up",
                name = "原图发布者",
                header = "header1.png",
                avatar = "avatar1.jpg",
                withPendant = false,
            ),
            payload = DynamicPayload(
                title = "原动态的完整内容",
                blocks = listOf(
                    richTextBlock(
                        text("这是被转发的原始动态，里面有正文、表情和一个小卡片。"),
                        emoji("[热词系列_知识增加]"),
                    ),
                    articleCard(
                        id = "origin-card",
                        title = "原动态里的附加说明",
                        description = "检查嵌套卡片里的作者栏、正文和附加内容是否协调。",
                        style = MediaCardStyle.SMALL,
                    ),
                ),
            ),
        )

        return dynamicUpdate(
            id = "preview-repost-dynamic",
            publisher = previewPublisher(
                id = "repost-up",
                name = "转发观察员",
                header = "header2.png",
            ),
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

    private fun longTextImagesDynamic(): SourceUpdate {
        return dynamicUpdate(
            id = "preview-long-text-images",
            publisher = previewPublisher(
                id = "long-text-up",
                name = "长文记录本",
                header = "header1.png",
            ),
            payload = DynamicPayload(
                title = "长文字和多图的阅读密度观察",
                blocks = listOf(
                    richTextBlock(
                        text("看到这个评论就想简单讲一下，其实也没啥特别的技巧，毛坯买的是一米三玉米须万用，为了还原立绘那种长发。做法就是前面的头发再烫一遍很蓬松的玉米须，只有烫蓬松了才能做出来层次感。\n"),
                        text("然后分区开始做刘海，先分三大块最后再做层次细分。我比较喜欢保留一点毛流感，所以需要两侧刘海要打薄，这样做出来有层次感。边做边喷一点水，过会再喷发胶，还没干的时候用手掐用尖尾梳弄出一点纹理来都可以。"),
                    ),
                    imageGrid(count = 9),
                ),
            ),
        )
    }

    private fun emojiOnlyDynamic(): SourceUpdate {
        return dynamicUpdate(
            id = "preview-emoji-only",
            publisher = previewPublisher(
                id = "emoji-only-up",
                name = "表情观察员",
                header = "header2.png",
            ),
            payload = DynamicPayload(
                blocks = listOf(
                    richTextBlock(
                        emoji("[阿库娅_不关我事]"),
                        emoji("[热词系列_知识增加]"),
                    ),
                ),
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
            description = "这条链接没有封面，所以测试会把标题提到动态标题位置，并把较长的简介作为正文绘制。这样可以观察链接解析结果在没有封面时是否仍然有完整的信息层级：先看到标题，再读到正文，最后通过一个紧凑的小卡片知道它来自哪个页面。",
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

    private fun liveOpenUpdate(): SourceUpdate {
        return dynamicUpdate(
            id = "preview-live-open",
            publisher = previewPublisher(
                id = "live-open-up",
                name = "星河直播间",
                header = "header1.png",
                avatar = "avatar1.jpg",
                withPendant = false,
            ),
            payload = LivePayload(
                roomId = "23058",
                title = "今晚八点一起看动态绘图效果",
                area = "绘图 / 开发杂谈",
                cover = imageMedia("bg1.jpg", MediaKind.COVER),
                status = LiveStatus.OPEN,
                statusText = "直播中",
                startedAtEpochSeconds = 1L,
                metrics = listOf(
                    DynamicMetric(key = "online", raw = 53000, display = "5.3万"),
                    DynamicMetric(key = "follow", raw = 1280000, display = "128万"),
                ),
            ),
        )
    }

    private fun themeDynamic(
        id: String,
        title: String,
        avatar: String,
        header: String,
    ): SourceUpdate {
        return dynamicUpdate(
            id = id,
            publisher = previewPublisher(
                id = id,
                name = "主题观察员",
                header = header,
                avatar = avatar,
                withPendant = false,
            ),
            payload = DynamicPayload(
                title = title,
                blocks = listOf(
                    richTextBlock(
                        text("这条动态用来观察主题色映射后的作者名、正文、链接、媒体标签和二维码区域。"),
                        emoji("[热词系列_知识增加]"),
                        topic("主题预览"),
                    ),
                    imageGrid(count = 2),
                    articleCard(
                        id = "$id-card",
                        title = "主题色效果检查",
                        description = "同一套动态内容搭配不同主题，方便看整体协调度和文字可读性。",
                        style = MediaCardStyle.SMALL,
                    ),
                ),
            ),
        )
    }

    private fun avatarThemePreview(fileName: String, avatarFile: String): PreviewCase {
        val colors = AvatarThemeExtractor { media ->
            loadTestResource("image", media.uri.removePrefix(TEST_IMAGE_PREFIX)).readBytes()
        }.extractColors(imageMedia(avatarFile, MediaKind.AVATAR))?.joinToString(";") ?: DEFAULT_THEME_COLORS

        return PreviewCase(
            fileName = fileName,
            update = themeDynamic(
                id = "avatar-theme-${avatarFile.substringBeforeLast('.')}",
                title = "头像自动主题预览",
                avatar = avatarFile,
                header = "header1.png",
            ),
            layout = "minimal",
            ornament = DrawOrnament.QRCODE,
            themeColors = colors,
        )
    }

    private fun renderThemeOverviewToOutput() {
        val cases = listOf(
            ThemeOverviewCase("默认粉色", DEFAULT_THEME_COLORS, null),
            ThemeOverviewCase("亮青色", "#BFFAFF", null),
            ThemeOverviewCase("暗色渐变", DARK_THEME_COLORS, null),
            ThemeOverviewCase("头像 avatar4", avatarThemeColors("avatar4.jpg"), "avatar4.jpg"),
            ThemeOverviewCase("头像 avatar8", avatarThemeColors("avatar8.jpg"), "avatar8.jpg"),
            ThemeOverviewCase("头像 avatar10", avatarThemeColors("avatar10.jpg"), "avatar10.jpg"),
        )

        View(
            file = testOutput.resolve("theme_overview.png"),
            modifier = Modifier()
                .width(1220.dp)
                .padding(22.dp)
                .background(Color.makeRGB(246, 248, 252)),
        ) {
            Column(modifier = Modifier().fillMaxWidth()) {
                Text(
                    text = "主题效果总览",
                    fontSize = 40.dp,
                    color = Color.BLACK,
                    modifier = Modifier().margin(bottom = 8.dp),
                )
                Text(
                    text = "固定主题和头像提取主题的关键颜色。真实动态效果见 theme_* 输出。",
                    fontSize = 24.dp,
                    color = Color.makeRGB(82, 88, 100),
                    modifier = Modifier().margin(bottom = 18.dp),
                )
                cases.forEach { drawThemeOverviewCase(it) }
            }
        }
    }

    private fun Layout.drawThemeOverviewCase(case: ThemeOverviewCase) {
        val theme = DrawThemeFactory.fromThemeColorText(case.colors)
        Row(
            modifier = Modifier()
                .fillMaxWidth()
                .margin(bottom = 14.dp)
                .padding(12.dp)
                .background(
                    gradient = Gradient(
                        LayoutAlignment.LEFT,
                        LayoutAlignment.RIGHT,
                        theme.backgroundColors,
                    ),
                )
                .border(2.dp, 14.dp, theme.borderColor),
        ) {
            if (case.avatarFile != null) {
                Image(
                    image = loadTestImage("image", case.avatarFile),
                    ratio = Ratio.SQUARE,
                    modifier = Modifier()
                        .width(78.dp)
                        .height(78.dp)
                        .margin(right = 12.dp)
                        .border(2.dp, 39.dp, theme.borderColor),
                )
            }
            Column(
                modifier = Modifier()
                    .fillMaxWidth()
                    .padding(12.dp)
                    .background(theme.cardColor)
                    .border(2.dp, 10.dp, theme.borderColor),
            ) {
                Text(
                    text = "${case.title}  ${case.colors}  ${theme.mode.name}",
                    fontSize = 24.dp,
                    color = theme.textColor,
                    modifier = Modifier().fillMaxWidth().margin(bottom = 10.dp),
                )
                Row(modifier = Modifier().fillMaxWidth()) {
                    drawThemeSwatch("媒体标签", theme.primaryColor, theme.onPrimaryColor, "primary")
                    drawThemeSwatch("可读强调", theme.readableAccentColor, contrastTextColor(theme.readableAccentColor), "readable")
                    drawThemeSwatch("二维码点", theme.qrPointColor, contrastTextColor(theme.qrPointColor), "qrPoint")
                    drawThemeSwatch("卡片底色", theme.cardColor, theme.textColor, "card")
                    drawThemeSwatch("正文", theme.textColor, contrastTextColor(theme.textColor), "text")
                    drawThemeSwatch("次级", theme.secondaryTextColor, contrastTextColor(theme.secondaryTextColor), "secondary")
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
                .width(162.dp)
                .margin(right = 8.dp),
        ) {
            Box(
                alignment = LayoutAlignment.CENTER,
                modifier = Modifier()
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(color)
                    .border(1.dp, 8.dp, contrastTextColor(color).withAlpha(0.22f)),
            ) {
                Text(
                    text = title,
                    fontSize = 19.dp,
                    color = textColor,
                    alignment = LayoutAlignment.CENTER,
                    modifier = Modifier().fillMaxWidth(),
                )
            }
            Text(
                text = "$fieldName ${toHexColor(color)}",
                fontSize = 14.dp,
                color = Color.makeRGB(92, 98, 110),
                maxLinesCount = 1,
                modifier = Modifier().margin(top = 4.dp).fillMaxWidth(),
            )
        }
    }

    private fun avatarThemeColors(avatarFile: String): String {
        return AvatarThemeExtractor { media ->
            loadTestResource("image", media.uri.removePrefix(TEST_IMAGE_PREFIX)).readBytes()
        }.extractColors(imageMedia(avatarFile, MediaKind.AVATAR))?.joinToString(";") ?: DEFAULT_THEME_COLORS
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
            role = DynamicBlockRole.ADDITIONAL,
        )
    }

    private fun videoCard(
        id: String,
        title: String,
        description: String,
        badge: String,
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
            style = MediaCardStyle.LARGE,
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

    private fun dynamicUpdate(
        id: String,
        publisher: PublisherInfo,
        payload: top.colter.dynamic.core.data.SourcePayload,
    ): SourceUpdate {
        return testDynamicUpdate(
            publisher = publisher,
            externalId = id,
            payload = payload,
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

    private fun linkPreviewUpdate(preview: LinkPreview): SourceUpdate {
        return buildLinkPreviewSourceUpdate(
            preview = preview,
            occurredAtEpochSeconds = 1L,
        )
    }

    private fun renderPreview(preview: PreviewCase) {
        previewLayouts(preview.layout).forEach { layout ->
            renderToOutput(
                fileName = previewFileName(layout, preview.fileName),
                update = preview.update,
                config = drawConfig(
                    layout = layout,
                    ornament = preview.ornament,
                    themeColors = preview.themeColors,
                ),
            )
        }
    }

    private fun renderToOutput(
        fileName: String,
        update: SourceUpdate,
        config: DrawConfig,
    ) {
        cacheMedia(update)
        val image = when (update.payload) {
            is DynamicPayload -> renderDynamicImage(update = update, config = config)
            is LivePayload -> renderLiveImage(update = update, config = config)
        }
        testOutput.resolve(fileName).writeBytes(image.encodeToData()!!.bytes)
    }

    private fun previewLayouts(defaultLayout: String): List<String> {
        val configured = System.getProperty(PREVIEW_LAYOUTS_PROPERTY)
            ?: System.getenv(PREVIEW_LAYOUTS_ENV)
            ?: return listOf(defaultLayout)
        return configured
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(defaultLayout) }
    }

    private fun previewFileName(layout: String, fileName: String): String {
        val safeLayout = layout.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "${safeLayout}_$fileName"
    }

    private fun drawConfig(
        layout: String,
        ornament: DrawOrnament,
        themeColors: String,
    ): DrawConfig {
        return DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "哔哩哔哩"),
            settings = DrawSettings(
                layout = layout,
                ornament = ornament,
                themeColors = themeColors,
                font = DrawFontSettings(
                    text = loadTestResource("font", DRAW_FONT).absolutePath,
                    emoji = loadTestResource("font", EMOJI_FONT).absolutePath,
                    typography = DrawTypographySettings(
                        autoNormalize = true,
                        lineHeightScale = 1.0,
                        letterSpacingEm = 0.0,
                    ),
                ),
            ),
            assetResolver = TestPlatformDrawAssetResolver,
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

    private fun contrastTextColor(color: Int): Int {
        return if (relativeLuminance(color) > 0.46) Color.BLACK else Color.WHITE
    }

    private data class PreviewCase(
        val fileName: String,
        val update: SourceUpdate,
        val layout: String,
        val ornament: DrawOrnament,
        val themeColors: String = DEFAULT_THEME_COLORS,
    )

    private data class ThemeOverviewCase(
        val title: String,
        val colors: String,
        val avatarFile: String?,
    )

    private object TestPlatformDrawAssetResolver : PlatformDrawAssetResolver {
        private val imageCache: MutableMap<String, Image> = mutableMapOf()

        override fun image(platformId: PlatformId, key: String, width: Int?, height: Int?): Image? {
            val fileName = when (key) {
                PlatformDrawAssetKeys.PRIMARY_LOGO -> "${platformId.value}_logo_primary.png"
                PlatformDrawAssetKeys.TEXT_LOGO -> "${platformId.value}_logo_wordmark.png"
                else -> return null
            }
            return imageCache.getOrPut(fileName) {
                loadTestImage("image", fileName)
            }
        }
    }
}
