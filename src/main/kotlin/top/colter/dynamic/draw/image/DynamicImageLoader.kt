package top.colter.dynamic.draw.image

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.core.data.MediaReference
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.mediaReferences
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<CachedDynamicImageLoader>()

public fun interface DynamicImageLoader {
    public suspend fun load(update: SourceUpdate)
}

public class CachedDynamicImageLoader(
    private val config: ImageCacheConfig = ImageCacheConfig(),
    private val downloader: ImageDownloader = HttpImageDownloader(),
) : DynamicImageLoader {
    private val inFlight: ConcurrentHashMap<String, CompletableDeferred<Unit>> = ConcurrentHashMap()

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

        val semaphore = Semaphore(config.maxConcurrentDownloads.coerceAtLeast(1))
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
        } catch (t: Throwable) {
            logger.warn(t) { "图片下载失败，使用占位图：platform=$platformId，type=${imageType.name}，uri=${image.uri}" }
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

public class HttpImageDownloader(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : ImageDownloader {
    override suspend fun download(uri: String, timeoutMs: Long, maxBytes: Long): ByteArray {
        val parsed = runCatching { URI(uri) }.getOrNull()
        return when (parsed?.scheme?.lowercase()) {
            "http", "https" -> downloadHttp(uri, timeoutMs, maxBytes)
            "file" -> readFileLimited(Paths.get(parsed), maxBytes)
            null -> readFileLimited(Paths.get(uri), maxBytes)
            else -> readLocalPathOrThrow(uri, parsed.scheme, maxBytes)
        }
    }

    private suspend fun downloadHttp(uri: String, timeoutMs: Long, maxBytes: Long): ByteArray {
        val request = HttpRequest.newBuilder(URI(uri))
            .timeout(Duration.ofMillis(timeoutMs))
            .GET()
            .build()
        val response = client
            .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .await()
        if (response.statusCode() !in 200..299) {
            response.body().close()
            error("图片下载失败：HTTP ${response.statusCode()}")
        }
        response.headers().firstValueAsLong("Content-Length")
            .takeIf { it.isPresent && it.asLong > maxBytes }
            ?.let { error("图片超过大小限制：size=${it.asLong}，maxBytes=$maxBytes") }
        return response.body().use { input -> readBytesLimited(input, maxBytes) }
    }

    private fun readFileLimited(path: Path, maxBytes: Long): ByteArray {
        val size = Files.size(path)
        require(size <= maxBytes) { "图片文件超过大小限制：size=$size，maxBytes=$maxBytes" }
        return Files.newInputStream(path).use { input -> readBytesLimited(input, maxBytes) }
    }

    private fun readLocalPathOrThrow(uri: String, scheme: String?, maxBytes: Long): ByteArray {
        val path = runCatching { Paths.get(uri) }.getOrNull()
        if (path != null && Files.isRegularFile(path)) {
            return readFileLimited(path, maxBytes)
        }
        error("不支持的图片地址协议：$scheme")
    }

    private fun readBytesLimited(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalStateException("图片超过大小限制：maxBytes=$maxBytes")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
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
