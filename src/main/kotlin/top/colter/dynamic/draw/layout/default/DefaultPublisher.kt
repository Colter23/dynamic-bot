package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.DrawOrnament
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.layout.default.component.Author
import top.colter.dynamic.draw.layout.default.component.AuthorSmall
import top.colter.dynamic.draw.resource.loadResourceImage
import top.colter.dynamic.draw.resource.qrCode
import top.colter.skiko.Modifier
import top.colter.skiko.dp
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.height
import top.colter.skiko.layout.Layout
import top.colter.skiko.margin
import top.colter.skiko.offset
import top.colter.skiko.withAlpha

internal fun Layout.drawPublisher(
    publisher: PublisherInfo,
    time: String,
    link: String,
    config: DrawConfig,
    mode: DynamicRenderMode,
) {
    val officialImage = publisher.official?.let { loadResourceImage(name = it) }

    if (mode == DynamicRenderMode.FORWARD) {
        AuthorSmall(
            face = config.image(publisher.avatar),
            name = publisher.name,
            time = time,
            badge = officialImage,
            accentColor = config.theme.primaryColor,
            mutedColor = config.theme.mutedTextColor,
            modifier = Modifier()
                .fillMaxWidth()
                .height(50.dp)
                .margin(bottom = 14.dp)
                .offset(y = (-5).dp),
        )
    } else {
        val ornamentImage = when (config.settings.ornament) {
            DrawOrnament.LOGO -> platformLogo(config)
            DrawOrnament.QRCODE -> qrCode(link, config.theme.primaryColor.withAlpha(1f))
            DrawOrnament.NONE -> null
        }

        Author(
            face = config.image(publisher.avatar),
            pendant = publisher.pendant?.let { config.image(it) },
            head = publisherHead(publisher, config),
            ornament = ornamentImage,
            badge = officialImage,
            name = publisher.name,
            time = time,
            modifier = Modifier().fillMaxWidth().height(100.dp),
        )
    }
}

private fun publisherHead(publisher: PublisherInfo, config: DrawConfig): Image {
    return publisher.banner?.let { config.image(it) } ?: platformDefaultHead(config)
}

private val platformHeadCache = ConcurrentHashMap<String, Image>()

private fun platformDefaultHead(config: DrawConfig): Image {
    val platformId = config.platform.id.value.uppercase()
    return loadResourceImage(name = "${platformId}_HEAD.png")
        ?: loadResourceImage(name = "${platformId}_BANNER.png")
        ?: platformHeadCache.getOrPut(defaultHeadCacheKey(config)) {
            makePlatformDefaultHead(config)
        }
}

private fun platformLogo(config: DrawConfig): Image? {
    val platformId = config.platform.id.value.uppercase()
    return loadResourceImage(name = "${platformId}_A.png")
        ?: loadResourceImage(name = "${platformId}_LOGO.png")
        ?: config.platform.icon
            ?.let { config.image(it.copy(kind = MediaKind.IMAGE)) }
        ?: config.platform.homepageUri
            ?.takeIf { it.isNotBlank() }
            ?.let { config.image(MediaRef(uri = it, kind = MediaKind.IMAGE)) }
}

private fun defaultHeadCacheKey(config: DrawConfig): String {
    return listOf(
        config.platform.id.value,
        config.theme.primaryColor,
        config.theme.backgroundColors.joinToString(","),
    ).joinToString(":")
}

private fun makePlatformDefaultHead(config: DrawConfig): Image {
    val width = 1200
    val height = 300
    val platformColor = colorFromPlatform(config.platform.id.value)
    val colors = intArrayOf(
        config.theme.backgroundColors.firstOrNull() ?: platformColor,
        platformColor,
        config.theme.backgroundColors.lastOrNull() ?: config.theme.primaryColor,
    )

    return Surface.makeRasterN32Premul(width, height).apply {
        val gradientPaint = Paint().apply {
            isAntiAlias = true
            shader = Shader.makeLinearGradient(
                x0 = 0f,
                y0 = 0f,
                x1 = width.toFloat(),
                y1 = height.toFloat(),
                colors = colors,
            )
        }
        canvas.drawRect(Rect.makeXYWH(0f, 0f, width.toFloat(), height.toFloat()), gradientPaint)

        val accentPaint = Paint().apply {
            isAntiAlias = true
            color = config.theme.primaryColor.withAlpha(0.24f)
        }
        canvas.drawRect(Rect.makeXYWH(width * 0.58f, 0f, width * 0.42f, height.toFloat()), accentPaint)

        val shadePaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK.withAlpha(0.12f)
        }
        canvas.drawRect(Rect.makeXYWH(0f, height * 0.72f, width.toFloat(), height * 0.28f), shadePaint)
    }.makeImageSnapshot()
}

private fun colorFromPlatform(platformId: String): Int {
    val hash = platformId.hashCode()
    val r = 96 + ((hash ushr 16) and 0x7F)
    val g = 96 + ((hash ushr 8) and 0x7F)
    val b = 96 + (hash and 0x7F)
    return Color.makeRGB(r, g, b)
}
