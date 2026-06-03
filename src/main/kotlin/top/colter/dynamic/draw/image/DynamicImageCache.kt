package top.colter.dynamic.draw.image

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.LinkedHashMap
import javax.imageio.ImageIO
import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.draw.DynamicImageResolver

public object DynamicImageCache : DynamicImageResolver {
    private val lock = Any()
    private val images = LinkedHashMap<String, MemoryImage>(16, 0.75f, true)
    private val imageFiles = LinkedHashMap<String, Path>(16, 0.75f, true)

    @Volatile
    private var sourceRoot: Path = Paths.get("data", "images", "source")
    private var maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES
    private var maxMemoryEntries: Int = DEFAULT_MAX_MEMORY_ENTRIES
    private var maxReadBytes: Long = DEFAULT_MAX_READ_BYTES
    private var currentMemoryBytes: Long = 0L

    private val placeholderBytes: ByteArray by lazy { createPlaceholderBytes() }

    public fun configure(
        sourceRoot: Path,
        maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES,
        maxMemoryEntries: Int = DEFAULT_MAX_MEMORY_ENTRIES,
        maxReadBytes: Long = DEFAULT_MAX_READ_BYTES,
    ) {
        val normalizedRoot = sourceRoot.toAbsolutePath().normalize()
        synchronized(lock) {
            val rootChanged = this.sourceRoot != normalizedRoot
            this.sourceRoot = normalizedRoot
            this.maxMemoryBytes = maxMemoryBytes.coerceAtLeast(0)
            this.maxMemoryEntries = maxMemoryEntries.coerceAtLeast(0)
            this.maxReadBytes = maxReadBytes.coerceAtLeast(1)
            if (rootChanged) {
                clearLocked()
            } else {
                trimMemoryLocked()
                trimFileIndexLocked()
            }
        }
    }

    public fun clearMemory() {
        synchronized(lock) {
            images.clear()
            currentMemoryBytes = 0L
        }
    }

    public fun contains(image: MediaRef): Boolean {
        val cachedPath = synchronized(lock) {
            if (images.containsKey(image.uri)) return true
            imageFiles[image.uri]
        }
        return cachedPath?.existsAndTouch() == true
    }

    public fun put(image: MediaRef, bytes: ByteArray) {
        storeMemory(image.uri, bytes)
    }

    public fun putPlaceholder(image: MediaRef) {
        storeMemory(image.uri, placeholderBytes)
    }

    public fun bytes(image: MediaRef): ByteArray {
        memory(image.uri)?.let { return it }

        trackedFile(image.uri)?.readAndTouch(maxReadBytes)?.let { bytes ->
            storeMemory(image.uri, bytes)
            return bytes
        }

        readLocalFile(image.uri)?.let { bytes ->
            storeMemory(image.uri, bytes)
            return bytes
        }

        return placeholderBytes
    }

    override fun image(image: MediaRef): Image {
        return decode(bytes(image)) ?: placeholderImage()
    }

    public fun loadFromDisk(image: MediaRef, platformId: String, imageType: MediaKind): Boolean {
        val path = pathFor(platformId, imageType, image.uri)
        val bytes = path.readAndTouch(maxReadBytes) ?: return false
        trackFile(image.uri, path)
        storeMemory(image.uri, bytes)
        return true
    }

    public fun store(image: MediaRef, platformId: String, imageType: MediaKind, bytes: ByteArray): Path {
        val path = pathFor(platformId, imageType, image.uri)
        Files.createDirectories(path.parent)

        if (!Files.exists(path)) {
            val tempPath = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
            Files.write(tempPath, bytes)
            runCatching {
                Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE)
            }.recoverCatching {
                if (Files.exists(path)) {
                    Files.deleteIfExists(tempPath)
                    path
                } else {
                    Files.move(tempPath, path)
                }
            }.onFailure {
                runCatching { Files.deleteIfExists(tempPath) }
            }.getOrThrow()
        }

