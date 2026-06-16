package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.Image
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.skiko.Modifier
import top.colter.skiko.background
import top.colter.skiko.bleed
import top.colter.skiko.dp
import top.colter.skiko.fillMaxHeight
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.fillWidth
import top.colter.skiko.height
import top.colter.skiko.layout.Box
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Row
import top.colter.skiko.layout.Text
import top.colter.skiko.margin
import top.colter.skiko.offset
import top.colter.skiko.radius
import top.colter.skiko.shadows
import top.colter.skiko.width
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.TextStroke

/**
 * 作者组件。
 *
 * default 布局使用完整卡片；minimal 布局直接复用 [AuthorContent]。
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
    style: AuthorCardStyle = defaultAuthorCardStyle(qrCode != null),
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier,
) = Box(
    alignment = alignment,
    modifier = modifier
        .height(style.height)
        .fillMaxWidth()
        .radius(style.radius)
        .shadows(style.shadows),
) {
    AuthorHeadBackground(
        head = head,
        style = style.headBackground,
    )

    AuthorContent(
        face = face,
        pendant = pendant,
        name = name,
        time = time,
        ornament = ornament,
        badge = badge,
        qrCode = qrCode,
        style = style.content,
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight(),
    )
}

private fun Layout.AuthorHeadBackground(
    head: Image?,
    style: AuthorHeadBackgroundStyle,
) {
    when (style) {
        is AuthorFallbackHeadBackgroundStyle -> FallbackAuthorHeadBackground(head, style)
        is AuthorThemedHeadBackgroundStyle -> ThemedAuthorHeadBackground(head, style)
    }
}

private fun Layout.ThemedAuthorHeadBackground(
    head: Image?,
    style: AuthorThemedHeadBackgroundStyle,
) {
    Box(
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight()
            .background(
                gradient = Gradient(
                    LayoutAlignment.LEFT,
                    LayoutAlignment.RIGHT,
                    style.baseGradientColors,
                ),
            )
            .radius(style.radius),
    )

    if (head != null) {
        Box(
            modifier = Modifier()
                .fillMaxWidth()
                .fillMaxHeight()
                .background(
                    image = head,
                    imageAlpha = style.blurredImageAlpha,
                    imageBlur = style.blur,
                )
                .radius(style.radius),
        )
        Box(
            modifier = Modifier()
                .fillMaxWidth()
                .fillMaxHeight()
                .background(
                    image = head,
                    imageAlpha = style.originalImageAlpha,
                )
                .radius(style.radius),
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
                    style.tintGradientColors,
                ),
            )
            .radius(style.radius),
    )
    Box(
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight()
            .background(style.washColor)
            .radius(style.radius),
    )
}

private fun Layout.FallbackAuthorHeadBackground(
    head: Image?,
    style: AuthorFallbackHeadBackgroundStyle,
) {
    Box(
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight()
            .background(
                image = head,
                imageAlpha = style.imageAlpha,
                gradient = style.gradient,
            )
            .radius(style.radius),
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
    style: AuthorContentStyle = defaultAuthorContentStyle(qrCode != null),
    modifier: Modifier,
) = Row(
    modifier = modifier,
) {
    val avatarStyle = style.avatar
    val textStyle = style.text

    if (avatarStyle.left > 0.dp) {
        Box(
            modifier = Modifier()
                .width(avatarStyle.left)
                .fillMaxHeight(),
        )
    }

    Avatar(
        face = face,
        pendant = pendant,
        badge = badge,
        modifier = Modifier()
            .width(avatarStyle.size)
            .height(avatarStyle.size)
            .offset(y = avatarStyle.top)
//            .margin(
//                top = avatarStyle.top,
//            ),
    )

    if (avatarStyle.textSpacing > 0.dp) {
        Box(
            modifier = Modifier()
                .width(avatarStyle.textSpacing)
                .fillMaxHeight(),
        )
    }

    Box(
        modifier = Modifier()
            .fillWidth()
            .fillMaxHeight()
            .margin(right = textStyle.rightSpacing),
    ) {
        val nameStyle = textStyle.name
        AuthorTextLine(
            text = name,
            style = nameStyle,
            modifier = Modifier()
                .fillMaxWidth()
                .height(nameStyle.lineHeight)
                .offset(y = textStyle.nameTop, x = nameStyle.left)
                .bleed(vertical = nameStyle.bleedVertical),
        )

        val timeStyle = textStyle.time
        AuthorTextLine(
            text = time,
            style = timeStyle,
            modifier = Modifier()
                .fillMaxWidth()
                .height(timeStyle.lineHeight)
                .offset(y = textStyle.timeTop, x = timeStyle.left)
                .bleed(vertical = timeStyle.bleedVertical),
        )
    }

    Decorate(
        image = ornament,
        qrCode = qrCode,
        style = style.decorate,
        modifier = Modifier().fillMaxHeight(),
    )
}

private fun Layout.AuthorTextLine(
    text: String,
    style: AuthorTextLineStyle,
    modifier: Modifier,
) {
    Text(
        text = text,
        textStyle = TextStyle()
            .setColor(style.color)
            .setFontSize(style.fontSize.px)
            .setHeight(style.lineHeightRatio)
            .apply { topRatio = 0.5f },
        maxLinesCount = 1,
        textShadows = style.shadows,
        stroke = TextStroke(
            width = style.strokeWidth,
            color = style.strokeColor,
        ),
        alignment = LayoutAlignment.LEFT_TOP,
        modifier = modifier,
    )
}

private val AuthorTextLineStyle.lineHeightRatio: Float
    get() = (lineHeight.px / fontSize.px.coerceAtLeast(1f)).coerceIn(1.0f, 2.0f)
