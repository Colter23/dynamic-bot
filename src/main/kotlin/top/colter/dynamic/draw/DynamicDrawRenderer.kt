package top.colter.dynamic.draw

import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.SourceUpdate

public fun renderDynamicImage(update: SourceUpdate, config: DrawConfig): Image {
    return DrawLayoutRegistry.render(DrawScene.DynamicScene(update), config)
}
