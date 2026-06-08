package top.colter.dynamic.draw.layout.default.component
import org.jetbrains.skia.Image
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*

/**
 * 小媒体组件
 */
internal fun Layout.MediaSmall(
    cover: Image?,
    title: String,
    desc: String = "",
    duration: String? = null,
    badge: String? = null,
    coverRatio: Float = Ratio.COVER_2,
    colors: MediaCardColors = MediaCardColors(),
    modifier: Modifier = Modifier()
) = Row (
    modifier = modifier
        .height(200.dp)
        .fillMaxWidth()
        .background(colors.cardColor)
        .border(3.dp, 15.dp, colors.borderColor)
        .shadows(Shadow.ELEVATION_3)
) {
    require(modifier.height.isNotNull()) { "必须指定高度" }

    if (cover != null) {
        Box(
            modifier = Modifier().fillMaxHeight()
        ) {
            Image(
                image = cover,
                ratio = coverRatio,
                modifier = Modifier().fillMaxHeight().border(3.dp, 10.dp, colors.coverBorderColor).shadows(Shadow.ELEVATION_2)
            )

            if (!badge.isNullOrBlank()) {
                Box(
                    alignment = LayoutAlignment.LEFT_TOP,
                    modifier = Modifier()
                        .padding(horizontal = 24.dp, vertical = 3.dp)
                        .background(color = colors.accentColor)
                        .border(2.dp, listOf(10.dp, 0.dp, 10.dp, 0.dp), colors.badgeBorderColor)
                        .shadows(Shadow.ELEVATION_1)
                ) {
                    Text(
                        text = badge,
                        fontSize = 22.dp,
                        color = colors.badgeTextColor,
                        modifier = Modifier().maxWidth(200.dp)
                    )
                }
            }

            if (!duration.isNullOrBlank()) {
                Box(
                    alignment = LayoutAlignment.RIGHT_BOTTOM,
                    modifier = Modifier()
                        .margin(right = 25.dp, bottom = 20.dp)
                        .padding(horizontal = 15.dp, vertical = 2.dp)
                        .background(color = colors.overlayPillColor)
                        .radius(10.dp)
                ) {
                    Text(
                        text = duration,
                        fontSize = 20.dp,
                        color = colors.overlayTextColor,
                        modifier = Modifier().maxWidth(400.dp)
                    )
                }
            }
        }
    } else if (!badge.isNullOrBlank()) {
        Box(
            alignment = LayoutAlignment.LEFT_TOP,
            modifier = Modifier()
                .padding(horizontal = 24.dp, vertical = 3.dp)
                .background(color = colors.accentColor)
                .border(2.dp, listOf(10.dp, 0.dp, 10.dp, 0.dp), colors.badgeBorderColor)
                .shadows(Shadow.ELEVATION_1)
        ) {
            Text(
                text = badge,
                fontSize = 22.dp,
                color = colors.badgeTextColor,
                modifier = Modifier().maxWidth(200.dp)
            )
        }
    }

    // 标题简介
    Column(modifier = Modifier().fillWidth().fillMaxHeight().margin(15.dp)) {
        Text(
            text = title,
            fontSize = 23.dp,
            color = colors.titleColor,
            maxLinesCount = 2,
            alignment = LayoutAlignment.LEFT,
            modifier = Modifier().fillRatioHeight(0.4f)
        )

        Text(
            text = desc,
            color = colors.secondaryTextColor,
            maxLinesCount = 4,
            alignment = LayoutAlignment.LEFT,
            modifier = Modifier().fillRatioHeight(0.6f)
        )
    }

}
