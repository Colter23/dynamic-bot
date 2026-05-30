package top.colter.dynamic.listener

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.skia.EncodedImageFormat
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.renderDynamicImage

public fun interface DynamicImageRenderer {
    public fun render(update: SourceUpdate): Path
}

public class FileDynamicImageRenderer(
    private val outputDir: Path = Paths.get("data", "images", "dynamic"),
    private val drawSettingsProvider: () -> DrawSettings = { DrawSettings() },
) : DynamicImageRenderer {
    override fun render(update: SourceUpdate): Path {
        val od = outputDir
            .resolve(update.platformId.value)
            .resolve(update.publisher.externalId.ifBlank { "unknown" })
        Files.createDirectories(od)
        val data = renderDynamicImage(
            update = update,
            config = DrawConfig(
                platform = top.colter.dynamic.core.data.PlatformDescriptor(
                    id = update.platformId,
                    displayName = update.platformId.value,
                ),
                settings = drawSettingsProvider(),
            ),
        )
            .encodeToData(EncodedImageFormat.PNG, 100)
            ?: error("动态图片编码失败")
        val outputPath = od.resolve(safeFileName(update.key.externalId) + ".png").toAbsolutePath().normalize()
        Files.write(outputPath, data.bytes)
        return outputPath
    }

    private fun safeFileName(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').ifBlank { "dynamic" }
    }
}
