package top.colter.dynamic.listener

import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    /**
     * 响应头正常返回、body 传到一半断流时，HttpRequest.timeout 不会生效。
     * 这里必须靠读取超时失败退出，否则检测协程会被永久阻塞（issue #1）。
     */
    @Test
    fun httpDownloaderShouldFailFastWhenBodyStalls(): Unit = runBlocking {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stall.png") { exchange ->
            exchange.sendResponseHeaders(200, 1024)
            exchange.responseBody.write(ByteArray(8))
            exchange.responseBody.flush()
            release.await(10, TimeUnit.SECONDS)
            runCatching { exchange.close() }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/stall.png"
            val startAt = System.currentTimeMillis()
            try {
                HttpImageDownloader().download(url, timeoutMs = 500, maxBytes = 1_000_000)
                fail("stalled body should time out")
            } catch (e: ImageDownloadException) {
                assertTrue(e.message.orEmpty().contains("超时"), "message=${e.message}")
            }
            val elapsed = System.currentTimeMillis() - startAt
            assertTrue(elapsed < 5_000, "stalled body should not block: elapsed=${elapsed}ms")
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    /**
     * 空闲超时不能误杀“慢但持续有数据”的下载：整体耗时远超超时值，但每次间隔都很短。
     */
    @Test
    fun httpDownloaderShouldTolerateSlowButProgressingBody(): Unit = runBlocking {
        val chunks = 20
        val chunkSize = 16
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow.png") { exchange ->
            exchange.sendResponseHeaders(200, (chunks * chunkSize).toLong())
            exchange.responseBody.use { output ->
                repeat(chunks) {
                    output.write(ByteArray(chunkSize))
                    output.flush()
                    Thread.sleep(100)
                }
            }
            runCatching { exchange.close() }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/slow.png"
            // 空闲超时 600ms，每 100ms 有数据；整体耗时约 2s，远大于空闲超时
            val bytes = HttpImageDownloader().download(url, timeoutMs = 600, maxBytes = 1_000_000)
            assertEquals(chunks * chunkSize, bytes.size)
        } finally {
            server.stop(0)
        }
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
