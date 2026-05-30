package top.colter.dynamic.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testTargetAddress

class PluginServicesTest {
    @Test
    fun repositorySourceStateStoreShouldWrapCursorAndLiveStatusRepositories() {
        initTestDatabase("plugin-services-source-state")
        PublisherRepository.create(testPublisher(id = 1))

        val cursor = RepositorySourceStateStore.ensureCursorBaseline(
            publisherId = 1,
            sourceKey = "dynamic-feed",
            eventType = SourceEventType.DYNAMIC_CREATED,
            timestamp = 10,
        )
        val updated = RepositorySourceStateStore.markCursorSeen(
            publisherId = 1,
            sourceKey = "dynamic-feed",
            eventType = SourceEventType.DYNAMIC_CREATED,
            updateKey = "dynamic-1",
            timestamp = 20,
        )
        RepositorySourceStateStore.saveLiveStatus(
            PublisherLiveStatus(
                publisherId = 1,
                roomId = "100",
                status = LiveStatus.OPEN,
                title = "Live",
                cover = null,
                area = "Game",
                startedAtEpochSeconds = 20,
                lastObservedAtEpochSeconds = 30,
            ),
        )

        assertEquals("__baseline__10", cursor.lastSeenUpdateKey)
        assertEquals("dynamic-1", updated.lastSeenUpdateKey)
        assertEquals(updated, RepositorySourceStateStore.findCursor(1, "dynamic-feed", SourceEventType.DYNAMIC_CREATED))
        assertEquals(LiveStatus.OPEN, RepositorySourceStateStore.findLatestLiveStatus(1)?.status)
    }

    @Test
    fun repositorySubscriptionQueryServiceShouldReturnActivePublisherSnapshots() {
        initTestDatabase("plugin-services-subscription-query")
        PublisherRepository.create(testPublisher(id = 1))
        SubscriberRepository.create(
            Subscriber(
                id = 10,
                address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "100"),
                name = "group",
                state = EntityState.ACTIVE,
                createTime = 1,
                createUser = 1,
            ),
        )
        SubscriptionRepository.subscribe(subscriberId = 10, publisherId = 1)

        val byId = assertNotNull(RepositorySubscriptionQueryService.findActivePublisherWithSubscribersById(1))
        val byPlatform = RepositorySubscriptionQueryService.findActivePublishersWithSubscribersBySourcePlatform("bilibili")

        assertEquals("123", byId.publisher.externalId)
        assertEquals(listOf("100"), byId.subscribers.map { it.externalId })
        assertEquals(listOf("123"), byPlatform.map { it.publisher.externalId })
        assertEquals(byId.subscriptions.map { it.subscription.id }, byPlatform.single().subscriptions.map { it.subscription.id })
    }
}
