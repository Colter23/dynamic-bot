package top.colter.dynamic.admin

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.MediaKind

class AdminMediaServiceTest {
    @Test
    fun imageShouldReadAbsoluteLocalImageOutsideRuntimeRoot(): Unit = runBlocking {
        val file = createTempDirectory("admin-media-absolute").resolve("header.png").toFile()
        file.writeBytes(pngBytes(Color.MAGENTA))
        val service = AdminMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(maxImageMegabytes = 1.0),
                )
            },
        )

        val result = service.image(file.absolutePath, platformId = "bilibili", kind = MediaKind.IMAGE)

        assertEquals("image", result.contentType.contentType)
        assertEquals("png", result.contentType.contentSubtype)
        assertTrue(result.bytes.isNotEmpty())
    }

    private fun pngBytes(color: Color): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, 2, 2)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }
}
