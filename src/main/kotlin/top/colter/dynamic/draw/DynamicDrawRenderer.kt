package top.colter.dynamic.draw

import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.Dynamic

public fun renderDynamicImage(dynamic: Dynamic, config: DrawConfig): Image {
    return DrawLayoutRegistry.render(DrawScene.DynamicScene(dynamic), config)
}
