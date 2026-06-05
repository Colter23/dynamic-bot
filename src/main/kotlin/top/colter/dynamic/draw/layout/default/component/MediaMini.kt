package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*

/**
 * 迷你媒体组件
 */
internal fun Layout.MediaMini(
    cover: Image? = null,
    title: String,
    desc: String,
    badge: String? = null,
    coverRatio: Float = Ratio.COVER_2,
    accentColor: Int = Color.makeRGB(251, 114, 153),
    cardColor: Int = Color.WHITE.withAlpha(0.6f),
    borderColor: Int = Color.WHITE,
    titleColor: Int = Color.BLACK,
    secondaryTextColor: Int = Color.BLACK.withAlpha(0.7f),
    modifier: Modifier = Modifier()
) = Row (
    modifier = modifier
        .height(100.dp)
        .fillMaxWidth()
        .background(cardColor)
        .border(3.dp, 15.dp, borderColor)
        .shadows(Shadow.ELEVATION_3)
) {
    require(modifier.height.isNotNull()) { "必须指定高度" }

    // 封面
    if (cover != null) {
        Image(
            image = cover,
            ratio = coverRatio,
            modifier = Modifier().fillMaxHeight().border(3.dp, 10.dp).shadows(Shadow.ELEVATION_2)
        )
    }

    // 标题简介
    Column(modifier = Modifier().fillWidth().fillMaxHeight().margin(horizontal = 15.dp, vertical = 5.dp)) {
        Box(modifier = Modifier().fillMaxWidth().fillRatioHeight(0.4f)) {
            Text(
                text = title,
                fontSize = 22.dp,
                color = titleColor,
                maxLinesCount = 1,
                alignment = LayoutAlignment.LEFT,
                modifier = Modifier().fillMaxWidth()
            )
        }
        Box(modifier = Modifier().fillMaxWidth().fillRatioHeight(0.6f)) {
            Text(
                text = desc,
                color = secondaryTextColor,
                maxLinesCount = 2,
                alignment = LayoutAlignment.LEFT,
                modifier = Modifier().fillMaxWidth()
            )
        }
    }

    // TAG
    if (!badge.isNullOrBlank()) {
        Box(
            alignment = LayoutAlignment.RIGHT_TOP,
            modifier = Modifier()
                .padding(horizontal = 20.dp, vertical = 3.dp)
                .background(color = accentColor)
                .border(2.dp, listOf(0.dp, 10.dp, 0.dp, 10.dp))
                .shadows(Shadow.ELEVATION_2)
        ) {
            Text(
                text = badge,
                fontSize = 20.dp,
                color = Color.WHITE,
                modifier = Modifier().maxWidth(200.dp)
            )
        }
    }
}
