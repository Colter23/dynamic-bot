package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.Color
import top.colter.dynamic.draw.DrawTheme
import top.colter.dynamic.draw.DrawThemeMode
import top.colter.dynamic.draw.relativeLuminance
import top.colter.skiko.withAlpha

internal data class MediaCardColors(
    val accentColor: Int = Color.makeRGB(251, 114, 153),
    val cardColor: Int = Color.WHITE.withAlpha(0.6f),
    val borderColor: Int = Color.WHITE,
    val coverBorderColor: Int = Color.WHITE,
    val badgeBorderColor: Int = Color.WHITE.withAlpha(0.55f),
    val badgeTextColor: Int = Color.WHITE,
    val titleColor: Int = Color.BLACK,
    val secondaryTextColor: Int = Color.BLACK.withAlpha(0.7f),
    val overlayColor: Int = Color.BLACK.withAlpha(0.5f),
    val overlayTransparentColor: Int = Color.BLACK.withAlpha(0f),
    val overlayPillColor: Int = Color.BLACK.withAlpha(0.5f),
    val overlayTextColor: Int = Color.WHITE,
)

internal fun mediaCardColors(theme: DrawTheme): MediaCardColors {
    val badgeTextColor = readableTextColor(theme.primaryColor)
    val badgeBorderColor = if (badgeTextColor == Color.WHITE) {
        Color.WHITE.withAlpha(if (theme.mode == DrawThemeMode.DARK) 0.50f else 0.62f)
    } else {
        Color.BLACK.withAlpha(0.20f)
    }
    val overlayAlpha = if (theme.mode == DrawThemeMode.DARK) 0.62f else 0.50f
    val overlayColor = Color.BLACK.withAlpha(overlayAlpha)

    return MediaCardColors(
        accentColor = theme.primaryColor,
        cardColor = theme.cardColor,
        borderColor = theme.borderColor,
        coverBorderColor = theme.borderColor,
        badgeBorderColor = badgeBorderColor,
        badgeTextColor = badgeTextColor,
        titleColor = theme.textColor,
        secondaryTextColor = theme.secondaryTextColor,
        overlayColor = overlayColor,
        overlayTransparentColor = Color.BLACK.withAlpha(0f),
        overlayPillColor = Color.BLACK.withAlpha((overlayAlpha + 0.10f).coerceAtMost(0.74f)),
        overlayTextColor = Color.WHITE,
    )
}

private fun readableTextColor(background: Int): Int {
    return if (relativeLuminance(background) > 0.56) Color.BLACK else Color.WHITE
}
