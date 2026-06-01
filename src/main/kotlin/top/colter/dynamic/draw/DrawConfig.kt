package top.colter.dynamic.draw

import kotlinx.serialization.Serializable
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.draw.resource.DrawFonts
import top.colter.dynamic.draw.resource.EmptyPlatformDrawAssetResolver
import top.colter.dynamic.draw.resource.PlatformDrawAssetResolver
import top.colter.skiko.FontRegistry
import top.colter.skiko.Fonts
import top.colter.skiko.withAlpha
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

public fun interface DynamicImageResolver {
    public fun image(image: MediaRef): Image
}

public data class DrawConfig(
    val platform: PlatformDescriptor,
    val settings: DrawSettings = DrawSettings(),
    val theme: DrawTheme = DrawThemeFactory.fromSettings(settings),
    val imageResolver: DynamicImageResolver = DynamicImageCache,
    val assetResolver: PlatformDrawAssetResolver = EmptyPlatformDrawAssetResolver,
    val fontRegistry: FontRegistry = Fonts.default,
) {
    init {
        DrawFonts.ensureDefaultFonts(fontRegistry, settings.font)
    }

    public fun image(image: MediaRef): Image = imageResolver.image(image)

    public fun platformAssetImage(key: String, width: Int? = null, height: Int? = null): Image? {
        return assetResolver.image(platform.id, key, width, height)
    }
}

public enum class DrawThemeMode {
    LIGHT,
    DARK,
}

public data class DrawTheme(
    val mode: DrawThemeMode,
    val primaryColor: Int,
    val backgroundColors: List<Int>,
    val cardColor: Int,
    val borderColor: Int,
    val textColor: Int,
    val secondaryTextColor: Int,
    val mutedTextColor: Int,
    val linkColor: Int,
) {
    public fun toPalette(): DrawThemePalette = DrawThemePalette(
        mode = mode,
        primaryColor = toHexColor(primaryColor),
        backgroundColors = backgroundColors.map(::toHexColor),
        cardColor = toHexColor(cardColor),
        borderColor = toHexColor(borderColor),
        textColor = toHexColor(textColor),
        secondaryTextColor = toHexColor(secondaryTextColor),
        mutedTextColor = toHexColor(mutedTextColor),
        linkColor = toHexColor(linkColor),
    )
}

@Serializable
public data class DrawThemePalette(
    val mode: DrawThemeMode,
    val primaryColor: String,
    val backgroundColors: List<String>,
    val cardColor: String,
    val borderColor: String,
    val textColor: String,
    val secondaryTextColor: String,
    val mutedTextColor: String,
    val linkColor: String,
) {
    public fun toTheme(): DrawTheme = DrawTheme(
        mode = mode,
        primaryColor = parseHexColor(primaryColor),
        backgroundColors = backgroundColors.map(::parseHexColor),
        cardColor = parseHexColor(cardColor),
        borderColor = parseHexColor(borderColor),
        textColor = parseHexColor(textColor),
        secondaryTextColor = parseHexColor(secondaryTextColor),
        mutedTextColor = parseHexColor(mutedTextColor),
        linkColor = parseHexColor(linkColor),
    )
}

public object DrawThemeFactory {
    private const val MAX_THEME_COLORS: Int = 5
    private const val MIN_LINK_CONTRAST: Double = 4.5

    public fun fromSettings(settings: DrawSettings): DrawTheme {
        return fromColors(parseThemeColors(settings.themeColors))
    }

    public fun fromThemeColorText(value: String): DrawTheme {
        return fromColors(parseThemeColors(value))
    }

    public fun fromColors(colors: List<String>): DrawTheme {
        require(colors.isNotEmpty()) { "主题色不能为空" }
        require(colors.size <= MAX_THEME_COLORS) { "主题色最多支持 $MAX_THEME_COLORS 个" }

        val sourceColors = colors.map(::parseHexColor)
        val mode = detectMode(sourceColors)
        val backgroundColors = if (sourceColors.size == 1) {
            gradientFromSingleColor(sourceColors.single(), mode)
        } else {
            sourceColors.map { normalizeBackgroundColor(it, mode) }
        }
        val cardBase = if (mode == DrawThemeMode.LIGHT) Color.WHITE else Color.makeRGB(30, 34, 42)
        val primary = primaryAccentColor(sourceColors.first(), mode)
        val link = readableAccentColor(sourceColors.first(), mode, cardBase)

        return DrawTheme(
            mode = mode,
            primaryColor = primary,
            backgroundColors = backgroundColors,
            cardColor = if (mode == DrawThemeMode.LIGHT) Color.WHITE.withAlpha(0.76f) else Color.makeRGB(19, 23, 31).withAlpha(0.68f),
            borderColor = if (mode == DrawThemeMode.LIGHT) Color.WHITE.withAlpha(0.88f) else Color.WHITE.withAlpha(0.18f),
            textColor = if (mode == DrawThemeMode.LIGHT) Color.BLACK else Color.WHITE,
            secondaryTextColor = if (mode == DrawThemeMode.LIGHT) Color.BLACK.withAlpha(0.72f) else Color.WHITE.withAlpha(0.78f),
            mutedTextColor = if (mode == DrawThemeMode.LIGHT) Color.makeRGB(112, 118, 128) else Color.makeRGB(178, 186, 198),
            linkColor = link,
        )
    }

