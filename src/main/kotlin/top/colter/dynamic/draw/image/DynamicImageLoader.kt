package top.colter.dynamic.draw.image

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.core.data.MediaReference
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.mediaReferences
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<CachedDynamicImageLoader>()

/** 看守协程的轮询粒度。 */
private const val MIN_WATCHDOG_TICK_MILLIS: Long = 50L
private const val MAX_WATCHDOG_TICK_MILLIS: Long = 1_000L

/** 空闲超时之外的绝对上限，避免服务端用极慢的持续传输把下载拖成无限期。 */
private const val ABSOLUTE_TIMEOUT_MULTIPLIER: Long = 30L
private const val MIN_ABSOLUTE_TIMEOUT_MILLIS: Long = 180_000L

private fun absoluteTimeoutMillis(idleTimeoutMs: Long): Long {
    return maxOf(idleTimeoutMs * ABSOLUTE_TIMEOUT_MULTIPLIER, MIN_ABSOLUTE_TIMEOUT_MILLIS)
}

public fun interface DynamicImageLoader {
    public suspend fun load(update: SourceUpdate)
}

public class CachedDynamicImageLoader(
    private val config: ImageCacheConfig = ImageCacheConfig(),
    private val downloader: ImageDownloader = HttpImageDownloader(),
) : DynamicImageLoader {
    private val inFlight: ConcurrentHashMap<String, CompletableDeferred<Unit>> = ConcurrentHashMap()

    // 实例级上限：原先每次 load() 各自 new 一个信号量，等于没有全局下载并发上限。
    private val downloadPermits: Semaphore = Semaphore(config.maxConcurrentDownloads.coerceAtLeast(1))

    init {
        DynamicImageCache.configure(
            sourceRoot = Paths.get(config.sourceRoot),
            maxMemoryBytes = config.memoryMaxBytes,
            maxMemoryEntries = config.memoryMaxEntries,
            maxReadBytes = config.maxImageBytes,
        )
    }

    override suspend fun load(update: SourceUpdate) {
        val references = update.mediaReferences()
            .filter { it.media.uri.isNotBlank() }
        if (references.isEmpty()) return

        val semaphore = downloadPermits
        coroutineScope {
            references
                .map { reference ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            loadReference(update.platformId.value, reference)
                        }
                    }
                }
                .awaitAll()
        }
    }

    private suspend fun loadReference(platformId: String, reference: MediaReference) {
        val image = reference.media
        val imageType = reference.kind
        if (DynamicImageCache.loadFromDisk(image, platformId, imageType)) return

        val key = DynamicImageCache.cacheKey(platformId, imageType, image.uri)
        val waiter = CompletableDeferred<Unit>()
        val running = inFlight.putIfAbsent(key, waiter)
        if (running != null) {
            running.await()
            if (!DynamicImageCache.loadFromDisk(image, platformId, imageType)) {
                DynamicImageCache.putPlaceholder(image)
            }
            return
        }

        try {
            val bytes = downloader.download(
                image.uri,
                secondsToMillis(config.downloadTimeoutSeconds, minimumMillis = 1),
                config.maxImageBytes,
            )
            DynamicImageCache.store(image, platformId, imageType, bytes)
            waiter.complete(Unit)
        } catch (e: CancellationException) {
            waiter.completeExceptionally(e)
            throw e
        } catch (e: ImageDownloadException) {
            logger.warn {
                "图片下载失败，使用占位图：platform=$platformId，type=${imageType.name}，uri=${image.uri}，原因=${e.message}"
            }
            DynamicImageCache.putPlaceholder(image)
            waiter.complete(Unit)
        } catch (t: Throwable) {
            logger.warn(t) { "图片加载异常，使用占位图：platform=$platformId，type=${imageType.name}，uri=${image.uri}" }
            DynamicImageCache.putPlaceholder(image)
            waiter.complete(Unit)
        } finally {
            inFlight.remove(key, waiter)
        }
    }
}

public fun interface ImageDownloader {
    public suspend fun download(uri: String, timeoutMs: Long, maxBytes: Long): ByteArray
}

