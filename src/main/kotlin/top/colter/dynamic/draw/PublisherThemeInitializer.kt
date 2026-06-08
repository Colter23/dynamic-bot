package top.colter.dynamic.draw

import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.core.tools.nowInstant
import top.colter.dynamic.draw.image.CachedDynamicImageLoader
import top.colter.dynamic.draw.image.DynamicImageLoader
import top.colter.dynamic.repository.PublisherDrawThemeRepository

private val themeInitializerLogger = loggerFor<DefaultPublisherThemeInitializer>()

public fun interface PublisherThemeInitializer {
    public suspend fun initializeAfterFirstSubscription(publisher: Publisher, previousSubscriptionCount: Long)

    public suspend fun initializeAfterPublisherUpsert(publisher: Publisher) {
        initializeAfterFirstSubscription(publisher, previousSubscriptionCount = 0)
    }
}

public class DefaultPublisherThemeInitializer(
    private val configProvider: () -> MainDynamicConfig,
    imageLoader: DynamicImageLoader? = null,
    private val themeService: PublisherDrawThemeService = PublisherDrawThemeService(),
) : PublisherThemeInitializer {
    private val runtimeImageLoader: DynamicImageLoader by lazy {
        imageLoader ?: CachedDynamicImageLoader(configProvider().imageCache)
    }

    override suspend fun initializeAfterFirstSubscription(publisher: Publisher, previousSubscriptionCount: Long) {
        if (previousSubscriptionCount != 0L) return
        if (PublisherDrawThemeRepository.findByPublisherId(publisher.id) != null) return
        val config = configProvider()
        if (!config.draw.autoTheme) return

        runCatching {
            val update = publisher.toThemeSeedUpdate()
            runtimeImageLoader.load(update)
            themeService.resolveTheme(update, publisher, config.draw)
        }.onFailure {
            themeInitializerLogger.warn(it) {
                "首次订阅自动生成发布者主题色失败，已保留全局主题色：publisherId=${publisher.id}"
            }
        }
    }

    private fun Publisher.toThemeSeedUpdate(): SourceUpdate {
        val now = nowInstant().epochSeconds
        return SourceUpdate(
            key = UpdateKey.of(
                publisherKey = key,
                eventType = SourceEventType.DYNAMIC_CREATED,
                externalId = "theme-seed-$id-$now",
            ),
            publisher = toInfo(),
            occurredAtEpochSeconds = now,
            observedAtEpochSeconds = now,
            payload = DynamicPayload(),
        )
    }
}
