package top.colter.dynamic.admin

import io.ktor.http.ContentType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.draw.image.DynamicImageCache
import kotlin.math.roundToLong

public data class AdminMediaResult(
    val bytes: ByteArray,
    val contentType: ContentType,
)

public class AdminMediaService(
    private val configProvider: () -> MainDynamicConfig = { MainDynamicConfig() },
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    public suspend fun image(uri: String, platformId: String?, kind: MediaKind): AdminMediaResult {
        val imageUri = uri.trim()
        require(imageUri.isNotBlank()) { "图片地址不能为空" }

        val resolvedPlatformId = platformId?.trim()?.takeIf { it.isNotBlank() } ?: "admin"
        val image = MediaRef(uri = imageUri, kind = kind)
        val cachePath = DynamicImageCache.pathFor(resolvedPlatformId, kind, imageUri)

        if (DynamicImageCache.loadFromDisk(image, resolvedPlatformId, kind)) {
            val bytes = DynamicImageCache.bytes(image)
            return AdminMediaResult(bytes, contentTypeFor(cachePath, imageUri, bytes))
        }

        readLocalImage(imageUri)?.let { local ->
            DynamicImageCache.store(image, resolvedPlatformId, kind, local.bytes)
            return AdminMediaResult(local.bytes, contentTypeFor(local.path, imageUri, local.bytes))
        }

        val downloaded = downloadHttpImage(
            imageUri,
            secondsToMillis(configProvider().imageCache.downloadTimeoutSeconds, minimumMillis = 1),
        )
        DynamicImageCache.store(image, resolvedPlatformId, kind, downloaded.bytes)
        return AdminMediaResult(
            bytes = downloaded.bytes,
            contentType = downloaded.contentType ?: contentTypeFor(cachePath, imageUri, downloaded.bytes),
        )
    }

    private suspend fun readLocalImage(uri: String): LocalImage? = withContext(Dispatchers.IO) {
        val path = localPathFor(uri) ?: return@withContext null
        if (!Files.isRegularFile(path)) return@withContext null
        LocalImage(path, Files.readAllBytes(path))
    }

    private fun localPathFor(uri: String): Path? {
        if (windowsAbsolutePath.matches(uri) || uri.startsWith("\\\\")) {
            return runCatching { Paths.get(uri) }.getOrNull()
        }

        val parsed = runCatching { URI(uri) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (scheme == "file") return runCatching { Paths.get(parsed) }.getOrNull()
        if (scheme != null) return null

        return runCatching { Paths.get(uri) }.getOrNull()
    }

    private suspend fun downloadHttpImage(uri: String, timeoutMs: Long): DownloadedImage = withContext(Dispatchers.IO) {
        val parsed = runCatching { URI(uri) }
            .getOrElse { throw IllegalArgumentException("图片地址无效：$uri") }
        val scheme = parsed.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "本地图片不存在，且不是可下载的 HTTP/HTTPS 地址" }

        val requestBuilder = HttpRequest.newBuilder(parsed)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("User-Agent", USER_AGENT)
            .GET()
        refererFor(parsed)?.let { requestBuilder.header("Referer", it) }

        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("图片下载失败：HTTP ${response.statusCode()}")
        }
        DownloadedImage(
            bytes = response.body(),
            contentType = contentTypeFromHeader(response.headers().firstValue("Content-Type").orElse(null)),
        )
    }

    private fun refererFor(uri: URI): String? {
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://$host$port/"
    }

    private fun contentTypeFor(path: Path?, uri: String, bytes: ByteArray): ContentType {
        return contentTypeFromMagic(bytes)
            ?: path?.let { runCatching { Files.probeContentType(it) }.getOrNull()?.let(::contentTypeFromHeader) }
            ?: contentTypeFromExtension(uri)
            ?: OCTET_STREAM
    }

    private fun contentTypeFromMagic(bytes: ByteArray): ContentType? {
        return when {
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes.hasAscii(1, "PNG") -> ContentType("image", "png")
            bytes.size >= 3 &&
                bytes[0] == 0xff.toByte() &&
                bytes[1] == 0xd8.toByte() &&
                bytes[2] == 0xff.toByte() -> ContentType("image", "jpeg")
            bytes.hasAscii(0, "GIF87a") || bytes.hasAscii(0, "GIF89a") -> ContentType("image", "gif")
            bytes.hasAscii(0, "RIFF") && bytes.hasAscii(8, "WEBP") -> ContentType("image", "webp")
            bytes.dropLeadingWhitespace().hasAscii(0, "<svg") -> ContentType("image", "svg+xml")
            else -> null
        }
    }

    private fun contentTypeFromExtension(uri: String): ContentType? {
        val path = uri.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".png") -> ContentType("image", "png")
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> ContentType("image", "jpeg")
            path.endsWith(".gif") -> ContentType("image", "gif")
            path.endsWith(".webp") -> ContentType("image", "webp")
            path.endsWith(".svg") -> ContentType("image", "svg+xml")
            else -> null
        }
    }

    private fun contentTypeFromHeader(value: String?): ContentType? {
        val mediaType = value
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.contains('/') }
            ?: return null
        val parts = mediaType.split('/', limit = 2)
        return ContentType(parts[0], parts[1])
    }

    private fun ByteArray.hasAscii(offset: Int, value: String): Boolean {
        if (size < offset + value.length) return false
        return value.indices.all { this[offset + it] == value[it].code.toByte() }
    }

    private fun ByteArray.dropLeadingWhitespace(): ByteArray {
        val start = indexOfFirst { it !in whitespaceBytes }
        return if (start <= 0) this else copyOfRange(start, size)
    }

    private data class LocalImage(
        val path: Path,
        val bytes: ByteArray,
    )

    private data class DownloadedImage(
        val bytes: ByteArray,
        val contentType: ContentType?,
    )

    private companion object {
        private val windowsAbsolutePath = Regex("""^[A-Za-z]:[\\/].+""")
        private val whitespaceBytes = setOf(' '.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\t'.code.toByte())
        private val OCTET_STREAM = ContentType("application", "octet-stream")
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36"
    }
}

private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
    if (seconds <= 0.0 && minimumMillis <= 0) return 0
    return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
}