public class ImageDownloadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public class HttpImageDownloader(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : ImageDownloader {
    override suspend fun download(uri: String, timeoutMs: Long, maxBytes: Long): ByteArray {
        val parsed = runCatching { URI(uri) }.getOrNull()
        return try {
            when (parsed?.scheme?.lowercase()) {
                "http", "https" -> downloadHttp(uri, timeoutMs, maxBytes)
                "file" -> readFileLimited(Paths.get(parsed), maxBytes)
                null -> readFileLimited(Paths.get(uri), maxBytes)
                else -> readLocalPathOrThrow(uri, parsed.scheme, maxBytes)
            }
        } catch (e: ImageDownloadException) {
            throw e
        } catch (e: NoSuchFileException) {
            throw ImageDownloadException("本地图片不存在：${e.file}", e)
        } catch (e: InvalidPathException) {
            throw ImageDownloadException("图片地址不是有效路径：${e.input}", e)
        } catch (e: IllegalArgumentException) {
            throw ImageDownloadException(e.message ?: "图片地址无效", e)
        } catch (e: IOException) {
            throw ImageDownloadException(e.message ?: "图片读取失败", e)
        }
    }

    private suspend fun downloadHttp(uri: String, timeoutMs: Long, maxBytes: Long): ByteArray {
        val request = HttpRequest.newBuilder(URI(uri))
            .timeout(Duration.ofMillis(timeoutMs))
            .GET()
            .build()
        val response = client
            .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .awaitHttp(uri)
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw ImageDownloadException("HTTP ${response.statusCode()}")
        }
        response.headers().firstValueAsLong("Content-Length")
            .takeIf { it.isPresent && it.asLong > maxBytes }
            ?.let {
                response.body().close()
                throw ImageDownloadException("图片超过大小限制：size=${it.asLong}，maxBytes=$maxBytes")
            }
        return readBodyLimited(response.body(), uri, timeoutMs, maxBytes)
    }

    /**
     * HttpRequest.timeout 只覆盖到收到响应头为止，读取响应体不受它约束。
     * 连接在 body 传输中途断流时，read() 会一直阻塞，且阻塞读无法被协程取消打断，
     * 会把整条调用链（含检测任务和关闭流程）一起卡死。
     *
     * 这里在独立线程上做阻塞读取，并用“空闲超时”看守：连续 idleTimeoutMs 收不到新数据
     * 就关闭响应体让 read() 抛错退出。这样慢速但持续推进的大图不会被误杀，
     * 真正断流的连接也能在有限时间内失败；调用方协程被取消时同样会立刻关闭连接。
     */
    private suspend fun readBodyLimited(
        body: InputStream,
        uri: String,
        idleTimeoutMs: Long,
        maxBytes: Long,
    ): ByteArray = coroutineScope {
        val idleTimeout = idleTimeoutMs.coerceAtLeast(1L)
        val startedAt = System.currentTimeMillis()
        val lastProgressAt = AtomicLong(startedAt)
        val aborted = AtomicBoolean(false)
        val reader = CompletableFuture.supplyAsync(
            {
                body.use { input ->
                    readBytesLimited(input, maxBytes) {
                        lastProgressAt.set(System.currentTimeMillis())
                    }
                }
            },
            Dispatchers.IO.asExecutor(),
        )
        val abort: () -> Unit = {
            aborted.set(true)
            runCatching { body.close() }
            reader.cancel(true)
        }
        val watchdog = launch {
            val tick = (idleTimeout / 4).coerceIn(MIN_WATCHDOG_TICK_MILLIS, MAX_WATCHDOG_TICK_MILLIS)
            while (isActive) {
                delay(tick)
                val now = System.currentTimeMillis()
                if (now - lastProgressAt.get() < idleTimeout && now - startedAt < absoluteTimeoutMillis(idleTimeout)) {
                    continue
                }
                abort()
                return@launch
            }
        }
        try {
            awaitBody(reader, abort)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (aborted.get()) {
                throw ImageDownloadException("读取图片响应体超时：uri=$uri，idleTimeoutMs=$idleTimeout", t)
            }
            throw t
        } finally {
            watchdog.cancel()
        }
    }

    private fun readFileLimited(path: Path, maxBytes: Long): ByteArray {
        val size = Files.size(path)
        if (size > maxBytes) {
            throw ImageDownloadException("图片文件超过大小限制：size=$size，maxBytes=$maxBytes")
        }
        return Files.newInputStream(path).use { input -> readBytesLimited(input, maxBytes) }
    }

    private fun readLocalPathOrThrow(uri: String, scheme: String?, maxBytes: Long): ByteArray {
        val path = runCatching { Paths.get(uri) }.getOrNull()
        if (path != null && Files.isRegularFile(path)) {
            return readFileLimited(path, maxBytes)
        }
        throw ImageDownloadException("不支持的图片地址协议：$scheme")
    }

    private fun readBytesLimited(input: InputStream, maxBytes: Long, onProgress: () -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            onProgress()
            total += read
            if (total > maxBytes) {
                throw ImageDownloadException("图片超过大小限制：maxBytes=$maxBytes")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

/** 等待 body 读取完成；调用方被取消时先执行 [onCancel] 打断阻塞读，避免线程滞留。 */
private suspend fun <T> awaitBody(future: CompletableFuture<T>, onCancel: () -> Unit): T {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { onCancel() }
        future.whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException((error as? CompletionException)?.cause ?: error)
            }
        }
    }
}

private suspend fun <T> CompletableFuture<T>.awaitHttp(uri: String): T {
    return try {
        await()
    } catch (e: HttpTimeoutException) {
        throw ImageDownloadException("请求超时：$uri", e)
    } catch (e: IOException) {
        throw ImageDownloadException(e.message ?: "请求失败：$uri", e)
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value)
            } else {
                val cause = (error as? CompletionException)?.cause ?: error
                continuation.resumeWithException(cause)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
}

private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
    if (seconds <= 0.0 && minimumMillis <= 0) return 0
    return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
}
