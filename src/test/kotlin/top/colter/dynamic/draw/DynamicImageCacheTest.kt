package top.colter.dynamic.draw

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.ImageType
import top.colter.dynamic.core.data.LazyImage

class DynamicImageCacheTest {

    @Test
    fun fileNameForUriShouldUseOriginalUrlFileName() {
        assertEquals("avatar.png", DynamicImageCache.fileNameForUri("https://example.com/path/avatar.png?size=large"))
        assertEquals("测试 图.jpg", DynamicImageCache.fileNameForUri("https://example.com/%E6%B5%8B%E8%AF%95%20%E5%9B%BE.jpg"))
        assertEquals("bad_name.png", DynamicImageCache.fileNameForUri("https://example.com/path/bad:name.png"))
        assertEquals("image.img", DynamicImageCache.fileNameForUri("https://example.com/path/"))
    }

    @Test
    fun storeShouldWriteUnderPlatformAndImageType() {
        val root = createTempDirectory("dynamic-image-cache-store")
        DynamicImageCache.configure(root)

        val image = LazyImage("https://example.com/images/avatar.png?x=1")
        val bytes = pngBytes(Color.RED)

        val path = DynamicImageCache.store(image, "bilibili", ImageType.USER, bytes)

        assertEquals(root.resolve("bilibili").resolve("USER").resolve("avatar.png").toAbsolutePath().normalize(), path)
        assertTrue(path.exists())
        assertContentEquals(bytes, DynamicImageCache.bytes(image))
    }

    @Test
    fun sameFileNameShouldHitExistingFile() {
        val root = createTempDirectory("dynamic-image-cache-hit")
        DynamicImageCache.configure(root)

        val first = LazyImage("https://i0.example.com/path/shared.jpg?one")
        val second = LazyImage("https://i1.example.com/other/shared.jpg?two")
        val bytes = pngBytes(Color.BLUE)

        DynamicImageCache.store(first, "bilibili", ImageType.IMAGES, bytes)

        assertTrue(DynamicImageCache.loadFromDisk(second, "bilibili", ImageType.IMAGES))
        assertContentEquals(bytes, DynamicImageCache.bytes(second))
    }

    @Test
    fun resolverShouldReturnPlaceholderForMissingImage() {
        val root = createTempDirectory("dynamic-image-cache-placeholder")
        DynamicImageCache.configure(root)

        val image = DynamicImageCache.image(LazyImage("https://example.com/missing.png"))

        assertTrue(image.width > 0)
        assertTrue(image.height > 0)
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
