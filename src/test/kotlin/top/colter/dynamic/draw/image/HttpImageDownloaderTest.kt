package top.colter.dynamic.draw.image

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskStatus
import top.colter.dynamic.task.DefaultTaskScheduler
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisherInfo

class HttpImageDownloaderTest {
    @Test
    fun idleTimeoutIsDownloadFailureEvenWhenReaderHasNotExited(): Unit = runBlocking {
        val body = DelayedCloseBody()
        try {
            val downloader = HttpImageDownloader(BodyHttpClient { body })
            val error = assertFailsWith<ImageDownloadException> {
                withTimeout(5_000) {
                    downloader.download(IMAGE_URI, timeoutMs = 100, maxBytes = 1024)
                }
            }

            assertTrue(error.message.orEmpty().contains("超时"))
            assertTrue(body.closed.isCompleted)
            assertEquals(1L, body.finished.count)
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            body.releaseReader()
        }
    }

    @Test
    fun callerCancellationStillClosesBodyAndPropagates(): Unit = runBlocking {
        val body = DelayedCloseBody()
        try {
            val downloader = HttpImageDownloader(BodyHttpClient { body })
            val download = async {
                downloader.download(IMAGE_URI, timeoutMs = 60_000, maxBytes = 1024)
            }
            withTimeout(5_000) { body.readStarted.await() }

            download.cancel(CancellationException("requested stop"))
            val error = assertFailsWith<CancellationException> {
                withTimeout(5_000) { download.await() }
            }

            assertEquals("requested stop", error.message)
            assertTrue(body.closed.isCompleted)
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            body.releaseReader()
        }
    }

    @Test
    fun enclosingTimeoutRemainsCancellation(): Unit = runBlocking {
        val body = DelayedCloseBody()
        try {
            val downloader = HttpImageDownloader(BodyHttpClient { body })
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) {
                    downloader.download(IMAGE_URI, timeoutMs = 60_000, maxBytes = 1024)
                }
            }

            assertTrue(body.closed.isCompleted)
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            body.releaseReader()
        }
    }

    @Test
    fun pollingUsesPlaceholderAfterImageTimeoutAndRunsNextRound(): Unit = runBlocking {
        val body = DelayedCloseBody()
        val sourceRoot = createTempDirectory("image-timeout-polling")
        val requests = AtomicInteger()
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }
        val loader = CachedDynamicImageLoader(
            config = ImageCacheConfig(sourceRoot = sourceRoot.toString(), downloadTimeoutSeconds = 0.1),
            downloader = HttpImageDownloader(BodyHttpClient {
                if (requests.incrementAndGet() == 1) body else ByteArrayInputStream(bytes)
            }),
        )
        val image = MediaRef(IMAGE_URI, MediaKind.AVATAR)
        val update = testDynamicUpdate(publisher = testPublisherInfo(avatar = image))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scheduler = DefaultTaskScheduler(scope)
        val placeholderUsed = CompletableDeferred<Boolean>()
        val recovered = CompletableDeferred<Unit>()
        var rounds = 0
        try {
            scheduler.start(
                TaskDefinition(
                    id = "image-timeout-polling",
                    schedule = TaskSchedule.FixedDelay(20.milliseconds),
                    action = {
                        rounds++
                        loader.load(update)
                        if (rounds == 1) {
                            placeholderUsed.complete(DynamicImageCache.contains(image))
                        } else {
                            recovered.complete(Unit)
                        }
                    },
                ),
            )
            withTimeout(5_000) { recovered.await() }

            assertTrue(placeholderUsed.await())
            assertTrue(scheduler.isRunning("image-timeout-polling"))
            val snapshot = assertNotNull(scheduler.snapshot("image-timeout-polling"))
            assertEquals(TaskStatus.RUNNING, snapshot.status)
            assertNull(snapshot.lastErrorSummary)
            assertEquals(2, requests.get())
            assertTrue(bytes.contentEquals(DynamicImageCache.bytes(image)))
        } finally {
            scheduler.shutdown()
            scope.cancel()
            body.releaseReader()
        }
    }

    // close() happens before the reader exits, forcing Future cancellation to win the race.
    private class DelayedCloseBody : InputStream() {
        val readStarted = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val finished = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun read(): Int = error("Buffered read expected")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readStarted.complete(Unit)
            try {
                check(release.await(10, TimeUnit.SECONDS)) { "Reader was not released" }
                throw IOException("Body closed")
            } finally {
                finished.countDown()
            }
        }

        override fun close() {
            closed.complete(Unit)
        }

        fun releaseReader() {
            release.countDown()
            if (readStarted.isCompleted) {
                assertTrue(finished.await(5, TimeUnit.SECONDS), "Reader should exit after release")
            }
        }
    }

    private class BodyHttpClient(private val bodyProvider: () -> InputStream) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NORMAL
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
            error("Async request expected")

        override fun <T> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            @Suppress("UNCHECKED_CAST")
            val body = bodyProvider() as T
            return CompletableFuture.completedFuture(object : HttpResponse<T> {
                override fun statusCode(): Int = 200
                override fun request(): HttpRequest = request
                override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
                override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
                override fun body(): T = body
                override fun sslSession(): Optional<SSLSession> = Optional.empty()
                override fun uri(): URI = request.uri()
                override fun version(): Version = Version.HTTP_1_1
            })
        }

        override fun <T> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, handler)
    }

    private companion object {
        const val IMAGE_URI = "https://diagnostic.invalid/stall.png"
    }
}
