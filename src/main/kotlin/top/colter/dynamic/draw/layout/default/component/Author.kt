package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.*
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*


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
            imageAlpha = 0.8f,
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

    Row(
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Avatar(
            face = face,
            pendant = pendant,
            badge = badge,
            modifier = Modifier()
                .fillMaxHeight()
                .padding(horizontal = 5.dp, vertical = 20.dp)
        )

        Column(modifier = Modifier().fillWidth().fillMaxHeight().padding(vertical = 20.dp)) {
            Text(
                text = name,
                color = Color.WHITE,
                fontSize = 36.dp,
                alignment = LayoutAlignment.LEFT,
                modifier = Modifier().fillMaxWidth().fillRatioHeight(0.56f)
            )
            Text(
                text = time,
                color = Color.WHITE.withAlpha(0.8f),
                fontSize = 26.dp,
                alignment = LayoutAlignment.LEFT,
                modifier = Modifier().fillMaxWidth().fillRatioHeight(1f - 0.56f)
            )
        }

        Decorate(
            image = ornament,
            qrCode = qrCode,
            accentColor = accentColor,
            modifier = Modifier().fillMaxHeight()
        )

    }

}
