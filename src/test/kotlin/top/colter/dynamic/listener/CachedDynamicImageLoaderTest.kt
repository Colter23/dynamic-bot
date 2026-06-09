package top.colter.dynamic.listener

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.draw.image.CachedDynamicImageLoader
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.draw.image.HttpImageDownloader
import top.colter.dynamic.draw.image.ImageDownloadException
import top.colter.dynamic.draw.image.ImageDownloader
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisherInfo

class CachedDynamicImageLoaderTest {

    @Test
    fun loadShouldUseFullUriHashForSameFileNameOnDisk(): Unit = runBlocking {
        val root = createTempDirectory("dynamic-loader-hit")
        val downloadCount = AtomicInteger()
        val bytes = pngBytes(Color.GREEN)
        val loader = loader(root.toString()) { _, _, _ ->
            downloadCount.incrementAndGet()
            bytes
        }
        val first = "https://i0.example.com/path/avatar.png?one"
        val second = "https://i1.example.com/other/avatar.png?two"

        loader.load(dynamic(avatar = MediaRef(first, MediaKind.AVATAR)))
        loader.load(dynamic(avatar = MediaRef(second, MediaKind.AVATAR)))

        assertEquals(2, downloadCount.get())
        assertTrue(root.resolve("bilibili").resolve("AVATAR").resolve(DynamicImageCache.fileNameForUri(first)).exists())
        assertTrue(root.resolve("bilibili").resolve("AVATAR").resolve(DynamicImageCache.fileNameForUri(second)).exists())
    }

    @Test
    fun loadShouldMergeConcurrentDownloadsForSameCacheFile(): Unit = runBlocking {
        val root = createTempDirectory("dynamic-loader-merge")
        val downloadCount = AtomicInteger()
        val bytes = pngBytes(Color.YELLOW)
        val loader = loader(root.toString(), maxConcurrentDownloads = 4) { _, _, _ ->
            downloadCount.incrementAndGet()
            delay(50)
            bytes
        }
        val shared = MediaRef("https://i0.example.com/path/shared.png?same", MediaKind.AVATAR)

        loader.load(dynamic(avatar = shared, pendant = shared))

        assertEquals(1, downloadCount.get())
    }

    @Test
    fun loadShouldUsePlaceholderWhenDownloadFails(): Unit = runBlocking {
        val root = createTempDirectory("dynamic-loader-failure")
        val image = MediaRef("https://example.com/failure.png", MediaKind.AVATAR)
        val loader = loader(root.toString()) { _, _, _ -> error("boom") }

        loader.load(dynamic(avatar = image))

        assertTrue(DynamicImageCache.image(image).width > 0)
    }

    @Test
    fun loadShouldPassConfiguredMaxImageBytesToDownloader(): Unit = runBlocking {
        val root = createTempDirectory("dynamic-loader-max-bytes")
        var observedMaxBytes = 0L
        val loader = loader(root.toString(), maxImageMegabytes = 123.0 / 1024.0 / 1024.0) { _, _, maxBytes ->
            observedMaxBytes = maxBytes
            pngBytes(Color.CYAN)
        }

        loader.load(dynamic(avatar = MediaRef("https://example.com/limited.png", MediaKind.AVATAR)))

        assertEquals(123, observedMaxBytes)
    }

    @Test
    fun httpDownloaderShouldRejectOversizedLocalFiles(): Unit = runBlocking {
        val root = createTempDirectory("dynamic-loader-local-limit")
        val file = root.resolve("large.bin")
        file.writeBytes(ByteArray(16))

        try {
            HttpImageDownloader().download(file.toString(), timeoutMs = 1_000, maxBytes = 8)
            fail("oversized file should be rejected")
        } catch (_: ImageDownloadException) {
        }
    }

    @Test
    fun httpDownloaderShouldReadAbsoluteLocalFiles(): Unit = runBlocking {
        val root = createTempDirectory("dynamic-loader-local-absolute")
        val file = root.resolve("header.png")
        val bytes = pngBytes(Color.MAGENTA)
        file.writeBytes(bytes)

        val loaded = HttpImageDownloader().download(file.toAbsolutePath().toString(), timeoutMs = 1_000, maxBytes = 1024)

        assertEquals(bytes.size, loaded.size)
    }

    private fun loader(
        sourceRoot: String,
        maxConcurrentDownloads: Int = 8,
        maxImageMegabytes: Double = 20.0,
        downloader: ImageDownloader,
    ): CachedDynamicImageLoader {
        return CachedDynamicImageLoader(
            config = ImageCacheConfig(
                sourceRoot = sourceRoot,
                maxConcurrentDownloads = maxConcurrentDownloads,
                maxImageMegabytes = maxImageMegabytes,
            ),
            downloader = downloader,
        )
    }

    private fun dynamic(
        avatar: MediaRef,
        banner: MediaRef? = null,
        pendant: MediaRef? = null,
    ) = testDynamicUpdate(
        externalId = "dynamic-${avatar.uri.hashCode()}",
        publisher = testPublisherInfo(
            avatar = avatar,
            banner = banner,
            pendant = pendant,
        ),
    )

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
