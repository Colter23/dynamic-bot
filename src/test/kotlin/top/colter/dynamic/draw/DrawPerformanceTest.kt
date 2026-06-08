package top.colter.dynamic.draw

import kotlin.math.roundToLong
import kotlin.system.measureNanoTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import java.io.File
import top.colter.dynamic.DrawOrnament
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.DynamicBlockRole
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNode
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeText
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
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.mediaReferences
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.loadTestResource
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey
import top.colter.skiko.Dp
import top.colter.skiko.Fonts
import top.colter.skiko.data.Ratio

private const val TEST_IMAGE_PREFIX = "test://perf/image/"
private const val TEST_EMOJI_PREFIX = "test://perf/emoji/"
private const val ITERATIONS_PROPERTY = "dynamic.draw.perf.iterations"
private const val WARMUPS_PROPERTY = "dynamic.draw.perf.warmups"
private const val LAYOUTS_PROPERTY = "dynamic.draw.perf.layouts"
private const val THEMES_PROPERTY = "dynamic.draw.perf.themes"
private const val INCLUDE_ENCODE_PROPERTY = "dynamic.draw.perf.encode"
private const val DARK_THEME_COLORS = "#101624;#24182D;#0D2630"

class DrawPerformanceTest {

    private var blackhole: Long = 0L

