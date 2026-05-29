package top.colter.dynamic.draw.layout.minimal

import org.jetbrains.skia.Image
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.DrawLayoutSuite
import top.colter.dynamic.draw.DrawScene

internal object MinimalDrawLayoutSuite : DrawLayoutSuite {
    override val id: String = "minimal"
    override val name: String = "简约布局"

    override fun render(scene: DrawScene, config: DrawConfig): Image {
        return when (scene) {
            is DrawScene.DynamicScene -> renderMinimalDynamic(scene.update, config)
        }
    }
}
