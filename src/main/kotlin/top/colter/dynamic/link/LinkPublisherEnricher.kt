package top.colter.dynamic.link

import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.repository.PublisherRepository

internal class LinkPublisherEnricher(
    private val storedPublisherResolver: (PublisherKey) -> Publisher? = { PublisherRepository.findByKey(it) },
    private val publisherLookupResolver: (String) -> PublisherLookupPlugin? = { null },
    private val onLookupFailure: (PublisherInfo, Throwable) -> Unit = { _, _ -> },
) {
    suspend fun enrich(update: SourceUpdate): SourceUpdate {
        return update.copy(publisher = enrich(update.publisher))
    }

    suspend fun enrich(preview: LinkPreview): LinkPreview {
        val incoming = preview.publisher ?: return preview
        val publisher = enrich(incoming)
        val cover = if (preview.kind == LinkKinds.USER && preview.cover == null) {
            publisher.banner ?: preview.cover
        } else {
            preview.cover
        }
        return preview.copy(
            cover = cover,
            publisher = publisher,
        )
    }

    private suspend fun enrich(incoming: PublisherInfo): PublisherInfo {
        val localMerged = storedPublisherResolver(incoming.key)
            ?.let { stored -> mergePublisherInfo(incoming, stored.toInfo()) }
            ?: incoming
        if (!localMerged.needsLookup()) return localMerged

        val plugin = publisherLookupResolver(localMerged.platformId.value) ?: return localMerged
        val fetched = runCatching { plugin.fetchPublisherInfo(localMerged.externalId) }
            .onFailure { onLookupFailure(localMerged, it) }
            .getOrNull()
            ?: return localMerged
        return mergePublisherInfo(localMerged, fetched)
    }

    private fun PublisherInfo.needsLookup(): Boolean {
        return name.isBlank() || avatar.uri.isBlank() || banner == null
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
}
