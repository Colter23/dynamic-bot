package top.colter.dynamic.draw

import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.SourceUpdate

public fun renderDynamicImage(update: SourceUpdate, config: DrawConfig): Image {
    return DrawLayoutRegistry.render(DrawScene.DynamicScene(update), config)
}

public fun renderLiveImage(update: SourceUpdate, config: DrawConfig): Image {
    require(update.payload is LivePayload) { "直播绘图只能接收直播负载" }
    return DrawLayoutRegistry.render(DrawScene.LiveScene(update), config)
}
