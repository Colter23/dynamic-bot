package top.colter.dynamic.draw.layout.default.component

import kotlin.math.roundToInt
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.skiko.Modifier
import top.colter.skiko.aspectRatio
import top.colter.skiko.background
import top.colter.skiko.bleed
import top.colter.skiko.dp
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
import top.colter.skiko.withAlpha
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment

private val qrBackgroundBleedLeft = 68.dp
private val qrLogoSize = 40.dp
private val qrLogoPadding = 3.dp
private val qrLogoRadius = 8.dp
private val qrLogoInnerRadius = 6.dp

/**
 * 装饰组件
 */
internal fun Layout.Decorate(
    image: Image? = null,
    qrCode: Image? = null,
    accentColor: Int = Color.makeRGB(251, 114, 153),
    darkTheme: Boolean = false,
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Box(
    alignment = alignment,
    modifier = modifier
) {
    if (qrCode != null) {
        Box(
            alignment = LayoutAlignment.RIGHT,
            modifier = Modifier()
                .fillMaxHeight()
                .bleed(left = qrBackgroundBleedLeft)
                .radius(listOf(0.dp, 15.dp, 15.dp, 0.dp))
                .background(
                    gradient = Gradient(
                        LayoutAlignment.LEFT,
                        LayoutAlignment.RIGHT,
                        qrBackgroundColors(accentColor, darkTheme)
                    )
                )
        ) {
            Box(
                alignment = LayoutAlignment.RIGHT,
                modifier = Modifier()
                    .fillMaxHeight()
                    .aspectRatio(1f)
            ) {
                Image(
                    image = qrCode,
                    ratio = 1f,
                    alignment = LayoutAlignment.RIGHT,
                    modifier = Modifier().fillMaxHeight()
                )
                if (image != null) {
                    Box(
                        alignment = LayoutAlignment.CENTER,
                        modifier = Modifier()
                            .width(qrLogoSize)
                            .height(qrLogoSize)
                            .padding(qrLogoPadding)
                            .background(color = if (darkTheme) Color.BLACK else Color.WHITE)
                            .radius(qrLogoRadius)
                    ) {
                        Box(
                            alignment = LayoutAlignment.CENTER,
                            modifier = Modifier()
                                .fillMaxSize()
                                .padding(horizontal = 2.dp, vertical = 4.dp)
                                .background(color = accentColor)
                                .radius(qrLogoInnerRadius)
                        ) {
                            Image(
                                image = image,
                                alignment = LayoutAlignment.CENTER,
                                modifier = Modifier()
                                    .fillMaxWidth()
                                    .padding(horizontal = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    } else if (image != null) {
        Image(
            image = image,
            alignment = LayoutAlignment.RIGHT,
            modifier = Modifier().fillMaxHeight()
        )
    }
}

private fun qrBackgroundColors(accentColor: Int, darkTheme: Boolean): List<Int> {
    return if (darkTheme) {
        listOf(
            blendWithBlack(accentColor, 0.10f).withAlpha(0f),
            blendWithBlack(accentColor, 0.18f).withAlpha(0.72f),
            blendWithBlack(accentColor, 0.30f),
        )
    } else {
        listOf(
            blendWithWhite(accentColor, 0.08f).withAlpha(0f),
            blendWithWhite(accentColor, 0.12f).withAlpha(0.55f),
            blendWithWhite(accentColor, 0.24f),
        )
    }
}

private fun blendWithWhite(color: Int, ratio: Float): Int {
    fun channel(shift: Int): Int {
        val value = (color ushr shift) and 0xFF
        return (255 + (value - 255) * ratio).roundToInt().coerceIn(0, 255)
    }
    return Color.makeRGB(channel(16), channel(8), channel(0))
}

private fun blendWithBlack(color: Int, ratio: Float): Int {
    fun channel(shift: Int): Int {
        val value = (color ushr shift) and 0xFF
        return (value * ratio).roundToInt().coerceIn(0, 255)
    }
    return Color.makeRGB(channel(16), channel(8), channel(0))
}
