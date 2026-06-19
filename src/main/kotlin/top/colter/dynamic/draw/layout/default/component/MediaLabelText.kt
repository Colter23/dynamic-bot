package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.paragraph.TextStyle
import top.colter.skiko.Dp
import top.colter.skiko.Modifier
import top.colter.skiko.dp
import top.colter.skiko.height
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Text
import top.colter.skiko.maxWidth
import top.colter.skiko.px
import top.colter.skiko.data.LayoutAlignment

internal fun Layout.MediaLabelText(
    text: String,
    fontSize: Dp,
    lineHeight: Dp,
    color: Int,
    maxWidth: Dp = 400.dp,
) {
    val lineHeightRatio = (lineHeight.px / fontSize.px.coerceAtLeast(1f)).coerceIn(1.0f, 2.0f)
    Text(
        text = text,
        textStyle = TextStyle()
            .setColor(color)
            .setFontSize(fontSize.px)
            .setHeight(lineHeightRatio)
            .apply { topRatio = 0.5f },
        maxLinesCount = 1,
        alignment = LayoutAlignment.CENTER,
        intrinsicAlignment = LayoutAlignment.CENTER,
        modifier = Modifier()
            .height(lineHeight)
            .maxWidth(maxWidth),
    )
}
