package top.colter.dynamic.draw

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.draw.image.DynamicImageCache

public class AvatarThemeExtractor(
    private val bytesProvider: (MediaRef) -> ByteArray = DynamicImageCache::bytes,
) {
    public fun extractColors(avatar: MediaRef): List<String>? {
        if (avatar.uri.isBlank()) return null
        val image = runCatching {
            ImageIO.read(ByteArrayInputStream(bytesProvider(avatar)))
        }.getOrNull() ?: return null

        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val step = (maxOf(width, height) / 64).coerceAtLeast(1)
        val buckets = linkedMapOf<Int, ColorBucket>()

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val argb = image.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xff
                if (alpha >= 200) {
                    val r = (argb ushr 16) and 0xff
                    val g = (argb ushr 8) and 0xff
                    val b = argb and 0xff
                    val hsb = java.awt.Color.RGBtoHSB(r, g, b, null)
                    val saturation = hsb[1]
                    val brightness = hsb[2]
                    if (saturation >= 0.18f && brightness in 0.12f..0.94f) {
                        val key = ((hsb[0] * 24).toInt().coerceIn(0, 23) shl 8) or
                            ((saturation * 4).toInt().coerceIn(0, 3) shl 4) or
                            (brightness * 4).toInt().coerceIn(0, 3)
                        val weight = 0.35 + saturation * 0.45 + (1.0 - kotlin.math.abs(brightness - 0.58)) * 0.20
                        buckets.getOrPut(key) { ColorBucket() }.add(r, g, b, weight)
                    }
                }
                x += step
            }
            y += step
        }

        val selected = buckets.values
            .filter { it.weight > 0.0 }
            .maxByOrNull { it.weight }
            ?: return null
        return listOf(selected.toHex())
    }

    private class ColorBucket {
        var r: Double = 0.0
        var g: Double = 0.0
        var b: Double = 0.0
        var weight: Double = 0.0

        fun add(red: Int, green: Int, blue: Int, nextWeight: Double) {
            r += red * nextWeight
            g += green * nextWeight
            b += blue * nextWeight
            weight += nextWeight
        }

        fun toHex(): String {
            val divisor = weight.coerceAtLeast(0.0001)
            return "#%02X%02X%02X".format(
                (r / divisor).toInt().coerceIn(0, 255),
                (g / divisor).toInt().coerceIn(0, 255),
                (b / divisor).toInt().coerceIn(0, 255),
            )
        }
    }
}
