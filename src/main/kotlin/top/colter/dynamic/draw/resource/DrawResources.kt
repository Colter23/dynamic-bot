package top.colter.dynamic.draw.resource

import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.IOException
import java.lang.ClassLoader.getSystemResourceAsStream


//internal val logger  = KotlinLogging.logger {}

private val resourceImageCache = mutableMapOf<String, Image>()

internal fun loadResourceBytes(path: String, name: String): ByteArray? {
    return try {
        getSystemResourceAsStream(path.removeSuffix("/") + "/" + name)?.readBytes()
    } catch (e: IOException) {
//        logger.error(e) { "加载资源失败 $path" }
        null
    }
}

internal fun loadResourceImage(path: String = "image", name: String): Image? {
    val pathName = path.removeSuffix("/") + "/" + name
    var image = resourceImageCache[pathName]
    if (image != null) return image
    image = loadResourceBytes(path, name)?.let { Image.makeFromEncoded(it) }
    image?.let { resourceImageCache[pathName] = it }
    return image
}

/**
 * 渲染 SVG 图片
 */
internal fun SVGDOM.makeImage(width: Int, height: Int): Image {
    setContainerSize(width.toFloat(), height.toFloat())
    return Surface.makeRasterN32Premul(width, height).apply { render(canvas) }.makeImageSnapshot()
}

internal fun loadSVG(bytes: ByteArray): SVGDOM {
    return SVGDOM(Data.makeFromBytes(bytes))
}

/**
 * 从 resources 文件夹中加载 SVG
 */
internal fun loadResourceSVG(path: String, name: String): SVGDOM? {
    return loadResourceBytes(path, name)?.let { loadSVG(it) }
}

/**
 * 从 resources 文件夹中加载并渲染 SVG
 */
internal fun loadRenderSVG(path: String, name: String, width: Int = 100, height: Int = 100): Image? {
    return loadResourceSVG(path, name)?.makeImage(width, height)
}

internal fun ByteArray.makeImage() = Image.makeFromEncoded(this)

