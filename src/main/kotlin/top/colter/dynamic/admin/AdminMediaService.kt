package top.colter.dynamic.admin

import io.ktor.http.ContentType
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
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
    private val registeredLocalMediaLookup: AdminRegisteredLocalMediaLookup = EmptyAdminRegisteredLocalMediaLookup,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
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
        val checkedPath = AdminLocalMediaPath.fromUri(uri) ?: return null
        require(isAllowedLocalPath(checkedPath, config) || registeredLocalMediaLookup.contains(checkedPath)) {
            "本地图片路径必须位于运行目录、图片缓存目录内，或已登记为后台图片"
        }
        return checkedPath
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
        var currentUri = runCatching { URI(uri) }
            .getOrElse { throw IllegalArgumentException("图片地址无效：$uri") }

        repeat(MAX_REDIRECTS + 1) {
            val scheme = currentUri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") { "本地图片不存在，且不是可下载的 HTTP/HTTPS 地址" }
            requirePublicHost(currentUri)

            val requestBuilder = HttpRequest.newBuilder(currentUri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("User-Agent", USER_AGENT)
                .GET()
            refererFor(currentUri)?.let { requestBuilder.header("Referer", it) }

            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val status = response.statusCode()

            if (status in 300..399) {
                response.body().use { it.readAllBytes() }
                val location = response.headers().firstValue("Location").orElse(null)
                    ?: throw IllegalStateException("图片下载失败：重定向缺少 Location")
                currentUri = runCatching { currentUri.resolve(location) }
                    .getOrElse { throw IllegalArgumentException("图片地址无效：$location") }
                return@repeat
            }

            if (status !in 200..299) {
                response.body().use { it.readAllBytes() }
                throw IllegalStateException("图片下载失败：HTTP $status")
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
            return@withContext DownloadedImage(
                bytes = bytes,
                contentType = contentType,
            )
        }
        throw IllegalStateException("图片下载失败：重定向次数过多")
    }

    private fun requirePublicHost(uri: URI) {
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("图片地址缺少主机名：$uri")
        val addresses = runCatching { InetAddress.getAllByName(host) }
            .getOrElse { throw IllegalArgumentException("无法解析图片地址主机：$host") }
        require(addresses.isNotEmpty()) { "无法解析图片地址主机：$host" }
        addresses.forEach { address ->
            require(!isBlockedAddress(address)) { "不允许访问内网或本地地址：$host" }
        }
    }

    private fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            // 100.64.0.0/10 CGNAT
            if (b0 == 100 && b1 in 64..127) return true
        }
        if (bytes.size == 16) {
            // fc00::/7 IPv6 唯一本地地址
            if ((bytes[0].toInt() and 0xfe) == 0xfc) return true
            // ::ffff:x.x.x.x IPv4-mapped，取内嵌 v4 复判
            val isV4Mapped = (0..9).all { bytes[it].toInt() == 0 } &&
                (bytes[10].toInt() and 0xff) == 0xff &&
                (bytes[11].toInt() and 0xff) == 0xff
            if (isV4Mapped) {
                val mapped = runCatching {
                    InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                }.getOrNull()
                if (mapped != null && isBlockedAddress(mapped)) return true
            }
        }
        return false
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
        private val whitespaceBytes = setOf(' '.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\t'.code.toByte())
        private const val MAX_REDIRECTS = 3
        private val OCTET_STREAM = ContentType("application", "octet-stream")
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36"
    }
}

private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
    if (seconds <= 0.0 && minimumMillis <= 0) return 0
    return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
}
