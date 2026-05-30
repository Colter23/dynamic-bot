package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.Image
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.DrawLayoutSuite
import top.colter.dynamic.draw.DrawScene

internal object DefaultDrawLayoutSuite : DrawLayoutSuite {
    override val id: String = "default"
    override val name: String = "默认布局"

    override fun render(scene: DrawScene, config: DrawConfig): Image {
        return when (scene) {
            is DrawScene.DynamicScene -> renderDefaultDynamic(scene.update, config)
        }
    }
}
