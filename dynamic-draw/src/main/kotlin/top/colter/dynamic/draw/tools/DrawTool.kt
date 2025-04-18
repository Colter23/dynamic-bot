package top.colter.dynamic.draw.tools

import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.IOException
import java.lang.ClassLoader.getSystemResourceAsStream


//internal val logger  = KotlinLogging.logger {}

fun loadResourceBytes(path: String): ByteArray? {
    return try {
        getSystemResourceAsStream(path)?.readBytes()
//        object {}.javaClass.getResourceAsStream(path)?.readBytes()
    } catch (e: IOException) {
//        logger.error(e) { "加载资源失败 $path" }
        null
    }
}

fun loadResourceImage(path: String): Image? {
    return loadResourceBytes(path)?.let { Image.makeFromEncoded(it) }
}

/**
 * 渲染 SVG 图片
 */
fun SVGDOM.makeImage(width: Int, height: Int): Image {
    setContainerSize(width.toFloat(), height.toFloat())
    return Surface.makeRasterN32Premul(width, height).apply { render(canvas) }.makeImageSnapshot()
}

fun loadSVG(bytes: ByteArray): SVGDOM {
    return SVGDOM(Data.makeFromBytes(bytes))
}

/**
 * 从 resources 文件夹中加载 SVG
 */
fun loadResourceSVG(path: String): SVGDOM? {

    return loadResourceBytes(path)?.let { loadSVG(it) }
}

/**
 * 从 resources 文件夹中加载并渲染 SVG
 */
fun loadRenderSVG(path: String, width: Int = 100, height: Int = 100): Image? {
    return loadResourceSVG(path)?.makeImage(width, height)
}

fun ByteArray.makeImage() = Image.makeFromEncoded(this)

fun Color.makeRGB(hex: String): Int {
    require(hex.startsWith("#")) { "Hex format error: $hex" }
    require(hex.length == 7 || hex.length == 9) { "Hex length error: $hex" }
    return when (hex.length) {
        7 -> {
            makeRGB(
                Integer.valueOf(hex.substring(1, 3), 16),
                Integer.valueOf(hex.substring(3, 5), 16),
                Integer.valueOf(hex.substring(5), 16)
            )
        }

        9 -> {
            makeARGB(
                Integer.valueOf(hex.substring(1, 3), 16),
                Integer.valueOf(hex.substring(3, 5), 16),
                Integer.valueOf(hex.substring(5, 7), 16),
                Integer.valueOf(hex.substring(7), 16)
            )
        }

        else -> {
            WHITE
        }
    }
}

