package top.colter.dynamic.draw.layout.minimal

import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.layout.default.renderDefaultDynamic

internal fun renderMinimalDynamic(update: SourceUpdate, config: DrawConfig): Image {
    return renderDefaultDynamic(update, config)
}
