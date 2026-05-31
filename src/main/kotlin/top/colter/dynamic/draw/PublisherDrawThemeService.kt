package top.colter.dynamic.draw

import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.repository.PublisherDrawThemeRepository

private val themeLogger = loggerFor<PublisherDrawThemeService>()

public class PublisherDrawThemeService(
    private val repository: PublisherDrawThemeRepository = PublisherDrawThemeRepository,
    private val avatarThemeExtractor: AvatarThemeExtractor = AvatarThemeExtractor(),
) {
    public fun setTheme(publisherId: Int, colors: String): DrawThemePalette {
        return setTheme(publisherId, DrawThemeFactory.parseThemeColors(colors))
    }

    public fun setTheme(publisherId: Int, colors: List<String>): DrawThemePalette {
        val palette = DrawThemeFactory.fromColors(colors).toPalette()
        repository.upsert(publisherId, palette)
        return palette
    }

    public fun clearTheme(publisherId: Int): Boolean {
        return repository.deleteByPublisherId(publisherId)
    }

    public fun resolveTheme(
        update: SourceUpdate,
        storedPublisher: Publisher?,
        settings: DrawSettings,
    ): DrawTheme {
        val publisherId = storedPublisher?.id
        if (publisherId != null) {
            repository.findByPublisherId(publisherId)?.let { return it.palette.toTheme() }
        }

        if (settings.autoTheme) {
            extractAvatarTheme(update)?.let { theme ->
                if (publisherId != null) {
                    repository.upsert(publisherId, theme.toPalette())
                }
                return theme
            }
        }

        return DrawThemeFactory.fromSettings(settings)
    }

    private fun extractAvatarTheme(update: SourceUpdate): DrawTheme? {
        return runCatching {
            avatarThemeExtractor.extractColors(update.publisher.avatar)
                ?.let(DrawThemeFactory::fromColors)
        }.onFailure {
            themeLogger.warn(it) { "发布者头像取色失败，改用全局主题色：publisher=${update.publisher.key.stableValue()}" }
        }.getOrNull()
    }
}
