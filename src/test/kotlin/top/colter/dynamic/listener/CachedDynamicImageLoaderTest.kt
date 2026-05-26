package top.colter.dynamic.listener

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.ImageType
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformKind
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherType
import top.colter.dynamic.draw.DynamicImageCache

class CachedDynamicImageLoaderTest {

    @Test
    fun loadShouldDownloadOnceAndThenHitSameFileNameOnDisk(): Unit = runBlocking {
        val root = createTempDirectory("dynamic-loader-hit")
        val downloadCount = AtomicInteger()
        val bytes = pngBytes(Color.GREEN)
        val loader = loader(root.toString()) { _, _ ->
            downloadCount.incrementAndGet()
            bytes
        }

        loader.load(dynamic(face = LazyImage("https://i0.example.com/path/avatar.png?one")))
        loader.load(dynamic(face = LazyImage("https://i1.example.com/other/avatar.png?two")))

        assertEquals(1, downloadCount.get())
        assertTrue(root.resolve("bilibili").resolve("USER").resolve("avatar.png").exists())
    }

    @Test
    fun loadShouldMergeConcurrentDownloadsForSameCacheFile(): Unit = runBlocking {
        val root = createTempDirectory("dynamic-loader-merge")
        val downloadCount = AtomicInteger()
        val bytes = pngBytes(Color.YELLOW)
        val loader = loader(root.toString(), maxConcurrentDownloads = 4) { _, _ ->
            downloadCount.incrementAndGet()
            delay(50)
            bytes
        }

        loader.load(
            dynamic(
                face = LazyImage("https://i0.example.com/path/shared.png?face"),
                header = LazyImage("https://i1.example.com/other/shared.png?header"),
            )
        )

        assertEquals(1, downloadCount.get())
    }

    @Test
    fun loadShouldUsePlaceholderWhenDownloadFails(): Unit = runBlocking {
        val root = createTempDirectory("dynamic-loader-failure")
        val image = LazyImage("https://example.com/failure.png")
        val loader = loader(root.toString()) { _, _ -> error("boom") }

        loader.load(dynamic(face = image))

        assertTrue(DynamicImageCache.image(image).width > 0)
    }

    private fun loader(
        sourceRoot: String,
        maxConcurrentDownloads: Int = 8,
        downloader: ImageDownloader,
    ): CachedDynamicImageLoader {
        return CachedDynamicImageLoader(
            config = ImageCacheConfig(
                sourceRoot = sourceRoot,
                maxConcurrentDownloads = maxConcurrentDownloads,
            ),
            downloader = downloader,
        )
    }

    private fun dynamic(
        face: LazyImage,
        header: LazyImage? = null,
    ): Dynamic {
        return Dynamic(
            platform = PlatformDescriptor(
                id = "bilibili",
                name = "Bilibili",
                homepage = "https://www.bilibili.com",
                iconUri = "",
                kind = PlatformKind.PUBLISHER,
            ),
            dynamicId = "dynamic-${face.uri.hashCode()}",
            publisher = Publisher(
                id = 1,
                platformId = "bilibili",
                type = PublisherType.USER,
                externalId = "123",
                name = "Demo",
                state = EntityState.ACTIVE,
                face = face,
                header = header,
                createTime = 1,
                createUser = 1,
            ),
            time = 1,
            link = "https://t.bilibili.com/1",
        )
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
