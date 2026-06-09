package top.colter.dynamic.draw

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.testMedia

class AvatarThemeExtractorTest {
    @Test
    fun blankAvatarUriShouldReturnNull() {
        val extractor = AvatarThemeExtractor { error("不应读取空头像") }

        assertNull(extractor.extractColors(testMedia("", MediaKind.AVATAR)))
    }

    @Test
    fun circularAvatarAreaShouldIgnoreSquareCorners() {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        image.graphics2D {
            color = Color(230, 40, 60)
            fillRect(0, 0, image.width, image.height)
            color = Color(35, 145, 230)
            fillOval(0, 0, image.width, image.height)
        }

        val color = assertNotNull(extract(image)).first().toRgb()

        assertTrue(color.blue > color.red, "圆形头像外的角落颜色不应参与主题色判断")
    }

    @Test
    fun pastelHairShouldBeatSkinLikeFace() {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        image.graphics2D {
            color = Color(244, 182, 207)
            fillRect(0, 0, image.width, image.height)
            color = Color(240, 192, 176)
            fillOval(30, 28, 68, 76)
        }

        val color = assertNotNull(extract(image)).first().toRgb()

        assertTrue(color.blue > color.green, "粉色头发/背景应优先于肤色区域")
    }

    @Test
    fun tinyAccentOnNeutralAvatarShouldBeIgnored() {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        image.graphics2D {
            color = Color(242, 242, 242)
            fillRect(0, 0, image.width, image.height)
            color = Color(230, 40, 60)
            fillRect(63, 63, 3, 3)
        }

        assertNull(extract(image))
    }

    @Test
    fun supportedLogoAccentShouldStillGenerateTheme() {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        image.graphics2D {
            color = Color(30, 30, 30)
            fillRect(0, 0, image.width, image.height)
            color = Color.WHITE
            fillRect(28, 44, 72, 18)
            color = Color(125, 177, 147)
            fillRect(42, 62, 44, 24)
        }

        val color = assertNotNull(extract(image)).first().toRgb()

        assertTrue(color.green > color.red && color.green > color.blue, "有足够面积的 logo 强调色仍应可用于主题")
    }

    @Test
    fun distinctSecondaryColorShouldBeKeptForGradient() {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        image.graphics2D {
            color = Color(1, 24, 103)
            fillRect(0, 0, image.width, image.height)
            color = Color(238, 128, 0)
            fillRect(24, 42, 80, 54)
        }

        val colors = assertNotNull(extract(image))

        assertTrue(colors.size >= 2, "头像里足够明确的第二主题色应保留下来生成渐变")
        assertEquals(colors.distinct(), colors)
    }

    private fun extract(image: BufferedImage): List<String>? {
        return AvatarThemeExtractor { pngBytes(image) }
            .extractColors(testMedia("test://avatar.png", MediaKind.AVATAR))
    }

    private fun pngBytes(image: BufferedImage): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    private fun BufferedImage.graphics2D(block: java.awt.Graphics2D.() -> Unit) {
        val graphics = createGraphics()
        try {
            graphics.block()
        } finally {
            graphics.dispose()
        }
    }

    private data class Rgb(
        val red: Int,
        val green: Int,
        val blue: Int,
    )

    private fun String.toRgb(): Rgb {
        return Rgb(
            red = substring(1, 3).toInt(16),
            green = substring(3, 5).toInt(16),
            blue = substring(5, 7).toInt(16),
        )
    }
}
