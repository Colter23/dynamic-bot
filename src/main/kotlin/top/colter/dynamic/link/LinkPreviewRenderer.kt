package top.colter.dynamic.link

import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.draw.DefaultDynamicDrawService
import top.colter.dynamic.draw.DynamicDrawService
import top.colter.dynamic.repository.PublisherRepository

public fun interface LinkPreviewRenderer {
    public suspend fun render(preview: LinkPreview): MediaRef
}

public class DefaultLinkPreviewRenderer(
    private val configProvider: () -> MainDynamicConfig = { MainDynamicConfig() },
    drawService: DynamicDrawService? = null,
) : LinkPreviewRenderer {
    private val runtimeDrawService: DynamicDrawService by lazy {
        drawService ?: DefaultDynamicDrawService(configProvider = configProvider)
    }

    override suspend fun render(preview: LinkPreview): MediaRef {
        val incomingPublisher = preview.publisher
        val storedPublisher = incomingPublisher?.let { PublisherRepository.findByKey(it.key) }
        val publisher: PublisherInfo = when {
            incomingPublisher != null && storedPublisher != null -> mergePublisherInfo(incomingPublisher, storedPublisher.toInfo())
            incomingPublisher != null -> incomingPublisher
            else -> defaultPublisher(preview.platformId, preview.id)
        }
        val update = SourceUpdate(
            key = UpdateKey(
                publisherKey = publisher.key,
                eventType = SourceEventType.DYNAMIC_CREATED,
                externalId = listOf("link-preview", preview.kind, preview.id.ifBlank { "unknown" })
                    .joinToString(":"),
            ),
            publisher = publisher,
            occurredAtEpochSeconds = System.currentTimeMillis() / 1000,
            observedAtEpochSeconds = System.currentTimeMillis() / 1000,
            link = preview.url,
            payload = DynamicPayload(
                blocks = buildPreviewBlocks(preview),
                metrics = preview.metrics,
            ),
        )
        return runtimeDrawService.render(update, storedPublisher)
    }

    private fun buildPreviewBlocks(preview: LinkPreview): List<top.colter.dynamic.core.data.DynamicBlock> {
        val card = MediaCardBlock(
            style = if (preview.cover == null) MediaCardStyle.SMALL else MediaCardStyle.LARGE,
            card = DynamicMediaCard(
                kind = preview.kind.toMediaCardKind(),
                sourceKind = "${preview.platformId.value}.${preview.kind}",
                id = preview.id,
                title = preview.title.ifBlank { preview.url },
                description = preview.description,
                badge = preview.badge ?: preview.kind.label(),
                cover = preview.cover,
                info = preview.metrics.infoText(),
                metrics = preview.metrics,
                link = preview.url,
            ),
        )
        val description = preview.description.trim().takeIf { it.isNotBlank() }
        return if (preview.cover == null && description != null && description.length > 180) {
            listOf(
                card,
                TextBlock(DynamicContent(listOf(DynamicContentNodeText(description)))),
            )
        } else {
            listOf(card)
        }
    }

    private fun String.toMediaCardKind(): DynamicMediaCardKind {
        return when (this) {
            LinkKinds.VIDEO -> DynamicMediaCardKind.VIDEO
            LinkKinds.LIVE -> DynamicMediaCardKind.LIVE
            LinkKinds.USER -> DynamicMediaCardKind.LINK
            else -> DynamicMediaCardKind.LINK
        }
    }

    private fun String.label(): String {
        return when (this) {
            LinkKinds.VIDEO -> "视频"
            LinkKinds.LIVE -> "直播"
            LinkKinds.USER -> "用户"
            LinkKinds.DYNAMIC -> "动态"
            else -> "链接"
        }
    }

    private fun List<DynamicMetric>.infoText(): String? {
        return mapNotNull { metric ->
            metric.display?.takeIf { it.isNotBlank() }?.let { display ->
                when (metric.key) {
                    "play" -> "${display}播放"
                    "danmaku" -> "${display}弹幕"
                    "like" -> "${display}点赞"
                    "online" -> "${display}在线"
                    else -> display
                }
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    }

    private fun mergePublisherInfo(primary: PublisherInfo, fallback: PublisherInfo): PublisherInfo {
        return primary.copy(
            name = primary.name.ifBlank { fallback.name },
            avatarBadgeKey = primary.avatarBadgeKey ?: fallback.avatarBadgeKey,
            avatar = primary.avatar.takeIf { it.uri.isNotBlank() } ?: fallback.avatar,
            banner = primary.banner ?: fallback.banner,
            pendant = primary.pendant ?: fallback.pendant,
        )
    }

    private fun defaultPublisher(platformId: PlatformId, id: String): PublisherInfo {
        return PublisherInfo(
            key = PublisherKey.of(
                platformId = platformId.value,
                kind = PublisherKind.OTHER,
                externalId = id.ifBlank { "link-preview" },
            ),
            name = platformId.value,
            avatar = MediaRef("", MediaKind.AVATAR),
        )
    }
}
