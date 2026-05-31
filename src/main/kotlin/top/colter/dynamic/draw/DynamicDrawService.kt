package top.colter.dynamic.draw

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.skia.EncodedImageFormat
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.draw.image.CachedDynamicImageLoader
import top.colter.dynamic.draw.image.DynamicImageLoader
import top.colter.dynamic.draw.resource.EmptyPlatformDrawAssetResolver
import top.colter.dynamic.draw.resource.PlatformDrawAssetResolver

public fun interface DynamicDrawService {
    public suspend fun render(update: SourceUpdate, storedPublisher: Publisher?): MediaRef
}

public class DefaultDynamicDrawService(
    private val configProvider: () -> MainDynamicConfig,
    imageLoader: DynamicImageLoader? = null,
    private val assetResolver: PlatformDrawAssetResolver = EmptyPlatformDrawAssetResolver,
    private val themeService: PublisherDrawThemeService = PublisherDrawThemeService(),
) : DynamicDrawService {
    private val runtimeImageLoader: DynamicImageLoader by lazy {
        imageLoader ?: CachedDynamicImageLoader(configProvider().imageCache)
    }

    override suspend fun render(update: SourceUpdate, storedPublisher: Publisher?): MediaRef {
        val drawableUpdate = update.toDrawableDynamicUpdate()
        val config = configProvider()
        runtimeImageLoader.load(drawableUpdate)
        val theme = themeService.resolveTheme(
            update = drawableUpdate,
            storedPublisher = storedPublisher,
            settings = config.draw,
        )
        val path = renderToFile(
            update = drawableUpdate,
            config = config,
            theme = theme,
        )
        return MediaRef(uri = path.toString(), kind = MediaKind.IMAGE)
    }

    private fun renderToFile(
        update: SourceUpdate,
        config: MainDynamicConfig,
        theme: DrawTheme,
    ): Path {
        val outputDir = Paths.get(config.imageCache.renderedRoot)
            .resolve(update.platformId.value)
            .resolve(update.publisher.externalId.ifBlank { "unknown" })
        Files.createDirectories(outputDir)

        val data = renderDynamicImage(
            update = update,
            config = DrawConfig(
                platform = PlatformDescriptor(
                    id = update.platformId,
                    displayName = update.platformId.value,
                ),
                settings = config.draw,
                theme = theme,
                assetResolver = assetResolver,
            ),
        ).encodeToData(EncodedImageFormat.PNG, 100)
            ?: error("动态图片编码失败")

        val outputPath = outputDir
            .resolve(safeFileName(update.key.externalId) + ".png")
            .toAbsolutePath()
            .normalize()
        Files.write(outputPath, data.bytes)
        return outputPath
    }

    private fun safeFileName(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').ifBlank { "dynamic" }
    }
}

public fun SourceUpdate.toDrawableDynamicUpdate(): SourceUpdate {
    val live = payload as? LivePayload ?: return this
    val liveTitle = live.title.ifBlank { "Live" }
    val contentText = listOfNotNull(
        liveTitle,
        live.area?.takeIf { it.isNotBlank() },
        link?.takeIf { it.isNotBlank() },
    ).joinToString("\n")
    val card = live.cover?.let {
        MediaCardBlock(
            style = MediaCardStyle.LARGE,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LIVE,
                sourceKind = "live",
                id = live.roomId,
                title = liveTitle,
                description = live.area.orEmpty(),
                badge = "LIVE",
                cover = it,
                link = link,
            ),
        )
    }

    return copy(
        payload = DynamicPayload(
            labels = listOf(DynamicLabel("LIVE")),
            title = liveTitle,
            blocks = buildList {
                add(TextBlock(DynamicContent.text(contentText)))
                card?.let(::add)
            },
        ),
    )
}
