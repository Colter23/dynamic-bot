package top.colter.dynamic.draw.layout.minimal

import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.layout.default.renderDefaultDynamic

internal fun renderMinimalDynamic(dynamic: Dynamic, config: DrawConfig): Image {
    // 简约布局目前只注册入口，后续在这里替换为真实的简约动态布局。
    return renderDefaultDynamic(dynamic, config)
}
