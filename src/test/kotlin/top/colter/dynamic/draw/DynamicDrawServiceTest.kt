package top.colter.dynamic.draw

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import top.colter.dynamic.DrawOutputFormat
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.draw.image.DynamicImageLoader
import top.colter.dynamic.testDynamicUpdate

class DynamicDrawServiceTest {
    @Test
    fun `default render output is webp image`() = runTest {
        val renderedRoot = createTempDirectory("dynamic-draw-webp")
        val service = drawService(renderedRoot = renderedRoot)

        val media = service.render(testDynamicUpdate(externalId = "webp-default"), storedPublisher = null)
        val path = Paths.get(media.uri)
        val bytes = path.readBytes()

        assertEquals(MediaKind.IMAGE, media.kind)
        assertTrue(path.fileName.toString().endsWith(".webp"))
        assertTrue(Files.isRegularFile(path))
        assertTrue(bytes.isWebP())
    }

    @Test
    fun `png render output remains available for compatibility`() = runTest {
        val renderedRoot = createTempDirectory("dynamic-draw-png")
        val service = drawService(
            renderedRoot = renderedRoot,
            outputFormat = DrawOutputFormat.PNG,
        )

        val media = service.render(testDynamicUpdate(externalId = "png-fallback"), storedPublisher = null)
        val path = Paths.get(media.uri)
        val bytes = path.readBytes()

        assertEquals(MediaKind.IMAGE, media.kind)
        assertTrue(path.fileName.toString().endsWith(".png"))
        assertTrue(Files.isRegularFile(path))
        assertTrue(bytes.isPng())
    }

    private fun drawService(
        renderedRoot: java.nio.file.Path,
        outputFormat: DrawOutputFormat = DrawOutputFormat.WEBP,
    ): DefaultDynamicDrawService {
        val sourceRoot = createTempDirectory("dynamic-draw-source")
        return DefaultDynamicDrawService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(
                        sourceRoot = sourceRoot.toString(),
                        renderedRoot = renderedRoot.toString(),
                    ),
                    draw = DrawSettings(
                        autoTheme = false,
                        outputFormat = outputFormat,
                    ),
                )
            },
            imageLoader = DynamicImageLoader { },
        )
    }

    private fun ByteArray.isWebP(): Boolean {
        return size >= 12 &&
            String(copyOfRange(0, 4), StandardCharsets.US_ASCII) == "RIFF" &&
            String(copyOfRange(8, 12), StandardCharsets.US_ASCII) == "WEBP"
    }

    private fun ByteArray.isPng(): Boolean {
        return size >= 4 &&
            this[0] == 0x89.toByte() &&
            String(copyOfRange(1, 4), StandardCharsets.US_ASCII) == "PNG"
    }
}
