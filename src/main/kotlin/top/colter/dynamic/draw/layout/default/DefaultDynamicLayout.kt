package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.util.formatTime
import top.colter.skiko.Dp
import top.colter.skiko.Modifier
import top.colter.skiko.background
import top.colter.skiko.border
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.dp
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Text
import top.colter.skiko.layout.View
import top.colter.skiko.margin
import top.colter.skiko.padding
import top.colter.skiko.width

internal enum class DynamicRenderMode {
    ROOT,
    FORWARD,
}

private val scenePadding: Dp = 20.dp
private val contentSpacing: Dp = 20.dp
private val cardPadding: Dp = 20.dp
private val cardBorderWidth: Dp = 3.dp
private val cardRadius: Dp = 15.dp

internal fun renderDefaultDynamic(update: SourceUpdate, config: DrawConfig): Image {
    return View(
        fontRegistry = config.fontRegistry,
        modifier = Modifier()
            .width(config.settings.width.dp)
            .padding(scenePadding)
            .background(
                gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    config.theme.backgroundColors,
                )
            )
    ) {
        DefaultDynamicView(update, config, DynamicRenderMode.ROOT)
    }
}

internal fun Layout.DefaultDynamicView(
    update: SourceUpdate,
    config: DrawConfig,
    mode: DynamicRenderMode = DynamicRenderMode.ROOT,
    topSpacing: Dp = contentSpacing,
) {
    val payload = update.payload as? DynamicPayload ?: return
    val title = payload.title?.takeIf { it.isNotBlank() }
    val blocks = payload.blocks
    val hasBlocks = blocks.isNotEmpty()

    Column(modifier = Modifier().fillMaxWidth()) {
        if (mode == DynamicRenderMode.ROOT) {
            drawPublisher(
                publisher = update.publisher,
                time = update.occurredAtEpochSeconds.formatTime,
                link = update.link.orEmpty(),
                config = config,
                mode = mode,
            )
        }

        Column(
            modifier = Modifier()
                .fillMaxWidth()
                .margin(top = topSpacing)
                .padding(cardPadding)
                .background(config.theme.cardColor)
                .border(cardBorderWidth, cardRadius, config.theme.borderColor)
        ) {
            if (mode == DynamicRenderMode.FORWARD) {
                drawPublisher(
                    publisher = update.publisher,
                    time = update.occurredAtEpochSeconds.formatTime,
                    link = update.link.orEmpty(),
                    config = config,
                    mode = mode,
                )
            }

            title?.let {
                Text(
                    text = it,
                    color = config.theme.textColor,
                    fontSize = 36.dp,
                    fontStyle = FontStyle.BOLD,
                    maxLinesCount = 2,
                    modifier = Modifier().margin(bottom = if (hasBlocks) contentSpacing else 0.dp),
                )
            }
            if (hasBlocks) {
                drawDynamicBlocks(blocks, config, mode)
            }
        }
    }
}
