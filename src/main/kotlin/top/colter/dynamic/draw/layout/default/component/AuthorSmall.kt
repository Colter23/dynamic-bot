package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Row
import top.colter.skiko.layout.Text

private val authorSmallAvatarSize = 46.dp
private val authorSmallAvatarTrailing = 16.dp
private val authorSmallNameTop = 0.dp
private val authorSmallTimeTop = 7.dp

/**
 * 作者小组件
 */
internal fun Layout.AuthorSmall(
    face: Image,
    name: String,
    time: String,
    badge: Image? = null,
    accentColor: Int = Color.makeRGB(251, 114, 153),
    mutedColor: Int = Color.makeRGB(156, 156, 156),
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Row (
    alignment = alignment,
    modifier = modifier
) {

    Avatar(
        face = face,
        badge = badge,
        faceBorderWidth = 2.dp,
        badgeBorderWidth = 2.dp,
        alignment = LayoutAlignment.LEFT_TOP,
        modifier = Modifier()
            .height(authorSmallAvatarSize)
            .margin(right = authorSmallAvatarTrailing)
            .offset(y = (-7).dp)
            .bleed(2.dp)
    )
    Row(
        modifier = Modifier().fillMaxWidth().fillHeight(), // .background(Color.GREEN),
        alignment = LayoutAlignment.LEFT_TOP
    ) {
        Text(
            text = name,
            color = accentColor,
            fontSize = 30.dp,
            alignment = LayoutAlignment.LEFT_TOP,
            modifier = Modifier().margin(top = authorSmallNameTop, right = 15.dp)
        )
        Text(
            text = time,
            color = mutedColor,
            fontSize = 22.dp,
            alignment = LayoutAlignment.LEFT_TOP,
            modifier = Modifier().margin(top = authorSmallTimeTop),
        )
    }
}
