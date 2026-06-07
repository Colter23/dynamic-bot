package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.*
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Shadow
import top.colter.skiko.data.TextShadow
import top.colter.skiko.data.TextStroke
import top.colter.skiko.layout.*

private val authorNameShadows = listOf(
    TextShadow(
        offsetX = 2.dp,
        offsetY = 2.dp,
        blur = 2.dp,
        color = Color.BLACK.withAlpha(0.42f),
    ),
)
private val authorTimeShadows = listOf(
    TextShadow(
        offsetX = 1.dp,
        offsetY = 1.dp,
        blur = 1.dp,
        color = Color.BLACK.withAlpha(0.42f),
    ),
)

internal data class AuthorContentStyle(
    val height: Dp,
    val avatarSize: Dp,
    val avatarLeft: Dp,
    val avatarTop: Dp,
    val avatarTextSpacing: Dp,
    val textTop: Dp,
    val textRightSpacing: Dp,
    val nameFontSize: Dp,
    val timeFontSize: Dp,
    val timeTopSpacing: Dp,
    val nameStrokeWidth: Dp,
)

internal fun defaultAuthorContentStyle(hasQrCode: Boolean): AuthorContentStyle {
    return if (hasQrCode) {
        AuthorContentStyle(
            height = 150.dp,
            avatarSize = 112.dp,
            avatarLeft = 40.dp,
            avatarTop = 18.dp,
            avatarTextSpacing = 20.dp,
            textTop = 20.dp,
            textRightSpacing = 12.dp,
            nameFontSize = 48.dp,
            timeFontSize = 34.dp,
            timeTopSpacing = 0.dp,
            nameStrokeWidth = 5.dp,
        )
    } else {
        AuthorContentStyle(
            height = 120.dp,
            avatarSize = 92.dp,
            avatarLeft = 40.dp,
            avatarTop = 14.dp,
            avatarTextSpacing = 20.dp,
            textTop = 10.dp,
            textRightSpacing = 12.dp,
            nameFontSize = 43.dp,
            timeFontSize = 32.dp,
            timeTopSpacing = (-10).dp,
            nameStrokeWidth = 5.dp,
        )
    }
}

internal fun minimalAuthorContentStyle(hasQrCode: Boolean): AuthorContentStyle {
    return if (hasQrCode) {
        AuthorContentStyle(
            height = 122.dp,
            avatarSize = 92.dp,
            avatarLeft = 12.dp,
            avatarTop = 10.dp,
            avatarTextSpacing = 16.dp,
            textTop = 18.dp,
            textRightSpacing = 10.dp,
            nameFontSize = 43.dp,
            timeFontSize = 30.dp,
            timeTopSpacing = 0.dp,
            nameStrokeWidth = 3.dp,
        )
    } else {
        AuthorContentStyle(
            height = 92.dp,
            avatarSize = 84.dp,
            avatarLeft = 12.dp,
            avatarTop = 2.dp,
            avatarTextSpacing = 16.dp,
            textTop = 8.dp,
            textRightSpacing = 10.dp,
            nameFontSize = 43.dp,
            timeFontSize = 30.dp,
            timeTopSpacing = (-6).dp,
            nameStrokeWidth = 3.dp,
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
    cardHeight: Dp = defaultAuthorContentStyle(qrCode != null).height,
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Box (
    alignment = alignment,
    modifier = modifier
        .height(cardHeight)
        .fillMaxWidth()
        .background(
            image = head,
            imageAlpha = 0.85f,
            gradient = Gradient(
                LayoutAlignment.LEFT,
                LayoutAlignment.RIGHT,
                listOf(
                    Color.BLACK.withAlpha(0.2f),
                    Color.BLACK.withAlpha(0.2f),
                    Color.BLACK.withAlpha(0f),
                    Color.BLACK.withAlpha(0.2f)
                )
            )
        )
        .border(0.dp, 15.dp)
        .shadows(Shadow.ELEVATION_3)
) {
    //require(modifier.height.isNotNull()) { "必须指定高度" }

    AuthorContent(
        face = face,
        pendant = pendant,
        name = name,
        time = time,
        ornament = ornament,
        badge = badge,
        qrCode = qrCode,
        accentColor = accentColor,
        style = defaultAuthorContentStyle(qrCode != null),
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight(),
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
    style: AuthorContentStyle = defaultAuthorContentStyle(qrCode != null),
    modifier: Modifier,
) = Row(
    modifier = modifier,
) {
    Avatar(
        face = face,
        pendant = pendant,
        badge = badge,
        modifier = Modifier()
            .width(style.avatarSize)
            .height(style.avatarSize)
            .margin(
                top = style.avatarTop,
                right = style.avatarTextSpacing,
                left = style.avatarLeft,
            )
    )

    Column(
        modifier = Modifier()
            .fillWidth()
            .margin(top = style.textTop, right = style.textRightSpacing)
    ) {
        Text(
            text = name,
            color = Color.WHITE,
            fontSize = style.nameFontSize,
            stroke = TextStroke(
                width = style.nameStrokeWidth,
                color = accentColor,
            ),
            textShadows = authorNameShadows,
            alignment = LayoutAlignment.LEFT,
            modifier = Modifier().fillMaxWidth()
        )
        Text(
            text = time,
            color = Color.WHITE.withAlpha(0.85f),
            fontSize = style.timeFontSize,
            textShadows = authorTimeShadows,
            alignment = LayoutAlignment.LEFT,
            modifier = Modifier()
                .fillMaxWidth()
                .margin(top = style.timeTopSpacing)
        )
    }

    Decorate(
        image = ornament,
        qrCode = qrCode,
        accentColor = accentColor,
        modifier = Modifier().fillMaxHeight()
    )
}
