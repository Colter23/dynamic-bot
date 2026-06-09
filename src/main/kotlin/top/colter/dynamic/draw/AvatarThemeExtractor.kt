package top.colter.dynamic.draw

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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

        val sampleArea = AvatarSampleArea.of(image.width, image.height)
        val step = (sampleArea.size / TARGET_SAMPLE_GRID).coerceAtLeast(1)
        val buckets = linkedMapOf<Int, ColorBucket>()
        var circleSampleCount = 0

        var y = 0
        while (y < sampleArea.size) {
            var x = 0
            while (x < sampleArea.size) {
                val position = sampleArea.position(x, y)
                if (position != null) {
                    circleSampleCount += 1
                    val argb = image.getRGB(sampleArea.left + x, sampleArea.top + y)
                    val alpha = (argb ushr 24) and 0xff
                    if (alpha >= 200) {
                        val r = (argb ushr 16) and 0xff
                        val g = (argb ushr 8) and 0xff
                        val b = argb and 0xff
                        val hsb = java.awt.Color.RGBtoHSB(r, g, b, null)
                        val hue = hsb[0] * 360.0
                        val saturation = hsb[1]
                        val brightness = hsb[2]
                        if (isUsefulThemePixel(saturation, brightness)) {
                            val key = colorBucketKey(hsb[0], saturation, brightness)
                            val skinLike = isSkinLike(hue, saturation.toDouble(), brightness.toDouble())
                            val colorWeight = colorWeight(
                                saturation = saturation.toDouble(),
                                brightness = brightness.toDouble(),
                                distanceFromCenter = position.distanceFromCenter,
                            )
                            val scoreWeight = colorWeight * if (skinLike) SKIN_LIKE_SCORE_FACTOR else 1.0
                            buckets.getOrPut(key) { ColorBucket() }
                                .add(
                                    red = r,
                                    green = g,
                                    blue = b,
                                    hue = hue,
                                    colorWeight = colorWeight,
                                    scoreWeight = scoreWeight,
                                )
                        }
                    }
                }
                x += step
            }
            y += step
        }

        val minBucketSamples = max(
            MIN_BUCKET_SAMPLES,
            ceil(circleSampleCount * MIN_BUCKET_SAMPLE_RATIO).toInt(),
        )
        val selected = buckets.values
            .asSequence()
            .filter { it.sampleCount >= minBucketSamples && it.scoreWeight > 0.0 }
            .sortedByDescending { it.scoreWeight }
            .selectDistinctColors()
            .map { it.toHex() }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?: return null
        return selected
    }

    private fun Sequence<ColorBucket>.selectDistinctColors(): Sequence<ColorBucket> = sequence {
        val selected = mutableListOf<ColorBucket>()
        for (bucket in this@selectDistinctColors) {
            if (selected.isEmpty()) {
                selected.add(bucket)
                yield(bucket)
                continue
            }
            if (selected.size >= MAX_EXTRACTED_COLORS) break
            val primaryScore = selected.first().scoreWeight
            if (bucket.scoreWeight < primaryScore * SECONDARY_SCORE_RATIO) continue
            val closeToSelected = selected.any { it.hueDistance(bucket) < MIN_SECONDARY_HUE_DISTANCE }
            if (!closeToSelected) {
                selected.add(bucket)
                yield(bucket)
            }
        }
    }

    private fun isUsefulThemePixel(saturation: Float, brightness: Float): Boolean {
        if (saturation < MIN_SATURATION || brightness < MIN_BRIGHTNESS || brightness > MAX_BRIGHTNESS) return false
        // 高亮低饱和区域常见于皮肤、高光和白底，直接纳入会让浅色头像偏灰。
        return !(saturation < MIN_BRIGHT_SATURATION && brightness > BRIGHT_NEUTRAL_THRESHOLD)
    }

    private fun colorBucketKey(hue: Float, saturation: Float, brightness: Float): Int {
        return ((hue * HUE_BUCKET_COUNT).toInt().coerceIn(0, HUE_BUCKET_COUNT - 1) shl 8) or
            ((saturation * SATURATION_BUCKET_COUNT).toInt().coerceIn(0, SATURATION_BUCKET_COUNT - 1) shl 4) or
            (brightness * BRIGHTNESS_BUCKET_COUNT).toInt().coerceIn(0, BRIGHTNESS_BUCKET_COUNT - 1)
    }

    private fun colorWeight(
        saturation: Double,
        brightness: Double,
        distanceFromCenter: Double,
    ): Double {
        val centerWeight = 0.78 + (1.0 - distanceFromCenter).coerceIn(0.0, 1.0) * 0.38
        val saturationWeight = 0.55 + saturation * 1.15
        val brightnessWeight = 0.78 + (1.0 - (abs(brightness - 0.62) / 0.62).coerceAtMost(1.0)) * 0.22
        val pastelBoost = if (brightness >= 0.82 && saturation in 0.16..0.45) 1.18 else 1.0
        return centerWeight * saturationWeight * brightnessWeight * pastelBoost
    }

    private fun isSkinLike(hue: Double, saturation: Double, brightness: Double): Boolean {
        return hue in 12.0..48.0 && saturation in 0.16..0.58 && brightness >= 0.38
    }

    private class ColorBucket {
        var r: Double = 0.0
        var g: Double = 0.0
        var b: Double = 0.0
        var colorWeight: Double = 0.0
        var scoreWeight: Double = 0.0
        var hueSin: Double = 0.0
        var hueCos: Double = 0.0
        var sampleCount: Int = 0

        fun add(
            red: Int,
            green: Int,
            blue: Int,
            hue: Double,
            colorWeight: Double,
            scoreWeight: Double,
        ) {
            r += red * colorWeight
            g += green * colorWeight
            b += blue * colorWeight
            this.colorWeight += colorWeight
            this.scoreWeight += scoreWeight
            hueSin += sin(Math.toRadians(hue)) * colorWeight
            hueCos += cos(Math.toRadians(hue)) * colorWeight
            sampleCount += 1
        }

        fun hueDistance(other: ColorBucket): Double {
            val diff = abs(averageHue() - other.averageHue())
            return min(diff, 360.0 - diff)
        }

        fun toHex(): String {
            val divisor = colorWeight.coerceAtLeast(0.0001)
            return "#%02X%02X%02X".format(
                (r / divisor).roundToInt().coerceIn(0, 255),
                (g / divisor).roundToInt().coerceIn(0, 255),
                (b / divisor).roundToInt().coerceIn(0, 255),
            )
        }

        private fun averageHue(): Double {
            val divisor = colorWeight.coerceAtLeast(0.0001)
            val hue = Math.toDegrees(atan2(hueSin / divisor, hueCos / divisor))
            return if (hue < 0.0) hue + 360.0 else hue
        }
    }

    private data class AvatarSampleArea(
        val left: Int,
        val top: Int,
        val size: Int,
    ) {
        private val center: Double = size / 2.0
        private val radius: Double = size / 2.0

        fun position(x: Int, y: Int): SamplePosition? {
            val dx = (x + 0.5 - center) / radius
            val dy = (y + 0.5 - center) / radius
            val distance = sqrt(dx * dx + dy * dy)
            return if (distance <= 1.0) SamplePosition(distance) else null
        }

        companion object {
            fun of(width: Int, height: Int): AvatarSampleArea {
                val size = min(width.coerceAtLeast(1), height.coerceAtLeast(1))
                return AvatarSampleArea(
                    left = ((width - size) / 2).coerceAtLeast(0),
                    top = ((height - size) / 2).coerceAtLeast(0),
                    size = size,
                )
            }
        }
    }

    private data class SamplePosition(
        val distanceFromCenter: Double,
    )

    private companion object {
        const val TARGET_SAMPLE_GRID: Int = 96
        const val HUE_BUCKET_COUNT: Int = 36
        const val SATURATION_BUCKET_COUNT: Int = 5
        const val BRIGHTNESS_BUCKET_COUNT: Int = 5
        const val MAX_EXTRACTED_COLORS: Int = 3
        const val MIN_BUCKET_SAMPLES: Int = 12
        const val MIN_SECONDARY_HUE_DISTANCE: Double = 24.0
        const val SECONDARY_SCORE_RATIO: Double = 0.34
        const val MIN_BUCKET_SAMPLE_RATIO: Double = 0.004
        const val MIN_SATURATION: Float = 0.10f
        const val MIN_BRIGHTNESS: Float = 0.08f
        const val MAX_BRIGHTNESS: Float = 0.985f
        const val MIN_BRIGHT_SATURATION: Float = 0.16f
        const val BRIGHT_NEUTRAL_THRESHOLD: Float = 0.86f
        const val SKIN_LIKE_SCORE_FACTOR: Double = 0.38
    }
}
