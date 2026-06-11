package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.Image
import top.colter.skiko.Modifier
import top.colter.skiko.aspectRatio
import top.colter.skiko.background
import top.colter.skiko.bleed
import top.colter.skiko.fillMaxHeight
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.fillMaxSize
import top.colter.skiko.height
import top.colter.skiko.layout.Box
import top.colter.skiko.layout.Image
import top.colter.skiko.layout.Layout
import top.colter.skiko.padding
import top.colter.skiko.radius
import top.colter.skiko.width
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment

/**
 * 作者卡片右侧装饰组件。
 */
internal fun Layout.Decorate(
    image: Image? = null,
    qrCode: Image? = null,
    style: AuthorDecorateStyle = defaultAuthorDecorateStyle(),
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier,
) = Box(
    alignment = alignment,
    modifier = modifier,
) {
    if (qrCode != null) {
        Box(
            alignment = LayoutAlignment.RIGHT,
            modifier = Modifier()
                .fillMaxHeight()
                .bleed(left = style.qrBackgroundBleedLeft)
                .radius(style.qrBackgroundRadius)
                .apply {
                    if (style.qrBackgroundColors.isNotEmpty())
                        this.background(gradient = Gradient(
                        LayoutAlignment.LEFT,
                        LayoutAlignment.RIGHT,
                        style.qrBackgroundColors,
                    ))
                },
        ) {
            Box(
                alignment = LayoutAlignment.RIGHT,
                modifier = Modifier()
                    .fillMaxHeight()
                    .aspectRatio(1f),
            ) {
                Image(
                    image = qrCode,
                    ratio = 1f,
                    alignment = LayoutAlignment.RIGHT,
                    modifier = Modifier().fillMaxHeight(),
                )
                if (image != null) {
                    QrLogo(image, style.qrLogo)
                }
            }
        }
    } else if (image != null) {
        Image(
            image = image,
            alignment = LayoutAlignment.RIGHT,
            modifier = Modifier().fillMaxHeight(),
        )
    }
}

private fun Layout.QrLogo(
    image: Image,
    style: AuthorQrLogoStyle,
) {
    Box(
        alignment = LayoutAlignment.CENTER,
        modifier = Modifier()
            .width(style.size)
            .height(style.size)
            .padding(style.padding)
            .background(color = style.outerColor)
            .radius(style.radius),
    ) {
        Box(
            alignment = LayoutAlignment.CENTER,
            modifier = Modifier()
                .fillMaxSize()
                .padding(
                    horizontal = style.innerPaddingHorizontal,
                    vertical = style.innerPaddingVertical,
                )
                .background(color = style.innerColor)
                .radius(style.innerRadius),
        ) {
            Image(
                image = image,
                alignment = LayoutAlignment.CENTER,
                modifier = Modifier()
                    .fillMaxWidth()
                    .padding(horizontal = style.imagePaddingHorizontal),
            )
        }
    }
}
