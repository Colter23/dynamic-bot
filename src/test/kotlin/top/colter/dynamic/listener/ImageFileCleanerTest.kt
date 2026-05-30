package top.colter.dynamic.listener

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageFileCleanerTest {

    @Test
    fun cleanShouldDeleteOnlyFilesIdleLongerThanThreshold() {
        val now = Instant.parse("2026-05-25T00:00:00Z")
        val root = createTempDirectory("dynamic-image-cleaner")
        val sourceDir = root.resolve("source").createDirectories()
        val renderedDir = root.resolve("rendered").createDirectories()
        val oldSource = sourceDir.resolve("old.png")
        val freshSource = sourceDir.resolve("fresh.png")
        val oldRendered = renderedDir.resolve("old-rendered.png")

        Files.write(oldSource, byteArrayOf(1))
        Files.write(freshSource, byteArrayOf(2))
        Files.write(oldRendered, byteArrayOf(3))
        Files.setLastModifiedTime(oldSource, FileTime.from(now.minusSeconds(31L * 24 * 60 * 60)))
        Files.setLastModifiedTime(freshSource, FileTime.from(now.minusSeconds(5L * 24 * 60 * 60)))
        Files.setLastModifiedTime(oldRendered, FileTime.from(now.minusSeconds(40L * 24 * 60 * 60)))

        val cleaner = ImageFileCleaner(Clock.fixed(now, ZoneOffset.UTC))
        val sourceResult = cleaner.clean(sourceDir, maxIdleDays = 30)
        val renderedResult = cleaner.clean(renderedDir, maxIdleDays = 30)

        assertEquals(1, sourceResult.deletedFiles)
        assertEquals(1, renderedResult.deletedFiles)
        assertFalse(oldSource.exists())
        assertTrue(freshSource.exists())
        assertFalse(oldRendered.exists())
    }
}
