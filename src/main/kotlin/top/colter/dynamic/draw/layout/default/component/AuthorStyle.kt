package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.Color
import top.colter.dynamic.draw.DrawTheme
import top.colter.dynamic.draw.DrawThemeMode
import top.colter.skiko.Dp
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.GradientBlur
import top.colter.skiko.data.GradientBlurStop
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Shadow
import top.colter.skiko.data.TextShadow
import top.colter.skiko.dp
import top.colter.skiko.withAlpha
import kotlin.math.roundToInt

private val defaultAuthorAccentColor = Color.makeRGB(251, 114, 153)

/**
 * 作者卡片整体样式。
 *
 * default 布局使用完整卡片、头图背景和阴影；minimal 布局只复用 [AuthorContentStyle]。
 */
internal data class AuthorCardStyle(
    val height: Dp,
    val radius: Dp,
    val shadows: List<Shadow>,
    val content: AuthorContentStyle,
    val headBackground: AuthorHeadBackgroundStyle,
)

/**
 * 作者头图背景样式。
 */
internal sealed interface AuthorHeadBackgroundStyle {
    val radius: Dp
}

/**
 * 无主题对象时的旧版头图样式。
 */
internal data class AuthorFallbackHeadBackgroundStyle(
    override val radius: Dp,
    val imageAlpha: Float,
    val gradient: Gradient,
) : AuthorHeadBackgroundStyle

/**
 * 有主题对象时的头图调和样式。
 */
internal data class AuthorThemedHeadBackgroundStyle(
    override val radius: Dp,
    val baseGradientColors: List<Int>,
    val blur: Dp,
    val blurredImageAlpha: Float,
    val originalImageAlpha: Float,
    val tintGradientColors: List<Int>,
    val washColor: Int,
) : AuthorHeadBackgroundStyle

/**
 * 作者内容行样式，不包含外层卡片、背景、圆角和阴影。
 */
internal data class AuthorContentStyle(
    val height: Dp,
    val avatar: AuthorAvatarStyle,
    val text: AuthorTextBlockStyle,
    val decorate: AuthorDecorateStyle,
)

/**
 * 头像区域样式。
 */
internal data class AuthorAvatarStyle(
    val size: Dp,
    val left: Dp,
    val top: Dp,
    val textSpacing: Dp,
)

/**
 * 名称和时间的文字区域样式。
 *
 * [top] 是名称固定行盒的顶部偏移；时间行通过名称行高和 [timeTopSpacing] 推导，
 * 避免描边或阴影增大 Text 自身测量尺寸后反过来撑开布局。
 */
internal data class AuthorTextBlockStyle(
    val top: Dp,
    val rightSpacing: Dp,
    val timeTopSpacing: Dp,
    val name: AuthorTextLineStyle,
    val time: AuthorTextLineStyle,
) {
    val nameTop: Dp get() = top
    val timeTop: Dp get() = top + name.lineHeight + timeTopSpacing
}

/**
 * 单行文字的固定行盒样式。
 */
internal data class AuthorTextLineStyle(
    val left: Dp,
    val fontSize: Dp,
    val lineHeight: Dp,
    val color: Int,
    val strokeWidth: Dp,
    val strokeColor: Int,
    val bleedVertical: Dp,
    val shadows: List<TextShadow>,
)

/**
 * 作者卡片右侧装饰区样式，包含普通 logo 和二维码两种装饰需要的参数。
 */
internal data class AuthorDecorateStyle(
    val qrBackgroundBleedLeft: Dp,
    val qrBackgroundRadius: List<Dp>,
    val qrBackgroundColors: List<Int>,
    val qrLogo: AuthorQrLogoStyle,
)

/**
 * 二维码中心平台 logo 样式。
 */
internal data class AuthorQrLogoStyle(
    val size: Dp,
    val padding: Dp,
    val radius: Dp,
    val outerColor: Int,
    val innerColor: Int,
    val innerPaddingHorizontal: Dp,
    val innerPaddingVertical: Dp,
    val innerRadius: Dp,
    val imagePaddingHorizontal: Dp,
)