    public fun parseThemeColors(value: String): List<String> {
        val parts = value.split(';').map { it.trim() }
        require(parts.isNotEmpty() && parts.any { it.isNotEmpty() }) { "主题色不能为空" }
        require(parts.none { it.isEmpty() }) { "主题色列表不能包含空项" }
        require(parts.size <= MAX_THEME_COLORS) { "主题色最多支持 $MAX_THEME_COLORS 个" }
        parts.forEach(::requireHexColor)
        return parts
    }

    private fun detectMode(colors: List<Int>): DrawThemeMode {
        val averageLightness = colors.map { it.toHsl().l }.average()
        val averageBrightness = colors.map { it.toHsv().v }.average()
        return if (averageLightness >= 0.42 || averageBrightness >= 0.58) {
            DrawThemeMode.LIGHT
        } else {
            DrawThemeMode.DARK
        }
    }

    private fun gradientFromSingleColor(color: Int, mode: DrawThemeMode): List<Int> {
        val hsv = color.toHsv()
        val hues = listOf(hsv.h - 30.0, hsv.h, hsv.h + 30.0)
        if (mode == DrawThemeMode.LIGHT) {
            return hues.map { hue -> HsvColor(hue, 0.30, 1.0).toColor() }
        }
        val saturation = hsv.s.coerceIn(0.34, 0.52)
        val brightness = (hsv.v * 0.32 + 0.16).coerceIn(0.16, 0.34)
        return hues.map { hue -> HsvColor(hue, saturation, brightness).toColor() }
    }

    private fun normalizeBackgroundColor(color: Int, mode: DrawThemeMode): Int {
        val hsv = color.toHsv()
        if (mode == DrawThemeMode.LIGHT) {
            return HsvColor(hsv.h, 0.30, 1.0).toColor()
        }
        return HsvColor(
            h = hsv.h,
            s = hsv.s.coerceIn(0.34, 0.52),
            v = (hsv.v * 0.32 + 0.16).coerceIn(0.16, 0.34),
        ).toColor()
    }

    private fun primaryAccentColor(color: Int, mode: DrawThemeMode): Int {
        val hsl = color.toHsl()
        return HslColor(
            h = hsl.h,
            s = hsl.s.coerceIn(0.38, 0.68),
            l = if (mode == DrawThemeMode.LIGHT) 0.60 else 0.76,
        ).toColor()
    }

    private fun readableAccentColor(color: Int, mode: DrawThemeMode, cardBase: Int): Int {
        val hsl = color.toHsl()
        val candidate = HslColor(
            h = hsl.h,
            s = hsl.s.coerceIn(0.45, 0.72),
            l = if (mode == DrawThemeMode.LIGHT) 0.36 else 0.72,
        ).toColor()
        return ensureContrast(
            color = candidate,
            background = cardBase,
            minRatio = MIN_LINK_CONTRAST,
            lighten = mode == DrawThemeMode.DARK,
            fallback = if (mode == DrawThemeMode.LIGHT) Color.makeRGB(0, 93, 153) else Color.makeRGB(145, 216, 255),
        )
    }
}

