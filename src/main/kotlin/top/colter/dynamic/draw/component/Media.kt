package top.colter.dynamic.draw.component

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*


/**
 * 媒体组件
 *
 * @param cover 封面
 * @param title 标题
 * @param desc 简介
 * @param badge 标签
 * @param duration 时长
 * @param info 信息
 * @param coverRatio 封面比例
 *
 */
fun Layout.Media(
    cover: Image,
    title: String,
    desc: String,
    badge: String? = null,
    duration: String? = null,
    info: String? = null,
    coverRatio: Float = Ratio.COVER_1,
    modifier: Modifier = Modifier()
) = Column(
    modifier = modifier
        .fillMaxWidth()
        .background(Color.WHITE.withAlpha(0.6f))
        .border(3.dp, 15.dp, Color.WHITE)
        .shadows(Shadow.ELEVATION_3)
) {
    require(modifier.width.isNotNull()) { "必须指定宽度" }

    Box(
        modifier = Modifier().fillMaxWidth()
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
                alignment = LayoutAlignment.RIGHT_TOP,
                modifier = Modifier()
                    .padding(horizontal = 30.dp, vertical = 3.dp)
                    .background(color = Color.makeRGB(251, 114, 153))
                    .border(2.dp, listOf(0.dp, 10.dp, 0.dp, 10.dp))
                    .shadows(Shadow.ELEVATION_1)
            ) {
                Text(
                    text = badge,
                    fontSize = 30.dp,
                    color = Color.WHITE,
                    modifier = Modifier().maxWidth(200.dp)
                )
            }
        }

        // 信息
        if (!duration.isNullOrBlank() || !info.isNullOrBlank()) {
            // 遮罩
            Row(
                alignment = LayoutAlignment.LEFT_BOTTOM,
                modifier = Modifier()
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(gradient = Gradient(
                        LayoutAlignment.BOTTOM,
                        LayoutAlignment.TOP,
                        listOf(Color.BLACK.withAlpha(0.5f), Color.BLACK.withAlpha(0f))
                    ))
                    .border(0.dp, listOf(0.dp, 0.dp, 10.dp, 10.dp))
            ) {
                // 时长
                if (!duration.isNullOrBlank()) {
                    Box(
                        alignment = LayoutAlignment.LEFT,
                        modifier = Modifier()
                            .margin(left = 40.dp)
                            .padding(horizontal = 15.dp, vertical = 4.dp)
                            .background(color = Color.BLACK.withAlpha(0.5f))
                            .border(0.dp, 10.dp)
                    ) {
                        Text(
                            text = duration,
                            fontSize = 26.dp,
                            color = Color.WHITE,
                            modifier = Modifier().maxWidth(400.dp)
                        )
                    }
                }

                // 视频信息
                if (!info.isNullOrBlank()) {
                    Text(
                        text = info,
                        fontSize = 28.dp,
                        color = Color.WHITE,
                        alignment = LayoutAlignment.LEFT,
                        modifier = Modifier().maxWidth(600.dp).margin(left = 15.dp)
                    )
                }

            }
        }
    }

    // 标题
    Text(
        text = title,
        fontSize = 34.dp,
        maxLinesCount = 2,
        modifier = Modifier().margin(top = 15.dp, right = 15.dp, bottom = 10.dp, left = 15.dp)
    )

    // 简介
    Text(
        text = desc,
        fontSize = 24.dp,
        color = Color.BLACK.withAlpha(0.7f),
        maxLinesCount = 3,
        modifier = Modifier().margin(right = 15.dp, bottom = 15.dp, left = 15.dp)
    )
}