    @BeforeTest
    fun init() {
        Dp.factor = 1f
        Fonts.default.loadTextTypeface(loadTestResource("font", "HarmonyOS_SansSC_Medium.ttf").absolutePath)
        Fonts.default.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `measure draw render time`() {
        val warmups = intProperty(WARMUPS_PROPERTY, 2)
        val iterations = intProperty(ITERATIONS_PROPERTY, 5)
        val includeEncode = booleanProperty(INCLUDE_ENCODE_PROPERTY, false)
        val layouts = listProperty(LAYOUTS_PROPERTY, listOf("default", "minimal"))
        val themes = themeCases()
        val cases = performanceCases()
        val reportLines = mutableListOf<String>()

        fun log(line: String) {
            println(line)
            reportLines += line
        }

        cases.forEach { cacheMedia(it.update) }

        log("dynamic draw performance: warmups=$warmups iterations=$iterations mode=${if (includeEncode) "render+encode" else "render"}")
        log("layout   theme  case                    avg(ms)   min(ms)   max(ms)   size")

        layouts.forEach { layout ->
            themes.forEach { theme ->
                cases.forEach { case ->
                    val config = drawConfig(
                        layout = layout,
                        ornament = case.ornament,
                        themeColors = theme.colors,
                    )
                    repeat(warmups) { consume(render(case.update, config), includeEncode) }

                    val times = LongArray(iterations)
                    var width = 0
                    var height = 0
                    repeat(iterations) { index ->
                        times[index] = measureNanoTime {
                            val image = render(case.update, config)
                            width = image.width
                            height = image.height
                            consume(image, includeEncode)
                        }
                    }

                    log(
                        "%-8s %-6s %-23s %8.2f %8.2f %8.2f %dx%d".format(
                            layout,
                            theme.name,
                            case.name,
                            times.averageMs(),
                            times.minOrNull()!!.toMs(),
                            times.maxOrNull()!!.toMs(),
                            width,
                            height,
                        )
                    )
                }
            }
        }
        log("blackhole=$blackhole")
        writeReport(reportLines)
    }

    private fun performanceCases(): List<PerformanceCase> {
        val origin = testDynamicUpdate(
            publisher = publisher(
                id = "perf-origin",
                name = "原动态作者",
                header = "header1.png",
                withPendant = false,
            ),
            externalId = "perf-origin",
            payload = DynamicPayload(
                title = "原动态内容",
                blocks = listOf(
                    richTextBlock(
                        text("这是一条用于性能测试的转发源动态，包含一段中文正文、一个表情 "),
                        emoji("[热词系列_知识增加]"),
                        text("，以及一个附加卡片。"),
                    ),
                    articleCard(
                        id = "perf-origin-card",
                        title = "原动态里的附加卡片",
                        description = "用于观察转发动态中嵌套内容的绘图耗时。",
                        style = MediaCardStyle.SMALL,
                    ),
                ),
            ),
        )

        return listOf(
            PerformanceCase(
                name = "long_text_images",
                update = testDynamicUpdate(
                    publisher = publisher(
                        id = "perf-long",
                        name = "长文记录本",
                        header = "header1.png",
                    ),
                    externalId = "perf-long-text-images",
                    payload = DynamicPayload(
                        title = "长文字和多图的阅读密度观察",
                        blocks = listOf(
                            richTextBlock(text(longChineseText())),
                            imageGrid(9),
                        ),
                    ),
                ),
            ),
            PerformanceCase(
                name = "text_images_card",
                update = testDynamicUpdate(
                    publisher = publisher(
                        id = "perf-images-card",
                        name = "云边观察员",
                        header = "header2.png",
                        avatar = "avatar1.jpg",
                    ),
                    externalId = "perf-text-images-card",
                    payload = DynamicPayload(
                        blocks = listOf(
                            richTextBlock(
                                text("今天整理了一组绘图模块的组合预览，主要看正文、图片和附加卡片放在一起时的节奏。"),
                                emoji("[阿库娅_不关我事]"),
                                text(" 图片之间的间距、正文和卡片之间的留白都需要一起观察。"),
                            ),
                            imageGrid(4),
                            articleCard(
                                id = "perf-extra-article",
                                title = "动态转发工具排版记录",
                                description = "记录默认布局、minimal 布局、主题色和媒体卡片联动效果。",
                                style = MediaCardStyle.SMALL,
                                role = DynamicBlockRole.ADDITIONAL,
                            ),
                        ),
                    ),
                ),
                ornament = DrawOrnament.QRCODE,
            ),
            PerformanceCase(
                name = "repost",
                update = testDynamicUpdate(
                    publisher = publisher(
                        id = "perf-repost",
                        name = "转发观察员",
                        header = "header2.png",
                    ),
                    externalId = "perf-repost",
                    payload = DynamicPayload(
                        blocks = listOf(
                            richTextBlock(
                                text("转发一下这个版本的效果，重点看原动态作者栏和正文之间的距离。"),
                                mention("原动态作者"),
                            ),
                            RepostBlock(
                                referenceKind = DynamicReferenceKind.ORIGIN,
                                key = origin.key,
                                link = origin.link,
                                embedded = origin,
                            ),
                        ),
                    ),
                ),
            ),
            PerformanceCase(
                name = "live_open",
                update = testDynamicUpdate(
                    publisher = publisher(
                        id = "perf-live",
                        name = "星河直播间",
                        header = "header1.png",
                        avatar = "avatar1.jpg",
                        withPendant = false,
                    ),
                    externalId = "perf-live-open",
                    payload = LivePayload(
                        roomId = "23058",
                        title = "今晚八点一起看动态绘图效果",
                        area = "绘图 / 开发杂谈",
                        cover = imageMedia("bg1.jpg", MediaKind.COVER),
                        status = LiveStatus.OPEN,
                        startedAtEpochSeconds = 1L,
                    ),
                ).copy(link = "https://live.bilibili.com/23058"),
                ornament = DrawOrnament.QRCODE,
            ),
        )
    }

    private fun render(update: SourceUpdate, config: DrawConfig) = when (update.payload) {
        is DynamicPayload -> renderDynamicImage(update, config)
        is LivePayload -> renderLiveImage(update, config)
    }

    private fun consume(image: org.jetbrains.skia.Image, includeEncode: Boolean) {
        blackhole += image.width.toLong() * 31L + image.height
        if (includeEncode) {
            blackhole += image.encodeToData()?.bytes?.size?.toLong() ?: 0L
        }
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

    private fun publisher(
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
                    badge = "图$index".takeIf { count > 1 },
                )
            },
        )
    }

    private fun articleCard(
        id: String,
        title: String,
        description: String,
        style: MediaCardStyle,
        role: DynamicBlockRole = DynamicBlockRole.BODY,
    ): MediaCardBlock {
        return MediaCardBlock(
            style = style,
            role = role,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.ARTICLE,
                sourceKind = "专栏",
                id = id,
                title = title,
                description = description,
                badge = "专栏",
                cover = imageMedia("bg1.jpg", MediaKind.COVER),
                coverRatio = Ratio.BANNER,
            ),
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

    private fun themeCases(): List<ThemeCase> {
        return listProperty(THEMES_PROPERTY, listOf("light")).map {
            when (it) {
                "dark" -> ThemeCase("dark", DARK_THEME_COLORS)
                else -> ThemeCase("light", "#FE65A6")
            }
        }
    }

    private fun listProperty(name: String, default: List<String>): List<String> {
        return System.getProperty(name)
            ?.split(',', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { default }
            ?: default
    }

    private fun intProperty(name: String, default: Int): Int {
        return System.getProperty(name)?.toIntOrNull()?.coerceAtLeast(1) ?: default
    }

    private fun booleanProperty(name: String, default: Boolean): Boolean {
        return System.getProperty(name)?.toBooleanStrictOrNull() ?: default
    }

    private fun writeReport(lines: List<String>) {
        val report = File("build/reports/draw-performance.txt")
        report.parentFile.mkdirs()
        report.writeText(lines.joinToString(separator = System.lineSeparator()) + System.lineSeparator())
    }

    private fun Long.toMs(): Double = this / 1_000_000.0

    private fun LongArray.averageMs(): Double {
        return if (isEmpty()) 0.0 else (sum().toDouble() / size / 1_000_000.0 * 100.0).roundToLong() / 100.0
    }

    private fun longChineseText(): String {
        return "看到这个评论就想简单讲一下，其实也没啥特别的技巧，毛坯买的是一米三玉米须万用，为了还原立绘那种长发。" +
            "做法就是前面的头发再烫一遍很蓬松的玉米须，只有烫蓬松了才能做出来层次感。然后分区开始做刘海，" +
            "先分三大块最后再做层次细分。我比较喜欢保留一点毛流感，所以需要两侧刘海要打薄，这样做出来有层次感。" +
            "其实原图没这么层次感，只是做毛的时候喜欢加一点自己理解进去。做中间这一缕，要想有层次就要从这这块最底下这层发排取一部分作为侧面刘海收进去，" +
            "其实我中间刘海发量取多了有点挡脸，而且鬓角做多了，其实不用那么多。边做边喷一点水，过会再喷发胶，还没干的时候用手掐用尖尾梳弄出一点纹理来都可以。" +
            "后面头发做了一点防炸，但是拍到后面也炸了。还得再研究下防炸方法。当然我也没什么技巧，纯属为了省钱自学的，可能有些方法并不是很主流。"
    }

    private data class PerformanceCase(
        val name: String,
        val update: SourceUpdate,
        val ornament: DrawOrnament = DrawOrnament.LOGO,
    )

    private data class ThemeCase(
        val name: String,
        val colors: String,
    )
}
