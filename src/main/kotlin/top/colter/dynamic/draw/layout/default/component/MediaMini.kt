package top.colter.dynamic.draw.layout.default.component

import org.jetbrains.skia.Image
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.data.Shadow
import top.colter.skiko.data.TextEmphasis
import top.colter.skiko.layout.*

private val mediaMiniTitleEmphasis = TextEmphasis(0.5f.dp)

/**
 * 迷你媒体组件
 */
internal fun Layout.MediaMini(
    cover: Image? = null,
    title: String,
    desc: String,
    badge: String? = null,
    coverRatio: Float = Ratio.COVER_2,
    colors: MediaCardColors = MediaCardColors(),
    modifier: Modifier = Modifier()
) = Row (
    modifier = modifier
        .height(100.dp)
        .fillMaxWidth()
        .background(colors.cardColor)
        .border(3.dp, 15.dp, colors.borderColor)
        .shadows(Shadow.ELEVATION_3)
) {
    require(modifier.height.isNotNull()) { "必须指定高度" }

    // 封面
    if (cover != null) {
        Image(
            image = cover,
            ratio = coverRatio,
            modifier = Modifier().fillMaxHeight().border(3.dp, 10.dp, colors.coverBorderColor).shadows(Shadow.ELEVATION_2)
        )
    }

    // 标题简介
    Column(modifier = Modifier().fillWidth().fillMaxHeight().margin(horizontal = 15.dp, vertical = 5.dp)) {
        Box(modifier = Modifier().fillMaxWidth().fillRatioHeight(0.4f)) {
            Text(
                text = title,
                fontSize = 22.dp,
                color = colors.titleColor,
                maxLinesCount = 1,
                alignment = LayoutAlignment.LEFT,
                textEmphasis = mediaMiniTitleEmphasis,
                modifier = Modifier().fillMaxWidth()
            )
        }
        Box(modifier = Modifier().fillMaxWidth().fillRatioHeight(0.6f)) {
            Text(
                text = desc,
                color = colors.secondaryTextColor,
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
                .padding(horizontal = 20.dp)
                .background(color = colors.accentColor)
                .border(2.dp, listOf(0.dp, 10.dp, 0.dp, 10.dp), colors.badgeBorderColor)
                .shadows(Shadow.ELEVATION_2)
        ) {
            MediaLabelText(
                text = badge,
                fontSize = 20.dp,
                lineHeight = 32.dp,
                color = colors.badgeTextColor,
                maxWidth = 200.dp,
            )
        }
    }
}