        path.touch()
        trackFile(image.uri, path)
        storeMemory(image.uri, path.readAndTouch(maxReadBytes) ?: bytes)
        return path
    }

    public fun pathFor(platformId: String, imageType: MediaKind, uri: String): Path {
        return sourceRoot
            .resolve(safePathSegment(platformId.ifBlank { "unknown" }))
            .resolve(imageType.name)
            .resolve(fileNameForUri(uri))
            .toAbsolutePath()
            .normalize()
    }

    public fun cacheKey(platformId: String, imageType: MediaKind, uri: String): String {
        return listOf(
            safePathSegment(platformId.ifBlank { "unknown" }),
            imageType.name,
            fileNameForUri(uri),
        ).joinToString("/")
    }

    public fun fileNameForUri(uri: String): String {
        val trimmed = uri.trim()
        val rawName = uriPathFileName(trimmed)
            ?.takeIf { it.isNotBlank() }
            ?: trimmed.substringBefore('?').substringBefore('#')
                .replace('\\', '/')
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }

        val decoded = rawName
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrDefault(it) }
            .orEmpty()
        val safe = decoded
            .replace(Regex("""[<>:"/\\|?*\u0000-\u001F]+"""), "_")
            .trim { it <= ' ' || it == '.' || it == '_' }
        val hash = sha256(trimmed).take(16)
        val fileName = safe.ifBlank { "image.img" }
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < fileName.lastIndex - 1) {
            fileName.substring(0, dotIndex) + "-$hash" + fileName.substring(dotIndex)
        } else {
            "$fileName-$hash"
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun uriPathFileName(uri: String): String? {
        return runCatching {
            URI(uri).rawPath
                ?.replace('\\', '/')
                ?.substringAfterLast('/')
        }.getOrNull()
    }

    private fun safePathSegment(value: String): String {
        return value
            .replace(Regex("""[<>:"/\\|?*\u0000-\u001F]+"""), "_")
            .trim { it <= ' ' || it == '.' || it == '_' }
            .ifBlank { "unknown" }
    }

    private fun memory(uri: String): ByteArray? = synchronized(lock) {
        images[uri]?.bytes
    }

    private fun storeMemory(uri: String, bytes: ByteArray) {
        synchronized(lock) {
            val previous = images.remove(uri)
            if (previous != null) currentMemoryBytes -= previous.size

            val size = bytes.size.toLong()
            if (maxMemoryEntries > 0 && size <= maxMemoryBytes) {
                images[uri] = MemoryImage(bytes, size)
                currentMemoryBytes += size
            }
            trimMemoryLocked()
        }
    }

    private fun trackedFile(uri: String): Path? = synchronized(lock) {
        imageFiles[uri]
    }

    private fun trackFile(uri: String, path: Path) {
        synchronized(lock) {
            imageFiles[uri] = path
            trimFileIndexLocked()
        }
    }

    private fun trimMemoryLocked() {
        while (images.isNotEmpty() && (images.size > maxMemoryEntries || currentMemoryBytes > maxMemoryBytes)) {
            val iterator = images.entries.iterator()
            val removed = iterator.next()
            currentMemoryBytes -= removed.value.size
            iterator.remove()
        }
        if (maxMemoryEntries == 0 || maxMemoryBytes == 0L) {
            images.clear()
            currentMemoryBytes = 0L
        }
    }

    private fun trimFileIndexLocked() {
        val maxFileEntries = (maxMemoryEntries.coerceAtLeast(1) * FILE_INDEX_MULTIPLIER).coerceAtLeast(MIN_FILE_INDEX_ENTRIES)
        while (imageFiles.size > maxFileEntries) {
            val iterator = imageFiles.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private fun clearLocked() {
        images.clear()
        imageFiles.clear()
        currentMemoryBytes = 0L
    }

    private fun readLocalFile(uri: String): ByteArray? {
        val path = runCatching {
            val parsed = URI(uri)
            if (parsed.scheme.equals("file", ignoreCase = true)) Paths.get(parsed) else Paths.get(uri)
        }.recoverCatching { error ->
            if (error is InvalidPathException) throw error
            Paths.get(uri)
        }.getOrNull() ?: return null

        return path.readAndTouch(maxReadBytes)
    }

    private fun Path.readAndTouch(maxBytes: Long): ByteArray? {
        if (!Files.isRegularFile(this)) return null
        return runCatching {
            val size = Files.size(this)
            require(size <= maxBytes) { "图片文件超过大小限制：size=$size，maxBytes=$maxBytes" }
            touch()
            Files.readAllBytes(this)
        }.getOrNull()
    }

    private fun Path.existsAndTouch(): Boolean {
        if (!Files.isRegularFile(this)) return false
        touch()
        return true
    }

    private fun Path.touch() {
        runCatching {
            Files.setLastModifiedTime(this, FileTime.fromMillis(System.currentTimeMillis()))
        }
    }

    private fun decode(bytes: ByteArray): Image? {
        return runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
    }

    private fun placeholderImage(): Image {
        return Image.makeFromEncoded(placeholderBytes)
    }

    private fun createPlaceholderBytes(): ByteArray {
        val width = 160
        val height = 90
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(242, 244, 247)
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color(206, 212, 220)
            graphics.drawRect(0, 0, width - 1, height - 1)
            graphics.drawLine(0, height - 1, width - 1, 0)
            graphics.drawLine(0, 0, width - 1, height - 1)
        } finally {
            graphics.dispose()
        }

        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    private data class MemoryImage(
        val bytes: ByteArray,
        val size: Long,
    )

    private const val DEFAULT_MAX_MEMORY_BYTES: Long = 128L * 1024L * 1024L
    private const val DEFAULT_MAX_MEMORY_ENTRIES: Int = 512
    private const val DEFAULT_MAX_READ_BYTES: Long = 20L * 1024L * 1024L
    private const val FILE_INDEX_MULTIPLIER: Int = 8
    private const val MIN_FILE_INDEX_ENTRIES: Int = 512
}
