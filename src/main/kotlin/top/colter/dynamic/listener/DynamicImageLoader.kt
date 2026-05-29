package top.colter.dynamic.listener

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
import top.colter.dynamic.core.data.collectMediaReferences
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.image.DynamicImageCache
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        DynamicImageCache.configure(Paths.get(config.sourceRoot))
    }

    override suspend fun load(update: SourceUpdate) {
        val references = collectMediaReferences(update)
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
            val bytes = downloader.download(image.uri, config.downloadTimeoutMs.coerceAtLeast(1))
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
    public suspend fun download(uri: String, timeoutMs: Long): ByteArray
}

public class HttpImageDownloader(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : ImageDownloader {
    override suspend fun download(uri: String, timeoutMs: Long): ByteArray {
        val parsed = URI(uri)
        return when (parsed.scheme?.lowercase()) {
            "http", "https" -> downloadHttp(uri, timeoutMs)
            "file" -> Files.readAllBytes(Paths.get(parsed))
            null -> Files.readAllBytes(Paths.get(uri))
            else -> error("不支持的图片地址协议：${parsed.scheme}")
        }
    }

    private suspend fun downloadHttp(uri: String, timeoutMs: Long): ByteArray {
        val request = HttpRequest.newBuilder(URI(uri))
            .timeout(Duration.ofMillis(timeoutMs))
            .GET()
            .build()
        val response = client
            .sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .await()
        if (response.statusCode() !in 200..299) {
            error("图片下载失败：HTTP ${response.statusCode()}")
        }
        return response.body()
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
