package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.util.formatTime
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

internal fun renderDefaultDynamic(update: SourceUpdate, config: DrawConfig): Image {
    return View(
        fontRegistry = config.fontRegistry,
        modifier = Modifier()
            .width(config.settings.width.dp)
            .padding(30.dp)
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

private fun Layout.DefaultDynamicView(
    update: SourceUpdate,
    config: DrawConfig,
    mode: DynamicRenderMode = DynamicRenderMode.ROOT,
) {
    val payload = update.payload as? DynamicPayload ?: return
    Column(modifier = Modifier().fillMaxWidth()) {
        drawPublisher(
            publisher = update.publisher,
            time = update.occurredAtEpochSeconds.formatTime,
            link = update.link.orEmpty(),
            config = config,
            mode = mode,
        )

        Column(
            modifier = Modifier()
                .fillMaxWidth()
                .margin(top = 30.dp, bottom = 30.dp)
                .padding(30.dp)
                .background(config.theme.cardColor)
                .border(3.dp, 15.dp, config.theme.borderColor)
        ) {
            payload.title?.let { title ->
                Text(
                    text = title,
                    color = config.theme.textColor,
                    fontSize = 36.dp,
                    fontStyle = FontStyle.BOLD,
                    maxLinesCount = 2,
                    modifier = Modifier().margin(bottom = 20.dp),
                )
            }
            payload.content?.let { content -> drawDynamicContent(content, config) }
            if (payload.attachments.isNotEmpty()) {
                drawDynamicAttachments(payload.attachments, config, mode)
            }
            payload.references
                .filter { reference -> reference.kind == DynamicReferenceKind.ORIGIN }
                .mapNotNull { reference -> reference.embedded }
                .forEach { origin ->
                    DefaultDynamicView(origin, config, DynamicRenderMode.FORWARD)
                }
        }
    }
}
