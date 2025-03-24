package top.colter.dynamic.draw.component

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.Box
import top.colter.skiko.layout.Image
import top.colter.skiko.layout.Layout


/**
 * 装饰组件
 */
fun Layout.Decorate(
    image: Image? = null,
    qrCode: Image? = null,
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Box (
    alignment = alignment,
    modifier = modifier
) {
    require(modifier.height.isNotNull()) { "必须指定高度" }

    if (image != null) {
        Image(
            image = image,
            alignment = LayoutAlignment.RIGHT,
            modifier = Modifier().fillMaxHeight()
        )
    }
    if (qrCode != null) {
        Box(
            alignment = LayoutAlignment.RIGHT,
            modifier = Modifier()
                .fillMaxHeight()
                .width(modifier.height * 1.5f)
                .border(0.dp, listOf(0.dp, 15.dp, 15.dp, 0.dp))
                .background(gradient =
                    Gradient(
                        LayoutAlignment.RIGHT,
                        LayoutAlignment.LEFT,
                        listOf(
                            Color.WHITE,
                            Color.WHITE,
                            Color.WHITE,
                            Color.WHITE.withAlpha(0.6f),
                            Color.WHITE.withAlpha(0f)
                        )
                    )
                )
        )
        Image(
            image = qrCode,
            alignment = LayoutAlignment.RIGHT,
            modifier = Modifier().fillMaxHeight()
        )
    }


//    val cardHeight = if (numStr == null) modifier.height * 0.6f else modifier.height
//    val cardWidth = image.width.dp * cardHeight.px / image.height.dp
//
//    modifier.width(if (numStr != null) cardWidth * 0.66f else cardWidth)
//
//    Image(
//        image = image,
//        alignment = LayoutAlignment.RIGHT,
//        modifier = Modifier().width(cardWidth)
//    )
//    if (qrCode != null) {
//        Image(
//            image = qrCode,
//            ratio = 1f/1f,
//            alignment = LayoutAlignment.RIGHT_BOTTOM,
//            modifier = Modifier().width(width)
//        )
//    } else if (numStr != null) {
//        Text(
//            text = numStr,
//            color = color?: Color.BLACK,
//            fontSize = cardHeight / 3.5f,
////            fontFamily = fanFont.familyName,
//            alignment = LayoutAlignment.LEFT
//        )
//    }
}
