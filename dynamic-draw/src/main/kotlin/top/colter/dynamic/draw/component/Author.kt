package top.colter.dynamic.draw.component

import org.jetbrains.skia.*
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*


/**
 * 作者组件
 */
fun Layout.Author(
    face: Image,
    pendant: Image? = null,
    head: Image? = null,
    official: String? = null,
    name: String,
    time: String,
    ornament: Image? = null,
    badgeImage: Image? = null,
    qrCode: Image? = null,
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Box (
    alignment = alignment,
    modifier = modifier
        .height(150.dp)
        .fillMaxWidth()
        .background(
            image = head,
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
    require(modifier.height.isNotNull()) { "必须指定高度" }

    Row(
        modifier = Modifier()
            .fillMaxWidth()
            .fillMaxHeight()
    ) {

        Avatar(
            face = face,
            pendant = pendant,
            badge = badgeImage,
            modifier = Modifier()
                .height(this.modifier.height)
                .padding(horizontal = 5.dp, vertical = 20.dp)
        )

        Column(modifier = Modifier().fillWidth().fillMaxHeight().padding(vertical = 20.dp)) {
            Box(modifier = Modifier().fillMaxWidth().fillRatioHeight(0.56f)) {
                Text(
                    text = name,
                    color = Color.WHITE,
                    fontSize = 36.dp,
                    alignment = LayoutAlignment.LEFT,
                )
            }
            Box(modifier = Modifier().fillMaxWidth().fillRatioHeight(1f - 0.56f)) {
                Text(
                    text = time,
                    color = Color.WHITE.withAlpha(0.8f),
                    fontSize = 26.dp,
                    alignment = LayoutAlignment.LEFT,
                )
            }
        }

        Decorate(
            image = ornament,
            qrCode = qrCode,
            modifier = Modifier().height(this.modifier.height)
        )

    }

}