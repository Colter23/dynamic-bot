package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.*
import top.colter.dynamic.draw.DrawTheme
import top.colter.dynamic.draw.DrawThemeMode
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.GradientBlur
import top.colter.skiko.data.GradientBlurStop
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Shadow
import top.colter.skiko.data.TextShadow
import top.colter.skiko.data.TextStroke
import top.colter.skiko.layout.*
import kotlin.math.roundToInt

private val authorTimeShadows = listOf(
    TextShadow(
        offsetX = 2.dp,
        offsetY = 2.dp,
        blur = 2.dp,
        color = Color.BLACK.withAlpha(0.2f),
    ),
)
private val authorHeadBackgroundBlur = GradientBlur(
    stops = listOf(
        GradientBlurStop(0f, 5.dp),
        GradientBlurStop(1f, 5.dp),
    ),
    steps = 5,
    stripWidth = 3.dp,
)
private val fallbackAuthorHeadGradient = Gradient(
    LayoutAlignment.LEFT,
    LayoutAlignment.RIGHT,
    listOf(
        Color.BLACK.withAlpha(0.2f),
        Color.BLACK.withAlpha(0.2f),
        Color.BLACK.withAlpha(0f),
        Color.BLACK.withAlpha(0.2f),
    ),
)

internal data class AuthorContentStyle(
    val height: Dp,
    val avatarSize: Dp,
    val avatarLeft: Dp,
    val avatarTop: Dp,
    val avatarTextSpacing: Dp,
    val textTop: Dp,
    val nameLeft: Dp,
    val textRightSpacing: Dp,
    val nameFontSize: Dp,
    val timeFontSize: Dp,
    val nameLineHeight: Dp,
    val timeLineHeight: Dp,
    val timeTopSpacing: Dp,
    val nameStrokeWidth: Dp,
    val authorNameShadows: List<TextShadow>,
)

internal fun defaultAuthorContentStyle(hasQrCode: Boolean, accentColor: Int): AuthorContentStyle {
    val shadows = listOf(
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
            avatarSize = 112.dp,
            avatarLeft = 30.dp,
            avatarTop = 18.dp,
            avatarTextSpacing = 18.dp,
            textTop = 7.dp,
            nameLeft = (-21).dp,
            textRightSpacing = 12.dp,
            nameFontSize = 48.dp,
            timeFontSize = 34.dp,
            nameLineHeight = 80.dp,
            timeLineHeight = 42.dp,
            timeTopSpacing = 5.dp,
            nameStrokeWidth = 8.dp,
            authorNameShadows = shadows
        )
    } else {
        AuthorContentStyle(
            height = 120.dp,
            avatarSize = 92.dp,
            avatarLeft = 30.dp,
            avatarTop = 14.dp,
            avatarTextSpacing = 18.dp,
            textTop = (-2).dp,
            nameLeft = (-21).dp,
            textRightSpacing = 12.dp,
            nameFontSize = 43.dp,
            timeFontSize = 32.dp,
            nameLineHeight = 68.dp,
            timeLineHeight = 38.dp,
            timeTopSpacing = 8.dp,
            nameStrokeWidth = 8.dp,
            authorNameShadows = shadows
        )
    }
}

internal fun minimalAuthorContentStyle(hasQrCode: Boolean, accentColor: Int): AuthorContentStyle {
    val shadows = listOf(
        TextShadow(
            offsetX = 0.dp,
            offsetY = 0.dp,
            blur = 8.dp,
            color = accentColor.withAlpha(0.6f),
        ),
    )

    return if (hasQrCode) {
        AuthorContentStyle(
            height = 122.dp,
            avatarSize = 92.dp,
            avatarLeft = 15.dp,
            avatarTop = 16.dp,
            avatarTextSpacing = 18.dp,
            textTop = 5.dp,
            nameLeft = (-20).dp,
            textRightSpacing = 12.dp,
            nameFontSize = 46.dp,
            timeFontSize = 32.dp,
            nameLineHeight = 66.dp,
            timeLineHeight = 38.dp,
            timeTopSpacing = 5.dp,
            nameStrokeWidth = 5.dp,
            authorNameShadows = shadows
        )
    } else {
        AuthorContentStyle(
            height = 92.dp,
            avatarSize = 84.dp,
            avatarLeft = 15.dp,
            avatarTop = 8.dp,
            avatarTextSpacing = 18.dp,
            textTop = (-5).dp,
            nameLeft = (-20).dp,
            textRightSpacing = 12.dp,
            nameFontSize = 43.dp,
            timeFontSize = 30.dp,
            nameLineHeight = 58.dp,
            timeLineHeight = 36.dp,
            timeTopSpacing = 8.dp,
            nameStrokeWidth = 5.dp,
            authorNameShadows = shadows
        )
    }
}


