package top.colter.dynamic.media

import io.ktor.http.ContentType
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent

public class OutboundMediaService(
    private val configProvider: () -> MainDynamicConfig,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    public fun rewriteMessage(message: Message): Message {
        val rewritten = rewriteBatches(message.batches)
        return if (rewritten == message.batches) message else message.copy(batches = rewritten)
    }

    public fun rewriteBatches(batches: List<MessageBatch>): List<MessageBatch> {
        val rewritten = batches.map { batch ->
            val content = batch.content.map(::rewriteContent)
            if (content == batch.content) batch else batch.copy(content = content)
        }
        return if (rewritten == batches) batches else rewritten
    }

    public fun rewriteMedia(media: MediaRef): MediaRef {
        val config = configProvider()
        val mediaConfig = config.outboundMedia
        if (!mediaConfig.enabled) return media
        val baseUrl = mediaConfig.publicBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank() || mediaConfig.signingSecret.isBlank()) return media

        val uri = media.uri.trim()
        if (uri.isBlank() || uri.isRemoteOrEncoded()) return media
        val path = uri.localPath() ?: return media
        val normalizedPath = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS)) return media

        val root = findAllowedRoot(normalizedPath, config) ?: return media
        val relative = root.path.relativize(normalizedPath).portableString()
        val id = mediaId(root.key, relative)
        val expires = nowEpochSeconds() + mediaConfig.urlTtlSeconds.coerceAtLeast(1)
        val signature = sign(mediaConfig.signingSecret, id, expires)
        val url = "$baseUrl/media/outbound/${id.urlComponent()}?expires=$expires&sig=${signature.urlComponent()}"
        return media.copy(uri = url)
    }

    public fun resolve(id: String, expires: Long, signature: String): OutboundMediaResult {
        val config = configProvider()
        val mediaConfig = config.outboundMedia
        require(mediaConfig.enabled) { "出站媒体 URL 未启用" }
        require(mediaConfig.signingSecret.isNotBlank()) { "出站媒体签名密钥未配置" }
        require(expires >= nowEpochSeconds()) { "媒体链接已过期" }
        require(secureEquals(signature, sign(mediaConfig.signingSecret, id, expires))) { "媒体链接签名无效" }

        val decoded = decodeMediaId(id)
        val root = allowedRoots(config).firstOrNull { it.key == decoded.rootKey }
            ?: throw NoSuchElementException("媒体根目录不存在：${decoded.rootKey}")
        val path = root.path.resolve(decoded.relativePath).normalize()
        require(path.startsWith(root.path)) { "媒体路径无效" }
        val realRoot = root.path.realPathOrSelf()
        val realPath = path.realPathOrSelf()
        require(realPath.startsWith(realRoot)) { "媒体路径不在允许目录内" }
        require(Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) { "媒体文件不存在" }

        val size = Files.size(realPath)
        require(size <= config.imageCache.maxImageBytes) {
            "媒体文件超过大小限制：size=$size，maxBytes=${config.imageCache.maxImageBytes}"
        }
        val bytes = Files.readAllBytes(realPath)
        require(bytes.size.toLong() <= config.imageCache.maxImageBytes) {
            "媒体文件超过大小限制：maxBytes=${config.imageCache.maxImageBytes}"
        }
        val contentType = imageContentType(realPath, bytes)
            ?: throw IllegalArgumentException("不支持的媒体类型：${realPath.fileName}")
        return OutboundMediaResult(
            bytes = bytes,
            contentType = contentType,
            cacheMaxAgeSeconds = (expires - nowEpochSeconds()).coerceAtLeast(0),
        )
    }

    private fun rewriteContent(content: MessageContent): MessageContent {
        return when (content) {
            is MessageContent.Image -> {
                val rewritten = rewriteMedia(content.image)
                if (rewritten == content.image) content else content.copy(image = rewritten)
            }
            else -> content
        }
    }

    private fun findAllowedRoot(path: Path, config: MainDynamicConfig): OutboundMediaRoot? {
        return allowedRoots(config).firstOrNull { root -> path.startsWith(root.path) }
    }

    private fun allowedRoots(config: MainDynamicConfig): List<OutboundMediaRoot> {
        return listOf(
            OutboundMediaRoot("source", Paths.get(config.imageCache.sourceRoot).toAbsolutePath().normalize()),
            OutboundMediaRoot("rendered", Paths.get(config.imageCache.renderedRoot).toAbsolutePath().normalize()),
            OutboundMediaRoot("login-qr", Paths.get("data", "login-qr").toAbsolutePath().normalize()),
        )
    }

    private fun mediaId(rootKey: String, relativePath: String): String {
        val payload = "$rootKey\n$relativePath"
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeMediaId(id: String): DecodedMediaId {
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8)
        }.getOrElse {
            throw IllegalArgumentException("媒体 ID 无效")
        }
        val rootKey = decoded.substringBefore('\n', missingDelimiterValue = "")
        val relativePath = decoded.substringAfter('\n', missingDelimiterValue = "")
        require(rootKey.isNotBlank() && relativePath.isNotBlank()) { "媒体 ID 无效" }
        require(relativePath.none { it == '\u0000' }) { "媒体路径无效" }
        return DecodedMediaId(rootKey, relativePath)
    }

    private fun sign(secret: String, id: String, expires: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal("$id.$expires".toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun secureEquals(left: String, right: String): Boolean {
        return MessageDigest.isEqual(
            left.toByteArray(StandardCharsets.UTF_8),
            right.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun imageContentType(path: Path, bytes: ByteArray): ContentType? {
        return when {
            bytes.hasAscii(1, "PNG") -> ContentType.Image.PNG
            bytes.size >= 3 &&
                bytes[0] == 0xff.toByte() &&
                bytes[1] == 0xd8.toByte() &&
                bytes[2] == 0xff.toByte() -> ContentType.Image.JPEG
            bytes.hasAscii(0, "GIF87a") || bytes.hasAscii(0, "GIF89a") -> ContentType.Image.GIF
            bytes.hasAscii(0, "RIFF") && bytes.hasAscii(8, "WEBP") -> ContentType("image", "webp")
            else -> when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
                "png" -> ContentType.Image.PNG
                "jpg", "jpeg" -> ContentType.Image.JPEG
                "gif" -> ContentType.Image.GIF
                "webp" -> ContentType("image", "webp")
                else -> null
            }
        }
    }

    private fun ByteArray.hasAscii(offset: Int, value: String): Boolean {
        if (size < offset + value.length) return false
        return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
    }

    private fun String.isRemoteOrEncoded(): Boolean {
        return startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true) ||
            startsWith("base64://", ignoreCase = true)
    }

    private fun String.localPath(): Path? {
        return try {
            val parsed = runCatching { URI(this) }.getOrNull()
            when {
                parsed != null && parsed.scheme.equals("file", ignoreCase = true) -> Paths.get(parsed)
                parsed != null && parsed.scheme != null -> null
                else -> Paths.get(this)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun Path.realPathOrSelf(): Path {
        return runCatching { toRealPath(LinkOption.NOFOLLOW_LINKS) }
            .getOrElse { toAbsolutePath().normalize() }
    }

    private fun Path.portableString(): String {
        return joinToString("/") { it.toString() }
    }

    private fun String.urlComponent(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
    }
}

public data class OutboundMediaResult(
    val bytes: ByteArray,
    val contentType: ContentType,
    val cacheMaxAgeSeconds: Long,
)

private data class OutboundMediaRoot(
    val key: String,
    val path: Path,
)

private data class DecodedMediaId(
    val rootKey: String,
    val relativePath: String,
)
