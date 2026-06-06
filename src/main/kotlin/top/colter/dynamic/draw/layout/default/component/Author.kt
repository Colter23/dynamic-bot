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
    cardHeight: Dp = 150.dp,
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
    modifier: Modifier,
) = Row(
    modifier = modifier,
) {
    Avatar(
        face = face,
        pendant = pendant,
        badge = badge,
        modifier = Modifier()
            .fillMaxHeight()
            .padding(horizontal = 5.dp, vertical = 20.dp)
    )

    Column(modifier = Modifier().fillWidth().fillMaxHeight().padding(vertical = 10.dp)) {
        Text(
            text = name,
            color = Color.WHITE,
            fontSize = 45.dp,
            stroke = TextStroke(
                width = 3.dp,
                color = accentColor,
            ),
            textShadows = authorNameShadows,
            alignment = LayoutAlignment.LEFT,
            modifier = Modifier().fillMaxWidth().fillRatioHeight(0.6f)
        )
        Text(
            text = time,
            color = Color.WHITE.withAlpha(0.85f),
            fontSize = 32.dp,
            textShadows = listOf(
                TextShadow(
                    offsetX = 1.dp,
                    offsetY = 1.dp,
                    blur = 1.dp,
                    color = Color.BLACK.withAlpha(0.42f),
                ),
            ),
            alignment = LayoutAlignment.LEFT,
            modifier = Modifier().fillMaxWidth().fillRatioHeight(1f - 0.4f)
        )
    }

    Decorate(
        image = ornament,
        qrCode = qrCode,
        accentColor = accentColor,
        modifier = Modifier().fillMaxHeight()
    )
}
