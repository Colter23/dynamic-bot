package top.colter.dynamic.plugin

import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.repository.PublisherLiveStatusRepository
import top.colter.dynamic.repository.SourceCursorRepository
import top.colter.dynamic.repository.SubscriptionRepository

public object RepositorySourceStateStore : SourceStateStore {
    override fun findCursor(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
    ): SourceCursor? {
        return SourceCursorRepository.find(publisherId, sourceKey, eventType)
    }

    override fun ensureCursorBaseline(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
        timestamp: Long,
    ): SourceCursor {
        return SourceCursorRepository.ensureBaseline(publisherId, sourceKey, eventType, timestamp)
    }

    override fun markCursorSeen(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
        updateKey: String,
        timestamp: Long,
    ): SourceCursor {
        return SourceCursorRepository.markSeen(publisherId, sourceKey, eventType, updateKey, timestamp)
    }

    override fun findLatestLiveStatus(publisherId: Int): PublisherLiveStatus? {
        return PublisherLiveStatusRepository
            .findByPublisherId(publisherId)
            .maxByOrNull { it.lastObservedAtEpochSeconds }
    }

    override fun saveLiveStatus(state: PublisherLiveStatus): PublisherLiveStatus {
        return PublisherLiveStatusRepository.upsert(state)
    }
}

public object RepositorySubscriptionQueryService : SubscriptionQueryService {
    override fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? {
        return SubscriptionRepository.findActivePublisherWithSubscribersById(publisherId)
    }

    override fun findActivePublishersWithSubscribersBySourcePlatform(platformId: String): Map<Publisher, List<Subscriber>> {
        return SubscriptionRepository.findActivePublishersWithSubscribersBySourcePlatform(platformId)
    }
}