public fun parseHexColor(value: String): Int {
    requireHexColor(value)
    val text = value.trim()
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

public fun toHexColor(color: Int): String {
    val alpha = color.alpha()
    val rgb = "%02X%02X%02X".format(color.red(), color.green(), color.blue())
    return if (alpha == 255) "#$rgb" else "#%02X%s".format(alpha, rgb)
}

private fun requireHexColor(value: String) {
    val text = value.trim()
    require(text.startsWith("#")) { "颜色必须以 # 开头：$value" }
    require(text.length == 7 || text.length == 9) { "颜色必须是 #RRGGBB 或 #AARRGGBB：$value" }
    require(text.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        "颜色包含非法字符：$value"
    }
}

private data class HslColor(
    val h: Double,
    val s: Double,
    val l: Double,
) {
    fun toColor(): Int {
        val c = (1.0 - abs(2.0 * l - 1.0)) * s
        val hPrime = ((h % 360.0) + 360.0) % 360.0 / 60.0
        val x = c * (1.0 - abs(hPrime % 2.0 - 1.0))
        val (r1, g1, b1) = when {
            hPrime < 1 -> Triple(c, x, 0.0)
            hPrime < 2 -> Triple(x, c, 0.0)
            hPrime < 3 -> Triple(0.0, c, x)
            hPrime < 4 -> Triple(0.0, x, c)
            hPrime < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2.0
        return Color.makeRGB(
            ((r1 + m) * 255).roundByte(),
            ((g1 + m) * 255).roundByte(),
            ((b1 + m) * 255).roundByte(),
        )
    }
}

private data class HsvColor(
    val h: Double,
    val s: Double,
    val v: Double,
) {
    fun toColor(): Int {
        val normalizedHue = ((h % 360.0) + 360.0) % 360.0
        val c = v * s
        val hPrime = normalizedHue / 60.0
        val x = c * (1.0 - abs(hPrime % 2.0 - 1.0))
        val (r1, g1, b1) = when {
            hPrime < 1 -> Triple(c, x, 0.0)
            hPrime < 2 -> Triple(x, c, 0.0)
            hPrime < 3 -> Triple(0.0, c, x)
            hPrime < 4 -> Triple(0.0, x, c)
            hPrime < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = v - c
        return Color.makeRGB(
            ((r1 + m) * 255).roundByte(),
            ((g1 + m) * 255).roundByte(),
            ((b1 + m) * 255).roundByte(),
        )
    }
}

private fun Int.toHsl(): HslColor {
    val r = red() / 255.0
    val g = green() / 255.0
    val b = blue() / 255.0
    val max = max(r, max(g, b))
    val min = min(r, min(g, b))
    val delta = max - min
    val lightness = (max + min) / 2.0
    if (delta == 0.0) return HslColor(0.0, 0.0, lightness)

    val saturation = delta / (1.0 - abs(2.0 * lightness - 1.0))
    val hue = when (max) {
        r -> 60.0 * (((g - b) / delta) % 6.0)
        g -> 60.0 * (((b - r) / delta) + 2.0)
        else -> 60.0 * (((r - g) / delta) + 4.0)
    }
    return HslColor((hue + 360.0) % 360.0, saturation, lightness)
}

private fun Int.toHsv(): HsvColor {
    val r = red() / 255.0
    val g = green() / 255.0
    val b = blue() / 255.0
    val max = max(r, max(g, b))
    val min = min(r, min(g, b))
    val delta = max - min
    if (delta == 0.0) return HsvColor(0.0, 0.0, max)
    val saturation = if (max == 0.0) 0.0 else delta / max
    val hue = when (max) {
        r -> 60.0 * (((g - b) / delta) % 6.0)
        g -> 60.0 * (((b - r) / delta) + 2.0)
        else -> 60.0 * (((r - g) / delta) + 4.0)
    }
    return HsvColor((hue + 360.0) % 360.0, saturation, max)
}

private fun ensureContrast(
    color: Int,
    background: Int,
    minRatio: Double,
    lighten: Boolean,
    fallback: Int,
): Int {
    var hsl = color.toHsl()
    repeat(24) {
        val current = hsl.toColor()
        if (contrastRatio(current, background) >= minRatio) return current
        hsl = hsl.copy(l = (hsl.l + if (lighten) 0.03 else -0.03).coerceIn(0.08, 0.92))
    }
    return fallback
}

private fun contrastRatio(a: Int, b: Int): Double {
    val lighter = max(relativeLuminance(a), relativeLuminance(b))
    val darker = min(relativeLuminance(a), relativeLuminance(b))
    return (lighter + 0.05) / (darker + 0.05)
}

public fun relativeLuminance(color: Int): Double {
    fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red()) + 0.7152 * channel(color.green()) + 0.0722 * channel(color.blue())
}

private fun Int.alpha(): Int = (this ushr 24) and 0xff
private fun Int.red(): Int = (this ushr 16) and 0xff
private fun Int.green(): Int = (this ushr 8) and 0xff
private fun Int.blue(): Int = this and 0xff
private fun Double.roundByte(): Int = roundToInt().coerceIn(0, 255)
