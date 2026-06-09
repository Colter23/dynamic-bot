package top.colter.dynamic.admin

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisherInfo

class AdminMediaServiceTest {
    @Test
    fun imageShouldRejectUnregisteredAbsoluteLocalImageOutsideRuntimeRoot(): Unit = runBlocking {
        val file = createTempDirectory("admin-media-absolute").resolve("header.png").toFile()
        file.writeBytes(pngBytes(Color.MAGENTA))
        val service = AdminMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(maxImageMegabytes = 1.0),
                )
            },
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.image(file.absolutePath, platformId = "bilibili", kind = MediaKind.IMAGE)
        }

        assertTrue(error.message.orEmpty().contains("已登记为后台图片"))
    }

    @Test
    fun imageShouldReadRegisteredAbsolutePublisherBannerOutsideRuntimeRoot(): Unit = runBlocking {
        initTestDatabase("admin-media-registered-local")
        val file = createTempDirectory("admin-media-registered").resolve("header.png").toFile()
        file.writeBytes(pngBytes(Color.MAGENTA))
        PublisherRepository.upsertInfo(
            testPublisherInfo(
                banner = testMedia(file.absolutePath, MediaKind.COVER),
            ),
        )
        val service = AdminMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(maxImageMegabytes = 1.0),
                )
            },
            registeredLocalMediaLookup = DatabaseAdminRegisteredLocalMediaLookup,
        )

        val result = service.image(file.toURI().toString(), platformId = "bilibili", kind = MediaKind.COVER)

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
