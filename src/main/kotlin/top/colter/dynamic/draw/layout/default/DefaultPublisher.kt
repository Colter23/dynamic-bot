package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.Color
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.DrawOrnament
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.plugin.PlatformDrawAssetKeys
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.DrawThemeMode
import top.colter.dynamic.draw.layout.default.component.Author
import top.colter.dynamic.draw.layout.default.component.AuthorSmall
import top.colter.dynamic.draw.layout.default.component.defaultAuthorContentStyle
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
    val avatarBadgeImage = publisher.avatarBadgeKey?.let { config.platformAssetImage(it, width = 100, height = 100) }

    if (mode == DynamicRenderMode.FORWARD) {
        AuthorSmall(
            face = config.image(publisher.avatar),
            name = publisher.name,
            time = time,
            badge = avatarBadgeImage,
            accentColor = config.theme.primaryColor,
            mutedColor = config.theme.mutedTextColor,
            modifier = Modifier()
                .fillMaxWidth()
                .height(50.dp)
                .margin(bottom = 14.dp)
                .offset(y = (-5).dp),
        )
    } else {
        val platformLogoImage = platformLogo(config)
        val platformTextLogoImage = platformTextLogo(config)
        val qrCodeImage = if (config.settings.ornament == DrawOrnament.QRCODE && link.isNotBlank()) {
            qrCode(link, config.theme.primaryColor.withAlpha(1f))
        } else {
            null
        }
        val ornamentImage = when (config.settings.ornament) {
            DrawOrnament.LOGO -> platformLogoImage
            DrawOrnament.QRCODE -> platformTextLogoImage ?: platformLogoImage
            DrawOrnament.NONE -> null
        }
        val publisherHeight = defaultAuthorContentStyle(qrCodeImage != null).height

        Author(
            face = config.image(publisher.avatar),
            pendant = publisher.pendant?.let { config.image(it) },
            head = publisherHead(publisher, config),
            ornament = ornamentImage,
            badge = avatarBadgeImage,
            name = publisher.name,
            time = time,
            qrCode = qrCodeImage,
            accentColor = config.theme.primaryColor,
            darkTheme = config.theme.mode == DrawThemeMode.DARK,
            cardHeight = publisherHeight,
            modifier = Modifier().fillMaxWidth(),
        )
    }
}

private fun publisherHead(publisher: PublisherInfo, config: DrawConfig): Image {
    return publisher.banner?.let { config.image(it) } ?: platformDefaultHead(config)
}

private val platformHeadCache = ConcurrentHashMap<String, Image>()

private fun platformDefaultHead(config: DrawConfig): Image {
    return config.platformAssetImage(PlatformDrawAssetKeys.DEFAULT_HEADER)
        ?: platformHeadCache.getOrPut(defaultHeadCacheKey(config)) {
            makePlatformDefaultHead(config)
        }
}

private fun platformLogo(config: DrawConfig): Image? {
    return config.platformAssetImage(PlatformDrawAssetKeys.PRIMARY_LOGO)
}

private fun platformTextLogo(config: DrawConfig): Image? {
    return config.platformAssetImage(PlatformDrawAssetKeys.TEXT_LOGO)
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
    val colors = arrayOf(
        config.theme.backgroundColors.firstOrNull() ?: platformColor,
        platformColor,
        config.theme.backgroundColors.lastOrNull() ?: config.theme.primaryColor,
    ).map { Color4f(it) }.toTypedArray()

    return Surface.makeRasterN32Premul(width, height).apply {
        val gradientPaint = Paint().apply {
            isAntiAlias = true
            shader = Shader.makeLinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                Gradient(
                    Gradient.Colors(
                        colors = colors,
                        positions = null,
                        tileMode = FilterTileMode.CLAMP,
                        colorSpace = ColorSpace.sRGB,
                    )
                ),
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
