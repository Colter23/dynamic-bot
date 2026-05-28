package top.colter.dynamic.listener

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.skia.EncodedImageFormat
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.renderDynamicImage

public fun interface DynamicImageRenderer {
    public fun render(dynamic: Dynamic): Path
}

public class FileDynamicImageRenderer(
    private val outputDir: Path = Paths.get("data", "images", "dynamic"),
    private val drawSettingsProvider: () -> DrawSettings = { DrawSettings() },
) : DynamicImageRenderer {
    override fun render(dynamic: Dynamic): Path {
        val od = outputDir.resolve(dynamic.platform.id).resolve(dynamic.publisher.externalId.ifBlank { "unknown" })
        Files.createDirectories(od)
        val data = renderDynamicImage(
            dynamic = dynamic,
            config = DrawConfig(
                platform = dynamic.platform,
                settings = drawSettingsProvider(),
            ),
        )
            .encodeToData(EncodedImageFormat.PNG, 100)
            ?: error("failed to encode dynamic image")
        val outputPath = od.resolve(safeFileName(dynamic.dynamicId) + ".png").toAbsolutePath().normalize()
        Files.write(outputPath, data.bytes)
        return outputPath
    }

    private fun safeFileName(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').ifBlank { "dynamic" }
    }
}
