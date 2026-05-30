package top.colter.dynamic.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.draw.resource.DrawFonts
import top.colter.skiko.FontRegistry
import top.colter.skiko.Fonts
import top.colter.skiko.withAlpha

public fun interface DynamicImageResolver {
    public fun image(image: MediaRef): Image
}

data class DrawConfig(
    val platform: PlatformDescriptor,
    val settings: DrawSettings = DrawSettings(),
    val theme: DrawTheme = DrawTheme.from(settings),
    val imageResolver: DynamicImageResolver = DynamicImageCache,
    val fontRegistry: FontRegistry = Fonts.default,
) {
    init {
        DrawFonts.ensureDefaultFonts(fontRegistry, settings.font)
    }

    fun image(image: MediaRef): Image = imageResolver.image(image)
}

data class DrawTheme(
    val primaryColor: Int,
    val backgroundColors: List<Int>,
    val cardColor: Int = Color.WHITE.withAlpha(0.6f),
    val borderColor: Int = Color.WHITE,
    val textColor: Int = Color.BLACK,
    val secondaryTextColor: Int = Color.BLACK.withAlpha(0.7f),
    val mutedTextColor: Int = Color.makeRGB(156, 156, 156),
    val linkColor: Int = Color.makeRGB(23, 139, 207),
) {
    companion object {
        fun from(settings: DrawSettings): DrawTheme {
            return DrawTheme(
                primaryColor = parseHexColor(settings.themeColor),
                backgroundColors = listOf(
                    parseHexColor(settings.backgroundStartColor),
                    parseHexColor(settings.backgroundEndColor),
                ),
            )
        }
    }
}

internal fun parseHexColor(value: String): Int {
    val text = value.trim()
    require(text.startsWith("#")) { "Hex color must start with #: $value" }
    require(text.length == 7 || text.length == 9) { "Hex color must be #RRGGBB or #AARRGGBB: $value" }
    return when (text.length) {
        7 -> Color.makeRGB(
            text.substring(1, 3).toInt(16),
            text.substring(3, 5).toInt(16),
            text.substring(5, 7).toInt(16),
        )
        else -> Color.makeARGB(
            text.substring(1, 3).toInt(16),
            text.substring(3, 5).toInt(16),
            text.substring(5, 7).toInt(16),
            text.substring(7, 9).toInt(16),
        )
    }
}
