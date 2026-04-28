package top.colter.dynamic.draw.component

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.dynamic.draw.tools.loadSVG
import top.colter.dynamic.draw.tools.makeImage
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Row
import top.colter.skiko.layout.Text
import java.io.File

/**
 * 作者小组件
 */
fun Layout.AuthorSmall(
    face: Image,
    name: String,
    time: String,
    badge: Image? = null,
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Row (
    alignment = alignment,
    modifier = modifier
) {
    require(modifier.height.isNotNull()) { "必须指定高度" }

    Avatar(
        face = face,
        badge = badge,
        modifier = Modifier().height(modifier.height).margin(15.dp)
    )
    Row(
        modifier = Modifier().fillMaxWidth().fillHeight(), // .background(Color.GREEN),
        alignment = LayoutAlignment.LEFT
    ) {
        Text(
            text = name,
            color = Color.makeRGB(251, 114, 153),
            fontSize = 30.dp,
            alignment = LayoutAlignment.LEFT,
            modifier = Modifier().margin(right = 15.dp)
        )
        Text(
            text = time,
            color = Color.makeRGB(156, 156, 156),
            fontSize = 22.dp,
            alignment = LayoutAlignment.LEFT,
        )
    }
}