internal fun defaultAuthorCardStyle(
    hasQrCode: Boolean,
    theme: DrawTheme? = null,
): AuthorCardStyle {
    val content = defaultAuthorContentStyle(hasQrCode, theme)
    return AuthorCardStyle(
        height = content.height,
        radius = 15.dp,
        shadows = Shadow.ELEVATION_3,
        content = content,
        headBackground = defaultAuthorHeadBackgroundStyle(theme),
    )
}

internal fun defaultAuthorContentStyle(
    hasQrCode: Boolean,
    theme: DrawTheme? = null,
): AuthorContentStyle {
    val accentColor = theme?.primaryColor ?: defaultAuthorAccentColor
    return defaultAuthorContentStyle(
        hasQrCode = hasQrCode,
        accentColor = accentColor,
        darkTheme = theme?.mode == DrawThemeMode.DARK,
    )
}

internal fun defaultAuthorContentStyle(
    hasQrCode: Boolean,
    accentColor: Int,
): AuthorContentStyle {
    return defaultAuthorContentStyle(
        hasQrCode = hasQrCode,
        accentColor = accentColor,
        darkTheme = false,
    )
}

private fun defaultAuthorContentStyle(
    hasQrCode: Boolean,
    accentColor: Int,
    darkTheme: Boolean,
): AuthorContentStyle {
    val nameShadows = listOf(
        TextShadow(
            offsetX = 2.dp,
            offsetY = 3.dp,
            blur = 3.dp,
            color = Color.BLACK.withAlpha(0.3f),
        ),
        TextShadow(
            offsetX = 0.dp,
            offsetY = 0.dp,
            blur = 8.dp,
            color = accentColor.withAlpha(0.8f),
        ),
    )

    return if (hasQrCode) {
        AuthorContentStyle(
            height = 150.dp,
            avatar = AuthorAvatarStyle(
                size = 112.dp,
                left = 30.dp,
                top = 18.dp,
                textSpacing = 18.dp,
            ),
            text = AuthorTextBlockStyle(
                top = 2.dp,
                rightSpacing = 12.dp,
                timeTopSpacing = 10.dp,
                name = AuthorTextLineStyle(
                    left = (-21).dp,
                    fontSize = 50.dp,
                    lineHeight = 80.dp,
                    color = Color.WHITE.withAlpha(0.9f),
                    strokeWidth = 8.dp,
                    strokeColor = accentColor,
                    bleedVertical = 20.dp,
                    shadows = nameShadows,
                ),
                time = AuthorTextLineStyle(
                    left = 0.dp,
                    fontSize = 36.dp,
                    lineHeight = 42.dp,
                    color = Color.WHITE.withAlpha(0.8f),
                    strokeWidth = 5.dp,
                    strokeColor = accentColor.withAlpha(0.6f),
                    bleedVertical = 10.dp,
                    shadows = authorTimeShadows,
                ),
            ),
            decorate = defaultAuthorDecorateStyle(accentColor, darkTheme),
        )
    } else {
        AuthorContentStyle(
            height = 120.dp,
            avatar = AuthorAvatarStyle(
                size = 92.dp,
                left = 30.dp,
                top = 14.dp,
                textSpacing = 18.dp,
            ),
            text = AuthorTextBlockStyle(
                top = (-6).dp,
                rightSpacing = 12.dp,
                timeTopSpacing = 12.dp,
                name = AuthorTextLineStyle(
                    left = (-21).dp,
                    fontSize = 43.dp,
                    lineHeight = 68.dp,
                    color = Color.WHITE.withAlpha(0.9f),
                    strokeWidth = 8.dp,
                    strokeColor = accentColor,
                    bleedVertical = 20.dp,
                    shadows = nameShadows,
                ),
                time = AuthorTextLineStyle(
                    left = 0.dp,
                    fontSize = 32.dp,
                    lineHeight = 38.dp,
                    color = Color.WHITE.withAlpha(0.8f),
                    strokeWidth = 5.dp,
                    strokeColor = accentColor.withAlpha(0.6f),
                    bleedVertical = 10.dp,
                    shadows = authorTimeShadows,
                ),
            ),
            decorate = defaultAuthorDecorateStyle(accentColor, darkTheme),
        )
    }
}

