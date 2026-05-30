package top.colter.dynamic.repository

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testPublisherKey
import top.colter.dynamic.testTargetAddress

class SubscriptionRepositoryTest {

    @Test
    fun shouldCreateUniqueSubscriptionsAndFindActivePublishersWithSubscribers() {
        initTestDatabase("dynamic-bot-core-subscription-db")
        seedPublisher(id = 1, platformId = "bilibili", externalId = "10001")
        seedPublisher(id = 2, platformId = "x", externalId = "20001")
        val onebotGroup = SubscriberRepository.upsert(
            address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "group-1"),
            name = "group-1",
        ).value
        val discordChannel = SubscriberRepository.upsert(
            address = testTargetAddress(platformId = "discord", kind = TargetKind.CHANNEL, externalId = "channel-1"),
            name = "channel-1",
        ).value

        assertTrue(SubscriptionRepository.subscribe(onebotGroup.id, 1).changed)
        assertFalse(SubscriptionRepository.subscribe(onebotGroup.id, 1).changed)
        assertTrue(SubscriptionRepository.subscribe(discordChannel.id, 1).changed)

        val subscriberIds = SubscriptionRepository.findSubscriberIdsByPublisherId(1)
        val subscribers = SubscriptionRepository.findSubscribersByPublisherId(1)
        val bilibiliPublishers = SubscriptionRepository.findActivePublishersWithSubscribersBySourcePlatform("bilibili")
        val xPublishers = SubscriptionRepository.findActivePublishersWithSubscribersBySourcePlatform("x")

        assertEquals(listOf(onebotGroup.id, discordChannel.id).sorted(), subscriberIds.sorted())
        assertEquals(2, subscribers.size)
        assertEquals("bilibili", bilibiliPublishers.keys.single().platformId.value)
        assertTrue(xPublishers.isEmpty())
    }

    @Test
    fun shouldRejectUnknownPublisherOrSubscriber() {
        initTestDatabase("dynamic-bot-core-subscription-invalid-db")

        assertFailsWith<IllegalArgumentException> {
            SubscriptionRepository.subscribe(subscriberId = 404, publisherId = 1)
        }
    }

    @Test
    fun shouldStoreEventAndMentionAllRulesInPolicy() {
        initTestDatabase("dynamic-bot-core-subscription-policy-db")
        seedPublisher(id = 1, platformId = "bilibili", externalId = "10001")
        val subscriber = SubscriberRepository.upsert(
            address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "group-1"),
            name = "group-1",
        ).value
        val policy = SubscriptionPolicy(
            enabledEvents = setOf(SubscriptionEventKind.DYNAMIC, SubscriptionEventKind.LIVE_STARTED),
            mentionAllEvents = setOf(SubscriptionEventKind.LIVE_STARTED),
        )

        assertTrue(SubscriptionRepository.subscribe(subscriberId = subscriber.id, publisherId = 1, policy = policy).changed)
        val created = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, 1)!!
        assertEquals(policy, created.policy)

        val updatedPolicy = policy.copy(enabledEvents = setOf(SubscriptionEventKind.LIVE_ENDED), mentionAllEvents = emptySet())
        val updated = SubscriptionRepository.updatePolicy(created.id, updatedPolicy)
        assertEquals(updatedPolicy, updated.policy)
        assertEquals(updatedPolicy, SubscriptionRepository.findAll().single().policy)
    }

    @Test
    fun shouldBroadcastSubscriptionChangesOnlyWhenStateChanges() = runBlocking {
        initTestDatabase("dynamic-bot-core-subscription-event-db")
        seedPublisher(id = 1, platformId = "bilibili", externalId = "10001")
        val subscriber = SubscriberRepository.upsert(
            address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "group-1"),
            name = "group-1",
        ).value
        val events = Channel<SubscriptionChangedEvent>(Channel.UNLIMITED)
        val eventBus = EventBus()
        object : Listener<SubscriptionChangedEvent> {
            override suspend fun onMessage(event: SubscriptionChangedEvent) {
                events.send(event)
            }
        }.let { eventBus.subscribe(it) }

        val subscribeResult = SubscriptionRepository.subscribe(subscriber.id, 1)
        assertTrue(subscribeResult.changed)
        subscribeResult.event?.let { eventBus.broadcast(it) }
        val subscribed = withTimeout(3_000) { events.receive() }
        assertEquals(SubscriptionChangeType.SUBSCRIBED, subscribed.changeType)
        assertEquals(1, subscribed.publisher.id)
        assertEquals(subscriber.id, subscribed.subscriber.id)

        val duplicateResult = SubscriptionRepository.subscribe(subscriber.id, 1)
        assertFalse(duplicateResult.changed)
        duplicateResult.event?.let { eventBus.broadcast(it) }
        assertEquals(null, withTimeoutOrNull(300) { events.receive() })

        val unsubscribeResult = SubscriptionRepository.unsubscribe(subscriber.id, 1)
        assertTrue(unsubscribeResult.changed)
        unsubscribeResult.event?.let { eventBus.broadcast(it) }
        val unsubscribed = withTimeout(3_000) { events.receive() }
        assertEquals(SubscriptionChangeType.UNSUBSCRIBED, unsubscribed.changeType)
        assertEquals(subscribed.subscription.id, unsubscribed.subscription.id)

        val missingResult = SubscriptionRepository.unsubscribe(subscriber.id, 1)
        assertFalse(missingResult.changed)
        missingResult.event?.let { eventBus.broadcast(it) }
        assertEquals(null, withTimeoutOrNull(300) { events.receive() })
        eventBus.shutdown()
    }

    private fun seedPublisher(id: Int, platformId: String, externalId: String) {
        PublisherRepository.create(
            testPublisher(
                id = id,
                key = testPublisherKey(platformId = platformId, externalId = externalId),
                name = "$platformId-$externalId",
            ),
        )
    }
}
