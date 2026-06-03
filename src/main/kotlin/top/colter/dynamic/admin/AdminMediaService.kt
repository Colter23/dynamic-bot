package top.colter.dynamic.admin

import io.ktor.http.ContentType
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.colter.dynamic.ImageCacheConfig
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

        val imageCacheConfig = configProvider().imageCache
        val resolvedPlatformId = platformId?.trim()?.takeIf { it.isNotBlank() } ?: "admin"
        val image = MediaRef(uri = imageUri, kind = kind)
        val cachePath = DynamicImageCache.pathFor(resolvedPlatformId, kind, imageUri)

        localPathFor(imageUri, imageCacheConfig)?.let { localPath ->
            val local = readLocalImage(localPath, imageCacheConfig.maxImageBytes)
                ?: throw NoSuchElementException("本地图片不存在：$imageUri")
            return AdminMediaResult(local.bytes, local.contentType)
        }

        if (DynamicImageCache.loadFromDisk(image, resolvedPlatformId, kind)) {
            val bytes = DynamicImageCache.bytes(image)
            val contentType = contentTypeFor(cachePath, imageUri, bytes)
            requireImageContentType(contentType)
            return AdminMediaResult(bytes, contentType)
        }

        val downloaded = downloadHttpImage(
            imageUri,
            secondsToMillis(imageCacheConfig.downloadTimeoutSeconds, minimumMillis = 1),
            imageCacheConfig.maxImageBytes,
        )
        DynamicImageCache.store(image, resolvedPlatformId, kind, downloaded.bytes)
        return AdminMediaResult(
            bytes = downloaded.bytes,
            contentType = downloaded.contentType,
        )
    }

    private suspend fun readLocalImage(path: Path, maxBytes: Long): LocalImage? = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@withContext null
        val size = Files.size(path)
        require(size <= maxBytes) { "本地图片超过大小限制：size=$size，maxBytes=$maxBytes" }
        val bytes = Files.readAllBytes(path)
        require(bytes.size.toLong() <= maxBytes) { "本地图片超过大小限制：maxBytes=$maxBytes" }
        val contentType = contentTypeFor(path, path.toString(), bytes)
        requireImageContentType(contentType)
        LocalImage(path, bytes, contentType)
    }

    private fun localPathFor(uri: String, config: ImageCacheConfig): Path? {
        val rawPath = localRawPathFor(uri) ?: return null
        val resolvedPath = resolveLocalPath(rawPath)
        val checkedPath = runCatching { resolvedPath.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrDefault(resolvedPath)
        require(isAllowedLocalPath(checkedPath, config)) {
            "本地图片路径必须位于运行目录或图片缓存目录内"
        }
        return checkedPath
    }

    private fun localRawPathFor(uri: String): Path? {
        if (windowsAbsolutePath.matches(uri) || uri.startsWith("\\\\")) {
            return runCatching { Paths.get(uri) }.getOrNull()
        }

        val parsed = runCatching { URI(uri) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (scheme == "file") return runCatching { Paths.get(parsed) }.getOrNull()
        if (scheme != null) return null

        return runCatching { Paths.get(uri) }.getOrNull()
    }

    private fun resolveLocalPath(path: Path): Path {
        return if (path.isAbsolute) {
            path.normalize()
        } else {
            runtimeRoot.resolve(path).normalize()
        }
    }

    private fun isAllowedLocalPath(path: Path, config: ImageCacheConfig): Boolean {
        return allowedLocalRoots(config).any { root -> path.startsWith(root) }
    }

    private fun allowedLocalRoots(config: ImageCacheConfig): List<Path> {
        return listOf(
            runtimeRoot,
            resolveConfiguredRoot(config.sourceRoot),
            resolveConfiguredRoot(config.renderedRoot),
        ).distinct()
    }

    private fun resolveConfiguredRoot(value: String): Path {
        val path = Paths.get(value)
        val resolved = if (path.isAbsolute) path else runtimeRoot.resolve(path)
        return runCatching { resolved.toRealPath(LinkOption.NOFOLLOW_LINKS) }
            .getOrElse { resolved.toAbsolutePath().normalize() }
    }

    private suspend fun downloadHttpImage(uri: String, timeoutMs: Long, maxBytes: Long): DownloadedImage = withContext(Dispatchers.IO) {
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

        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("图片下载失败：HTTP ${response.statusCode()}")
        }
        response.headers()
            .firstValue("Content-Length")
            .orElse(null)
            ?.toLongOrNull()
            ?.takeIf { it > maxBytes }
            ?.let { throw IllegalStateException("图片下载超过大小限制：size=$it，maxBytes=$maxBytes") }

        val bytes = response.body().use { input -> readBytesLimited(input, maxBytes) }
        val headerContentType = contentTypeFromHeader(response.headers().firstValue("Content-Type").orElse(null))
        val detectedContentType = contentTypeFor(null, uri, bytes)
        val contentType = if (headerContentType?.isImage() == true) headerContentType else detectedContentType
        requireImageContentType(contentType)
        DownloadedImage(
            bytes = bytes,
            contentType = contentType,
        )
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
                throw IllegalStateException("图片下载超过大小限制：maxBytes=$maxBytes")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
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

    private fun requireImageContentType(contentType: ContentType) {
        require(contentType.isImage()) {
            "资源不是受支持的图片类型：$contentType"
        }
    }

    private fun ContentType.isImage(): Boolean {
        return contentType.equals("image", ignoreCase = true)
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
        val contentType: ContentType,
    )

    private data class DownloadedImage(
        val bytes: ByteArray,
        val contentType: ContentType,
    )

    private companion object {
        private val runtimeRoot: Path = Paths.get("").toAbsolutePath().normalize()
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