/**
 * 作者组件
 */
internal fun Layout.Author(
    face: Image,
    pendant: Image? = null,
    head: Image? = null,
    name: String,
    time: String,
    ornament: Image? = null,
    badge: Image? = null,
    qrCode: Image? = null,
    accentColor: Int = Color.makeRGB(251, 114, 153),
    theme: DrawTheme? = null,
    darkTheme: Boolean = false,
    cardHeight: Dp = defaultAuthorContentStyle(qrCode != null, accentColor).height,
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Box (
    alignment = alignment,
    modifier = modifier
        .height(cardHeight)
        .fillMaxWidth()
        .radius(15.dp)
        .shadows(Shadow.ELEVATION_3)
) {
    //require(modifier.height.isNotNull()) { "必须指定高度" }
    AuthorHeadBackground(
        head = head,
        theme = theme,
    )

    AuthorContent(
        face = face,
        pendant = pendant,
        name = name,
        time = time,
        ornament = ornament,
        badge = badge,
        qrCode = qrCode,
        accentColor = accentColor,
        darkTheme = theme?.let { it.mode == DrawThemeMode.DARK } ?: darkTheme,
        style = defaultAuthorContentStyle(qrCode != null, accentColor),
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight(),
    )
}

private fun Layout.AuthorHeadBackground(
    head: Image?,
    theme: DrawTheme?,
) {
    if (theme == null) {
        FallbackAuthorHeadBackground(head)
        return
    }

    val darkTheme = theme.mode == DrawThemeMode.DARK
    Box(
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight()
            .background(
                gradient = Gradient(
                    LayoutAlignment.LEFT,
                    LayoutAlignment.RIGHT,
                    theme.backgroundColors,
                ),
            )
            .radius(15.dp),
    )

    if (head != null) {
        Box(
            modifier = Modifier()
                .fillMaxWidth()
                .fillMaxHeight()
                .background(
                    image = head,
                    imageAlpha = if (darkTheme) 0.50f else 0.40f,
                    imageGradientBlur = authorHeadBackgroundBlur,
                )
                .radius(15.dp),
        )
        Box(
            modifier = Modifier()
                .fillMaxWidth()
                .fillMaxHeight()
                .background(
                    image = head,
                    imageAlpha = if (darkTheme) 0.12f else 0.08f,
                )
                .radius(15.dp),
        )
    }

    Box(
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight()
            .background(
                gradient = Gradient(
                    LayoutAlignment.LEFT,
                    LayoutAlignment.RIGHT,
                    listOf(
                        theme.primaryColor.withAlpha(if (darkTheme) 0.16f else 0.09f),
                        theme.readableAccentColor.withAlpha(if (darkTheme) 0.09f else 0.07f),
                        theme.primaryColor.withAlpha(if (darkTheme) 0.10f else 0.07f),
                    ),
                ),
            )
            .radius(15.dp),
    )
    Box(
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight()
            .background(
                if (darkTheme) {
                    Color.makeRGB(8, 12, 22).withAlpha(0.18f)
                } else {
                    Color.WHITE.withAlpha(0.22f)
                },
            )
            .radius(15.dp),
    )
}

private fun Layout.FallbackAuthorHeadBackground(head: Image?) {
    Box(
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight()
            .background(
                image = head,
                imageAlpha = 0.85f,
                gradient = fallbackAuthorHeadGradient,
            )
            .radius(15.dp),
    )
}

/**
 * 作者内容行，不包含外层卡片、背景、圆角和阴影。
 */
internal fun Layout.AuthorContent(
    face: Image,
    pendant: Image? = null,
    name: String,
    time: String,
    ornament: Image? = null,
    badge: Image? = null,
    qrCode: Image? = null,
    accentColor: Int = Color.makeRGB(251, 114, 153),
    darkTheme: Boolean = false,
    style: AuthorContentStyle = defaultAuthorContentStyle(qrCode != null, accentColor),
    modifier: Modifier,
) = Row(
    modifier = modifier,
) {
    if (style.avatarLeft > 0.dp) {
        Box(
            modifier = Modifier()
                .width(style.avatarLeft)
                .fillMaxHeight()
        )
    }

    Avatar(
        face = face,
        pendant = pendant,
        badge = badge,
        modifier = Modifier()
            .width(style.avatarSize)
            .height(style.avatarSize)
            .margin(
                top = style.avatarTop,
            )
    )

    if (style.avatarTextSpacing > 0.dp) {
        Box(
            modifier = Modifier()
                .width(style.avatarTextSpacing)
                .fillMaxHeight()
        )
    }

    Box(
        modifier = Modifier()
            .fillWidth()
            .fillMaxHeight()
            .margin(right = style.textRightSpacing)
    ) {
        Text(
            text = name,
            color = Color.WHITE.withAlpha(0.9f),
            fontSize = style.nameFontSize,
            maxLinesCount = 1,
            stroke = TextStroke(
                width = style.nameStrokeWidth,
                color = accentColor,
//                color = authorNameStrokeColor(accentColor, darkTheme),
            ),
            textShadows = style.authorNameShadows,
//            textShadows = authorNameTextShadows(accentColor, darkTheme),
            alignment = LayoutAlignment.LEFT_TOP,
            modifier = Modifier()
                .fillMaxWidth()
                .height(style.nameLineHeight)
                .offset(y = style.textTop, x = style.nameLeft)
                .bleed(vertical = 14.dp)
        )
        Text(
            text = time,
            color = Color.WHITE.withAlpha(0.8f),
            fontSize = style.timeFontSize,
            maxLinesCount = 1,
            textShadows = authorTimeShadows,
            stroke = TextStroke(
                width = 5.dp,
                color = accentColor.withAlpha(0.6f),
            ),
            alignment = LayoutAlignment.LEFT_TOP,
            modifier = Modifier()
                .fillMaxWidth()
                .height(style.timeLineHeight)
                .offset(y = style.textTop + style.nameLineHeight + style.timeTopSpacing)
                .bleed(vertical = 8.dp)
        )
    }

    Decorate(
        image = ornament,
        qrCode = qrCode,
        accentColor = accentColor,
        darkTheme = darkTheme,
        modifier = Modifier().fillMaxHeight()
    )
}

private fun authorNameStrokeColor(accentColor: Int, darkTheme: Boolean): Int {
    return if (darkTheme) {
        Color.BLACK.withAlpha(0.48f)
    } else {
        mixColor(accentColor, Color.BLACK, 0.34f).withAlpha(0.66f)
    }
}



private fun mixColor(color: Int, target: Int, amount: Float): Int {
    val ratio = amount.coerceIn(0f, 1f)
    val keep = 1f - ratio
    return Color.makeRGB(
        (color.red() * keep + target.red() * ratio).roundToInt().coerceIn(0, 255),
        (color.green() * keep + target.green() * ratio).roundToInt().coerceIn(0, 255),
        (color.blue() * keep + target.blue() * ratio).roundToInt().coerceIn(0, 255),
    )
}

private fun Int.red(): Int = (this ushr 16) and 0xff
private fun Int.green(): Int = (this ushr 8) and 0xff
private fun Int.blue(): Int = this and 0xff
