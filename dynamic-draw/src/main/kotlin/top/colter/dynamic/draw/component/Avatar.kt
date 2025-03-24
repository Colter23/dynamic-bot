package top.colter.dynamic.draw.component

import org.jetbrains.skia.Image
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.Box
import top.colter.skiko.layout.Image
import top.colter.skiko.layout.Layout

/**
 * 头像组件
 *
 * 必需指定宽度或者高度
 *
 * [pendant] 头像框 当有头像框时，会超出指定大小一些，可以给一些外边距
 *
 * [badge] 右下角徽章
 *
 */
fun Layout.Avatar(
    face: Image,
    pendant: Image? = null,
    badge: Image? = null,
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Box(
    alignment = alignment,
    modifier = modifier
) {
    require(modifier.width.isNotNull() || modifier.height.isNotNull()) { "必须指定宽度或高度" }

    val width = if (modifier.width.isNotNull()) modifier.contentWidth else modifier.contentHeight
    if (pendant != null) modifier.margin(width * 0.375f) else modifier.margin(width * 0.2f)

    val faceWidth = if (pendant == null) width else width * 0.787f

    Image(
        image = face,
        ratio = 1f/1f,
        alignment = LayoutAlignment.CENTER,
        modifier = Modifier().width(faceWidth).border(4.dp, faceWidth / 2).shadows(Shadow.ELEVATION_2)
    )

    if (pendant != null) {
        Image(
            image = pendant,
            alignment = LayoutAlignment.CENTER,
            modifier = Modifier().width(width * 1.375f).margin(width * -0.375f)
        )
    }
    if (badge != null) {
        Image(
            image = badge,
            ratio = 1f/1f,
            alignment = LayoutAlignment.RIGHT_BOTTOM,
            modifier = Modifier().width(width / if (pendant != null) 4f else 3f).border(3.dp, faceWidth / 2)
        )
    }
}