internal fun minimalAuthorContentStyle(
    hasQrCode: Boolean,
    theme: DrawTheme? = null,
): AuthorContentStyle {
    return minimalAuthorContentStyle(
        hasQrCode = hasQrCode,
        shadowAccentColor = theme?.primaryColor ?: defaultAuthorAccentColor,
        paintAccentColor = theme?.readableAccentColor ?: defaultAuthorAccentColor,
        darkTheme = theme?.mode == DrawThemeMode.DARK,
    )
}

internal fun minimalAuthorContentStyle(
    hasQrCode: Boolean,
    accentColor: Int,
): AuthorContentStyle {
    return minimalAuthorContentStyle(
        hasQrCode = hasQrCode,
        shadowAccentColor = accentColor,
        paintAccentColor = accentColor,
        darkTheme = false,
    )
}

private fun minimalAuthorContentStyle(
    hasQrCode: Boolean,
    shadowAccentColor: Int,
    paintAccentColor: Int,
    darkTheme: Boolean,
): AuthorContentStyle {
    val nameShadows = listOf(
        TextShadow(
            offsetX = 0.dp,
            offsetY = 0.dp,
            blur = 8.dp,
            color = shadowAccentColor.withAlpha(0.6f),
        ),
    )

    return if (hasQrCode) {
        AuthorContentStyle(
            height = 122.dp,
            avatar = AuthorAvatarStyle(
                size = 92.dp,
                left = 15.dp,
                top = 16.dp,
                textSpacing = 18.dp,
            ),
            text = AuthorTextBlockStyle(
                top = 8.dp,
                rightSpacing = 12.dp,
                timeTopSpacing = 10.dp,
                name = AuthorTextLineStyle(
                    left = (-20).dp,
                    fontSize = 43.dp,
                    lineHeight = 58.dp,
                    color = Color.WHITE.withAlpha(0.9f),
                    strokeWidth = 5.dp,
                    strokeColor = paintAccentColor,
                    bleedVertical = 20.dp,
                    shadows = nameShadows,
                ),
                time = AuthorTextLineStyle(
                    left = 0.dp,
                    fontSize = 30.dp,
                    lineHeight = 36.dp,
                    color = Color.WHITE.withAlpha(0.8f),
                    strokeWidth = 5.dp,
                    strokeColor = paintAccentColor.withAlpha(0.6f),
                    bleedVertical = 8.dp,
                    shadows = authorTimeShadows,
                ),
            ),
            decorate = minimalAuthorDecorateStyle(paintAccentColor, darkTheme),
        )
    } else {
        AuthorContentStyle(
            height = 92.dp,
            avatar = AuthorAvatarStyle(
                size = 92.dp,
                left = 15.dp,
                top = (-3).dp,
                textSpacing = 18.dp,
            ),
            text = AuthorTextBlockStyle(
                top = (-12).dp,
                rightSpacing = 12.dp,
                timeTopSpacing = 10.dp,
                name = AuthorTextLineStyle(
                    left = (-20).dp,
                    fontSize = 43.dp,
                    lineHeight = 58.dp,
                    color = Color.WHITE.withAlpha(0.9f),
                    strokeWidth = 5.dp,
                    strokeColor = paintAccentColor,
                    bleedVertical = 20.dp,
                    shadows = nameShadows,
                ),
                time = AuthorTextLineStyle(
                    left = 0.dp,
                    fontSize = 30.dp,
                    lineHeight = 36.dp,
                    color = Color.WHITE.withAlpha(0.8f),
                    strokeWidth = 5.dp,
                    strokeColor = paintAccentColor.withAlpha(0.6f),
                    bleedVertical = 8.dp,
                    shadows = authorTimeShadows,
                ),
            ),
            decorate = minimalAuthorDecorateStyle(paintAccentColor, darkTheme),
        )
    }
}

internal fun qrLogo(
    accentColor: Int = defaultAuthorAccentColor,
    darkTheme: Boolean = false
): AuthorQrLogoStyle {
    return AuthorQrLogoStyle(
        size = 40.dp,
        padding = 3.dp,
        radius = 8.dp,
        outerColor = if (darkTheme) Color.BLACK else Color.WHITE,
        innerColor = accentColor,
        innerPaddingHorizontal = 2.dp,
        innerPaddingVertical = 4.dp,
        innerRadius = 6.dp,
        imagePaddingHorizontal = 1.dp,
    )
}

