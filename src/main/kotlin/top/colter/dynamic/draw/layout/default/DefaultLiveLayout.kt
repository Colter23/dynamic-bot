package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.Image as SkiaImage
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.layout.default.component.Media
import top.colter.dynamic.draw.layout.default.component.MediaSmall
import top.colter.dynamic.draw.layout.default.component.mediaCardColors
import top.colter.dynamic.util.formatMetricInfo
import top.colter.dynamic.util.formatTime
import top.colter.skiko.Dp
import top.colter.skiko.Modifier
import top.colter.skiko.background
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.dp
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.View
import top.colter.skiko.margin
import top.colter.skiko.padding
import top.colter.skiko.width

private val liveScenePadding: Dp = 20.dp
private val liveContentSpacing: Dp = 20.dp

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

        drawLiveMediaCard(
            live = live,
            cover = cover,
            config = config,
            modifier = Modifier()
                .fillMaxWidth()
                .margin(top = liveContentSpacing),
        )
    }
}

internal fun Layout.drawLiveMediaCard(
    live: LivePayload,
    cover: SkiaImage?,
    config: DrawConfig,
    modifier: Modifier = Modifier().fillMaxWidth(),
) {
    val title = live.title.ifBlank { "直播" }
    val info = live.metrics.formatMetricInfo()
    val subtitle = listOfNotNull(
        live.area?.takeIf { it.isNotBlank() },
        info.takeIf { cover == null && it.isNotBlank() },
    ).joinToString(" / ")
    val badge = live.statusLabel()
    val colors = mediaCardColors(config.theme)
    if (cover == null) {
        MediaSmall(
            cover = null,
            title = title,
            desc = subtitle,
            badge = badge,
            colors = colors,
            modifier = modifier,
        )
    } else {
        Media(
            cover = cover,
            title = title,
            desc = subtitle,
            badge = badge,
            coverRatio = Ratio.COVER_1,
            info = info,
            colors = colors,
            modifier = modifier,
        )
    }
}

private fun LivePayload.statusLabel(): String {
    statusText?.takeIf { it.isNotBlank() }?.let { return it }
    return when (status) {
        LiveStatus.OPEN -> "直播中"
        LiveStatus.ROUND -> "轮播中"
        LiveStatus.CLOSE -> if (endedAtEpochSeconds == null) "未开播" else "已结束"
    }
}
