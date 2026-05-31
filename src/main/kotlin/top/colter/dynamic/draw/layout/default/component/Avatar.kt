package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.Image
import top.colter.skiko.Dp
import top.colter.skiko.Modifier
import top.colter.skiko.aspectRatio
import top.colter.skiko.border
import top.colter.skiko.circle
import top.colter.skiko.dp
import top.colter.skiko.fillMaxHeight
import top.colter.skiko.fillMaxSize
import top.colter.skiko.fillRatioWidth
import top.colter.skiko.overflowRatioHeight
import top.colter.skiko.overflowRatioWidth
import top.colter.skiko.shadows
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.Box
import top.colter.skiko.layout.Image
import top.colter.skiko.layout.Layout

/**
 * 头像组件
 *
 * 必须指定宽度或者高度。
 *
 * [face] 头像
 * [pendant] 头像框
 * [badge] 右下角徽章
 */
internal fun Layout.Avatar(
    face: Image,
    pendant: Image? = null,
    badge: Image? = null,
    faceBorderWidth: Dp = 4.dp,
    badgeBorderWidth: Dp = 3.dp,
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Box(
    alignment = alignment,
    modifier = modifier.aspectRatio(1f)
) {
    val hasPendant = pendant != null
    val faceRatio = if (hasPendant) 0.78f else 1f
    val badgeRatio = if (hasPendant) 0.25f else 0.33f

    Box(
        alignment = LayoutAlignment.CENTER,
        modifier = Modifier()
            .fillMaxHeight()
            .aspectRatio(1f)
    ) {
        Image(
            image = face,
            ratio = 1f / 1f,
            alignment = LayoutAlignment.CENTER,
            modifier = Modifier()
                .fillRatioWidth(faceRatio)
                .circle()
                .border(faceBorderWidth)
                .shadows(Shadow.ELEVATION_2)
        )

        if (pendant != null) {
            Image(
                image = pendant,
                ratio = 1f / 1f,
                alignment = LayoutAlignment.CENTER,
                modifier = Modifier()
                    .fillMaxSize()
                    .overflowRatioWidth(1.375f)
                    .overflowRatioHeight(1.375f)
            )
        }

        if (badge != null) {
            Image(
                image = badge,
                ratio = 1f / 1f,
                alignment = LayoutAlignment.RIGHT_BOTTOM,
                modifier = Modifier()
                    .fillRatioWidth(badgeRatio)
                    .circle()
                    .border(badgeBorderWidth)
            )
        }
    }
}
