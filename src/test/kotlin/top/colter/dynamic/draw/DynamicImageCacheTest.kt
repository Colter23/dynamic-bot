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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.draw.image.DynamicImageCache

class DynamicImageCacheTest {

    @Test
    fun fileNameForUriShouldKeepReadableNameAndAppendUriHash() {
        val avatar = DynamicImageCache.fileNameForUri("https://example.com/path/avatar.png?size=large")
        val badName = DynamicImageCache.fileNameForUri("https://example.com/path/bad:name.png")
        val blank = DynamicImageCache.fileNameForUri("https://example.com/path/")

        assertTrue(avatar.startsWith("avatar-"))
        assertTrue(avatar.endsWith(".png"))
        assertTrue(badName.startsWith("bad_name-"))
        assertTrue(badName.endsWith(".png"))
        assertTrue(blank.startsWith("image-"))
    }

    @Test
    fun storeShouldWriteUnderPlatformAndImageType() {
        val root = createTempDirectory("dynamic-image-cache-store")
        DynamicImageCache.configure(root)

        val image = MediaRef("https://example.com/images/avatar.png?x=1", MediaKind.AVATAR)
        val bytes = pngBytes(Color.RED)

        val path = DynamicImageCache.store(image, "bilibili", MediaKind.AVATAR, bytes)

        assertEquals(
            root.resolve("bilibili")
                .resolve("AVATAR")
                .resolve(DynamicImageCache.fileNameForUri(image.uri))
                .toAbsolutePath()
                .normalize(),
            path,
        )
        assertTrue(path.exists())
        assertContentEquals(bytes, DynamicImageCache.bytes(image))
    }

    @Test
    fun sameFileNameWithDifferentUriShouldNotHitExistingFile() {
        val root = createTempDirectory("dynamic-image-cache-hit")
        DynamicImageCache.configure(root)

        val first = MediaRef("https://i0.example.com/path/shared.jpg?one", MediaKind.IMAGE)
        val second = MediaRef("https://i1.example.com/other/shared.jpg?two", MediaKind.IMAGE)
        val bytes = pngBytes(Color.BLUE)

        DynamicImageCache.store(first, "bilibili", MediaKind.IMAGE, bytes)

        assertFalse(DynamicImageCache.loadFromDisk(second, "bilibili", MediaKind.IMAGE))
    }

    @Test
    fun resolverShouldReturnPlaceholderForMissingImage() {
        val root = createTempDirectory("dynamic-image-cache-placeholder")
        DynamicImageCache.configure(root)

        val image = DynamicImageCache.image(MediaRef("https://example.com/missing.png", MediaKind.IMAGE))

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
