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
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.ImageType
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.LazyImageReference
import top.colter.dynamic.core.data.collectLazyImageReferences
import top.colter.dynamic.draw.image.DynamicImageCache
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public fun interface DynamicImageLoader {
    public suspend fun load(dynamic: Dynamic)
}

public class CachedDynamicImageLoader(
    private val config: ImageCacheConfig = ImageCacheConfig(),
    private val downloader: ImageDownloader = HttpImageDownloader(),
) : DynamicImageLoader {
    private val inFlight: ConcurrentHashMap<String, CompletableDeferred<Unit>> = ConcurrentHashMap()

    init {
        DynamicImageCache.configure(Paths.get(config.sourceRoot))
    }

    override suspend fun load(dynamic: Dynamic) {
        val references = collectLazyImageReferences(dynamic)
            .filter { it.image.uri.isNotBlank() }
        if (references.isEmpty()) return

        val semaphore = Semaphore(config.maxConcurrentDownloads.coerceAtLeast(1))
        coroutineScope {
            references
                .map { reference ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            loadReference(dynamic.platform.id, reference)
                        }
                    }
                }
                .awaitAll()
        }
    }

    private suspend fun loadReference(platformId: String, reference: LazyImageReference) {
        val image = reference.image
        val imageType = reference.type
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
            println(
                "image download failed: platformId=$platformId, imageType=${imageType.name}, " +
                    "uri=${image.uri}, error=${t.message}"
            )
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
            else -> error("Unsupported image URI scheme: ${parsed.scheme}")
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
            error("Image download failed with HTTP ${response.statusCode()}")
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
