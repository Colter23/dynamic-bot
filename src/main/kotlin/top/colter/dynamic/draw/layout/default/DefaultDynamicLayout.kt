package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.util.formatTime
import top.colter.skiko.Modifier
import top.colter.skiko.background
import top.colter.skiko.border
import top.colter.skiko.dp
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.margin
import top.colter.skiko.padding
import top.colter.skiko.width
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Text
import top.colter.skiko.layout.View

internal enum class DynamicRenderMode {
    ROOT,
    FORWARD,
}

internal fun renderDefaultDynamic(dynamic: Dynamic, config: DrawConfig): Image {
    return View(
        fontRegistry = config.fontRegistry,
        modifier = Modifier()
            .width(config.settings.width.dp)
            .padding(30.dp)
            .background(
                gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    config.theme.backgroundColors
                )
            )
    ) {
        DefaultDynamicView(dynamic, config, DynamicRenderMode.ROOT)
    }
}

private fun Layout.DefaultDynamicView(
    dynamic: Dynamic,
    config: DrawConfig,
    mode: DynamicRenderMode = DynamicRenderMode.ROOT,
) {
    Column(modifier = Modifier().fillMaxWidth()) {
        drawPublisher(dynamic.publisher, dynamic.time.formatTime, dynamic.link, config, mode)

        Column(modifier = Modifier()
            .fillMaxWidth()
            .margin(top = 30.dp, bottom = 30.dp)
            .padding(30.dp)
            .background(config.theme.cardColor)
            .border(3.dp, 15.dp, config.theme.borderColor)
        ) {

            dynamic.title?.let { title ->
                Text(
                    text = title,
                    color = config.theme.textColor,
                    fontSize = 36.dp,
                    fontStyle = FontStyle.BOLD,
                    maxLinesCount = 2,
                    modifier = Modifier().margin(bottom = 20.dp)
                )
            }
            dynamic.content?.let { content -> drawDynamicContent(content, config) }
            if (dynamic.attachments.isNotEmpty()) {
                drawDynamicAttachments(dynamic.attachments, config, mode)
            }
            dynamic.references
                .filter { reference -> reference.kind == DynamicReferenceKind.ORIGIN }
                .mapNotNull { reference -> reference.update }
                .forEach { origin ->
                    DefaultDynamicView(origin, config, DynamicRenderMode.FORWARD)
                }
        }
    }
}
