package top.colter.dynamic.draw

import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.PlatformDescriptor

public fun interface DynamicImageResolver {
    public fun image(image: LazyImage): Image
}

data class DrawConfig(
    val platform: PlatformDescriptor,
    val themeColor: Int = 0xFE65A6,
    val backgroundColors: List<Int> = listOf(),
    val header: LazyImage? = null,
    val ornament: String = "LOGO",
    val imageResolver: DynamicImageResolver = DynamicImageCache,
) {
    fun image(image: LazyImage): Image = imageResolver.image(image)
}
