package top.colter.dynamic.draw.component
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*

/**
 * 小媒体组件
 */
fun Layout.MediaSmall(
    cover: Image,
    title: String,
    desc: String = "",
    duration: String? = null,
    badge: String? = null,
    coverRatio: Float = Ratio.COVER_2,
    modifier: Modifier = Modifier()
) = Row (
    modifier = modifier
        .height(200.dp)
        .fillMaxWidth()
        .background(Color.WHITE.withAlpha(0.6f))
        .border(3.dp, 15.dp, Color.WHITE)
        .shadows(Shadow.ELEVATION_3)
) {
    require(modifier.height.isNotNull()) { "必须指定高度" }

    // 封面
    Box(
        modifier = Modifier().fillMaxHeight()
    ) {
        // 封面
        Image(
            image = cover,
            ratio = coverRatio,
            modifier = Modifier().border(3.dp, 10.dp).shadows(Shadow.ELEVATION_2)
        )

        // TAG
        if (!badge.isNullOrBlank()) {
            Box(
                alignment = LayoutAlignment.LEFT_TOP,
                modifier = Modifier()
                    .padding(horizontal = 24.dp, vertical = 3.dp)
                    .background(color = Color.makeRGB(251, 114, 153))
                    .border(2.dp, listOf(10.dp, 0.dp, 10.dp, 0.dp))
                    .shadows(Shadow.ELEVATION_1)
            ) {
                Text(
                    text = badge,
                    fontSize = 22.dp,
                    color = Color.WHITE,
                    modifier = Modifier().maxWidth(200.dp)
                )
            }
        }

        // 时长
        if (!duration.isNullOrBlank()) {
            Box(
                alignment = LayoutAlignment.RIGHT_BOTTOM,
                modifier = Modifier()
                    .margin(right = 25.dp, bottom = 20.dp)
                    .padding(horizontal = 15.dp, vertical = 2.dp)
                    .background(color = Color.BLACK.withAlpha(0.5f))
                    .border(0.dp, 10.dp)
            ) {
                Text(
                    text = duration,
                    fontSize = 20.dp,
                    color = Color.WHITE,
                    modifier = Modifier().maxWidth(400.dp)
                )
            }
        }
    }

    // 标题简介
    Column(modifier = Modifier().fillWidth().fillMaxHeight().margin(15.dp)) {
        Box(modifier = Modifier().fillMaxWidth().fillRatioHeight(0.4f)) {
            Text(
                text = title,
                fontSize = 23.dp,
                maxLinesCount = 2,
                alignment = LayoutAlignment.LEFT,
                modifier = Modifier().fillMaxWidth()
            )
        }
        Box(modifier = Modifier().fillMaxWidth().fillRatioHeight(0.6f)) {
            Text(
                text = desc,
                color = Color.BLACK.withAlpha(0.7f),
                maxLinesCount = 4,
                alignment = LayoutAlignment.LEFT,
                modifier = Modifier().fillMaxWidth()
            )
        }
    }

}