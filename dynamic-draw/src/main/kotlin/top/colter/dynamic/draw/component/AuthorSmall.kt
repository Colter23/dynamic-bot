package top.colter.dynamic.draw.component

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.dynamic.draw.loadSVG
import top.colter.dynamic.draw.makeImage
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
    official: String? = null,
    name: String,
    time: String,
    alignment: LayoutAlignment = LayoutAlignment.CENTER,
    modifier: Modifier
) = Row (
    alignment = alignment,
    modifier = modifier
) {
    require(modifier.height.isNotNull()) { "必须指定高度" }

    val badgeImage = when (official) {
        "OfficialVerifyType.NONE" -> null
        "OfficialVerifyType.PERSONA" -> loadSVG(File("src/main/resources/icon/PERSONAL_OFFICIAL_VERIFY.svg").readBytes()).makeImage(100, 100)
        "OfficialVerifyType.ORGANIZATION" -> loadSVG(File("src/main/resources/icon/ORGANIZATION_OFFICIAL_VERIFY.svg").readBytes()).makeImage(100, 100)
        else -> { null }
    }

    Avatar(
        face = face,
        badge = badgeImage,
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
