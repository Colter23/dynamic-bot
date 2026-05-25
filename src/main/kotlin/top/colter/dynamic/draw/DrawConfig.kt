package top.colter.dynamic.draw

import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.PlatformDescriptor

/**
 *
 * @param ornament 装饰 LOGO/QRCODE
 */
data class DrawConfig(
    val platform: PlatformDescriptor,
    val themeColor: Int = 0xFE65A6,
    val backgroundColors: List<Int> = listOf(),
    val header: LazyImage? = null,
    val ornament: String = "LOGO",
)
