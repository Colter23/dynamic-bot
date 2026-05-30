package top.colter.dynamic.listener

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

public class ImageFileCleaner(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    public fun clean(root: Path, maxIdleDays: Long): ImageCleanupResult {
        if (!Files.exists(root) || maxIdleDays < 0) return ImageCleanupResult()

        val thresholdMillis = clock.millis() - Duration.ofDays(maxIdleDays).toMillis()
        var deletedFiles = 0
        var deletedBytes = 0L

        walk(root).filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
            val lastModifiedMillis = runCatching { Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis() }
                .getOrNull()
                ?: return@forEach
            if (lastModifiedMillis >= thresholdMillis) return@forEach

            val size = runCatching { Files.size(file) }.getOrDefault(0L)
            if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) {
                deletedFiles += 1
                deletedBytes += size
            }
        }

        val deletedDirectories = deleteEmptyDirectories(root)
        return ImageCleanupResult(
            deletedFiles = deletedFiles,
            deletedBytes = deletedBytes,
            deletedDirectories = deletedDirectories,
        )
    }

    private fun deleteEmptyDirectories(root: Path): Int {
        var deleted = 0
        walk(root)
            .filter { it != root && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .sortedByDescending { root.relativize(it).nameCount }
            .forEach { directory ->
                if (isEmptyDirectory(directory) && runCatching { Files.deleteIfExists(directory) }.getOrDefault(false)) {
                    deleted += 1
                }
            }
        return deleted
    }

    private fun isEmptyDirectory(path: Path): Boolean {
        return runCatching {
            Files.list(path).use { children -> !children.iterator().hasNext() }
        }.getOrDefault(false)
    }

    private fun walk(root: Path): List<Path> {
        return runCatching {
            Files.walk(root).use { stream ->
                val paths = mutableListOf<Path>()
                stream.iterator().forEachRemaining(paths::add)
                paths
            }
        }.getOrDefault(emptyList())
    }
}

public data class ImageCleanupResult(
    val deletedFiles: Int = 0,
    val deletedBytes: Long = 0,
    val deletedDirectories: Int = 0,
)
