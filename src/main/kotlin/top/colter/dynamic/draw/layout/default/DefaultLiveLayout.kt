package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.Color
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image as SkiaImage
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.util.formatTime
import top.colter.skiko.Dp
import top.colter.skiko.Modifier
import top.colter.skiko.background
import top.colter.skiko.border
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.dp
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.height
import top.colter.skiko.layout.Box
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Image
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Text
import top.colter.skiko.layout.View
import top.colter.skiko.margin
import top.colter.skiko.padding
import top.colter.skiko.width
import top.colter.skiko.withAlpha

private val liveScenePadding: Dp = 20.dp
private val liveContentSpacing: Dp = 20.dp
private val liveCardBorderWidth: Dp = 3.dp
private val liveCardRadius: Dp = 15.dp

internal fun renderDefaultLive(update: SourceUpdate, config: DrawConfig): SkiaImage {
    return View(
        fontRegistry = config.fontRegistry,
        modifier = Modifier()
            .width(config.settings.width.dp)
            .padding(liveScenePadding)
            .background(
                gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    config.theme.backgroundColors,
                )
            )
    ) {
        DefaultLiveView(update, config)
    }
}

private fun Layout.DefaultLiveView(update: SourceUpdate, config: DrawConfig) {
    val live = update.payload as? LivePayload ?: return
    val title = live.title.ifBlank { "直播" }
    val cover = live.cover?.let(config::image)
    val liveTime = live.startedAtEpochSeconds ?: update.occurredAtEpochSeconds

    Column(modifier = Modifier().fillMaxWidth()) {
        drawPublisher(
            publisher = update.publisher,
            time = liveTime.formatTime,
            link = update.link.orEmpty(),
            config = config,
            mode = DynamicRenderMode.ROOT,
        )

        Column(
            modifier = Modifier()
                .fillMaxWidth()
                .margin(top = liveContentSpacing)
                .background(config.theme.cardColor)
                .border(liveCardBorderWidth, liveCardRadius, config.theme.borderColor)
        ) {
            drawLiveCover(cover, live, config)
            Text(
                text = title,
                color = config.theme.textColor,
                fontSize = 42.dp,
                fontStyle = FontStyle.BOLD,
                maxLinesCount = 2,
                modifier = Modifier().margin(top = 16.dp, right = 20.dp, bottom = 18.dp, left = 20.dp),
            )
        }
    }
}

private fun Layout.drawLiveCover(cover: SkiaImage?, live: LivePayload, config: DrawConfig) {
    if (cover == null) {
        Box(
            alignment = LayoutAlignment.CENTER,
            modifier = Modifier()
                .fillMaxWidth()
                .height(220.dp)
                .background(config.theme.primaryColor.withAlpha(0.20f))
                .border(0.dp, liveCardRadius)
        ) {
            Text(
                text = live.statusLabel(),
                color = config.theme.primaryColor,
                fontSize = 40.dp,
                fontStyle = FontStyle.BOLD,
                modifier = Modifier().fillMaxWidth(),
            )
        }
        return
    }

    Box(modifier = Modifier().fillMaxWidth()) {
        Image(
            image = cover,
            ratio = Ratio.COVER_1,
            modifier = Modifier()
                .fillMaxWidth()
                .border(0.dp, liveCardRadius),
        )
        Box(
            alignment = LayoutAlignment.RIGHT_TOP,
            modifier = Modifier()
                .padding(horizontal = 30.dp, vertical = 4.dp)
                .background(color = config.theme.primaryColor)
                .border(2.dp, listOf(0.dp, 10.dp, 0.dp, 10.dp))
        ) {
            Text(
                text = live.statusLabel(),
                fontSize = 30.dp,
                color = Color.WHITE,
                modifier = Modifier().fillMaxWidth(),
            )
        }
    }
}

private fun LivePayload.statusLabel(): String {
    return when (status) {
        LiveStatus.OPEN -> "直播中"
        LiveStatus.ROUND -> "轮播中"
        LiveStatus.CLOSE -> "已结束"
    }
}
