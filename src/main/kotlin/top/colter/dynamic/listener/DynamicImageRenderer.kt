package top.colter.dynamic.listener

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.skia.EncodedImageFormat
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.DrawDynamic

public fun interface DynamicImageRenderer {
    public fun render(dynamic: Dynamic): Path
}

public class FileDynamicImageRenderer(
    private val outputDir: Path = Paths.get("data", "dynamic-images"),
    private val drawSettingsProvider: () -> DrawSettings = { DrawSettings() },
) : DynamicImageRenderer {
    override fun render(dynamic: Dynamic): Path {
        Files.createDirectories(outputDir)
        val data = DrawDynamic(
            dynamic = dynamic,
            config = DrawConfig(
                platform = dynamic.platform,
                settings = drawSettingsProvider(),
            ),
        )
            .encodeToData(EncodedImageFormat.PNG, 100)
            ?: error("failed to encode dynamic image")
        val outputPath = outputDir.resolve(fileName(dynamic)).toAbsolutePath().normalize()
        Files.write(outputPath, data.bytes)
        return outputPath
    }

    private fun fileName(dynamic: Dynamic): String {
        val rawName = listOf(
            dynamic.platform.id,
            dynamic.publisher.externalId.ifBlank { dynamic.publisher.id.toString() },
            dynamic.dynamicId,
        ).joinToString("-")
        return safeFileName(rawName) + ".png"
    }

    private fun safeFileName(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').ifBlank { "dynamic" }
    }
}
