package top.colter.dynamic.draw

import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.draw.tools.formatTime
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Text
import top.colter.skiko.layout.View


fun DrawDynamic(dynamic: Dynamic, config: DrawConfig): Image {
    return DrawLayoutRegistry.render(DrawScene.DynamicScene(dynamic), config)
}

object DefaultDrawLayoutSuite : DrawLayoutSuite {
    override val id: String = "default"
    override val name: String = "默认布局"

    override fun render(scene: DrawScene, config: DrawConfig): Image {
        return when (scene) {
            is DrawScene.DynamicScene -> renderDynamic(scene.dynamic, config)
        }
    }

    private fun renderDynamic(dynamic: Dynamic, config: DrawConfig): Image {
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
            DynamicView(dynamic, config, DynamicRenderMode.ROOT)
        }
    }
}

enum class DynamicRenderMode {
    ROOT,
    FORWARD,
}


fun Layout.DynamicView(dynamic: Dynamic, config: DrawConfig, mode: DynamicRenderMode = DynamicRenderMode.ROOT) {
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

            dynamic.media?.let { media -> drawDynamicMedia(media, config, mode) }

            dynamic.origin?.let {
                DynamicView(it, config, DynamicRenderMode.FORWARD)
            }

        }
    }
}