internal fun defaultAuthorDecorateStyle(
    accentColor: Int = defaultAuthorAccentColor,
    darkTheme: Boolean = false,
): AuthorDecorateStyle {
    return AuthorDecorateStyle(
        qrBackgroundBleedLeft = 68.dp,
        qrBackgroundRadius = listOf(0.dp, 15.dp, 15.dp, 0.dp),
        qrBackgroundColors = qrBackgroundColors(accentColor, darkTheme),
        qrLogo = qrLogo(accentColor, darkTheme),
    )
}

internal fun minimalAuthorDecorateStyle(
    accentColor: Int = defaultAuthorAccentColor,
    darkTheme: Boolean = false,
): AuthorDecorateStyle {
    return AuthorDecorateStyle(
        qrBackgroundBleedLeft = 68.dp,
        qrBackgroundRadius = listOf(0.dp, 15.dp, 15.dp, 0.dp),
        qrBackgroundColors = listOf(),
        qrLogo = qrLogo(accentColor, darkTheme),
    )
}

private fun defaultAuthorHeadBackgroundStyle(theme: DrawTheme?): AuthorHeadBackgroundStyle {
    if (theme == null) {
        return AuthorFallbackHeadBackgroundStyle(
            radius = 15.dp,
            imageAlpha = 0.85f,
            gradient = Gradient(
                LayoutAlignment.LEFT,
                LayoutAlignment.RIGHT,
                listOf(
                    Color.BLACK.withAlpha(0.2f),
                    Color.BLACK.withAlpha(0.2f),
                    Color.BLACK.withAlpha(0f),
                    Color.BLACK.withAlpha(0.2f),
                ),
            ),
        )
    }

    val darkTheme = theme.mode == DrawThemeMode.DARK
    return AuthorThemedHeadBackgroundStyle(
        radius = 15.dp,
        baseGradientColors = theme.backgroundColors,
        blur = 5.dp,
        blurredImageAlpha = if (darkTheme) 0.50f else 0.40f,
        originalImageAlpha = if (darkTheme) 0.12f else 0.08f,
        tintGradientColors = listOf(
            theme.primaryColor.withAlpha(if (darkTheme) 0.16f else 0.09f),
            theme.readableAccentColor.withAlpha(if (darkTheme) 0.09f else 0.07f),
            theme.primaryColor.withAlpha(if (darkTheme) 0.10f else 0.07f),
        ),
        washColor = if (darkTheme) {
            Color.makeRGB(8, 12, 22).withAlpha(0.18f)
        } else {
            Color.WHITE.withAlpha(0.22f)
        },
    )
}

private val authorTimeShadows = listOf(
    TextShadow(
        offsetX = 2.dp,
        offsetY = 2.dp,
        blur = 2.dp,
        color = Color.BLACK.withAlpha(0.2f),
    ),
)

private fun qrBackgroundColors(accentColor: Int, darkTheme: Boolean): List<Int> {
    return if (darkTheme) {
        listOf(
            blendWithBlack(accentColor, 0.10f).withAlpha(0f),
            blendWithBlack(accentColor, 0.18f).withAlpha(0.72f),
            blendWithBlack(accentColor, 0.30f),
        )
    } else {
        listOf(
            blendWithWhite(accentColor, 0.08f).withAlpha(0f),
            blendWithWhite(accentColor, 0.12f).withAlpha(0.55f),
            blendWithWhite(accentColor, 0.24f),
        )
    }
}

private fun blendWithWhite(color: Int, ratio: Float): Int {
    fun channel(shift: Int): Int {
        val value = (color ushr shift) and 0xFF
        return (255 + (value - 255) * ratio).roundToInt().coerceIn(0, 255)
    }
    return Color.makeRGB(channel(16), channel(8), channel(0))
}

private fun blendWithBlack(color: Int, ratio: Float): Int {
    fun channel(shift: Int): Int {
        val value = (color ushr shift) and 0xFF
        return (value * ratio).roundToInt().coerceIn(0, 255)
    }
    return Color.makeRGB(channel(16), channel(8), channel(0))
}
