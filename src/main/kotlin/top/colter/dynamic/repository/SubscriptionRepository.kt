package top.colter.dynamic.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionSubscriber
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.table.PublisherTable
import top.colter.dynamic.table.SubscriberTable
import top.colter.dynamic.table.SubscriptionTable
import top.colter.dynamic.core.tools.nowInstant

public data class SubscriptionMutationResult(
    val changed: Boolean,
    val event: SubscriptionChangedEvent? = null,
)

public object SubscriptionRepository {
    public fun findById(id: Int): Subscription? {
        return transaction {
            SubscriptionTable
                .selectAll()
                .where { SubscriptionTable.id eq id }
                .firstOrNull()
                ?.toSubscription()
        }
    }

    public fun findSubscriberIdsByPublisherId(publisherId: Int): List<Int> {
        return transaction {
            SubscriptionTable
                .selectAll()
                .where { SubscriptionTable.publisherId eq EntityID(publisherId, PublisherTable) }
                .map { it[SubscriptionTable.subscriberId].value }
                .distinct()
        }
    }

    public fun findSubscribersByPublisherId(publisherId: Int): List<Subscriber> {
        val ids = findSubscriberIdsByPublisherId(publisherId)
        if (ids.isEmpty()) return emptyList()
        return SubscriberRepository.findByIds(ids)
    }

    public fun findSubscriptionsWithSubscribersByPublisherId(publisherId: Int): List<SubscriptionSubscriber> {
        val subscriptions = transaction {
            SubscriptionTable
                .selectAll()
                .where {
                    (SubscriptionTable.publisherId eq EntityID(publisherId, PublisherTable)) and
                        (SubscriptionTable.state eq EntityState.ACTIVE)
                }
                .map { it.toSubscription() }
        }
        if (subscriptions.isEmpty()) return emptyList()

        val subscribersById = SubscriberRepository
            .findByIds(subscriptions.map { it.subscriberId }.distinct())
            .filter { it.state == EntityState.ACTIVE }
            .associateBy { it.id }
        return subscriptions.mapNotNull { subscription ->
            subscribersById[subscription.subscriberId]?.let { subscriber ->
                SubscriptionSubscriber(subscription, subscriber)
            }
        }
    }

    public fun findPublisherIdsBySubscriberId(subscriberId: Int): List<Int> {
        return transaction {
            SubscriptionTable
                .selectAll()
                .where { SubscriptionTable.subscriberId eq EntityID(subscriberId, SubscriberTable) }
                .map { it[SubscriptionTable.publisherId].value }
                .distinct()
        }
    }

    public fun subscribe(
        subscriberId: Int,
        publisherId: Int,
        policy: SubscriptionPolicy = SubscriptionPolicy.default(),
    ): SubscriptionMutationResult {
        val subscriber = SubscriberRepository.findById(subscriberId)
        require(subscriber != null) { "订阅目标不存在：subscriberId=$subscriberId" }
        val publisher = PublisherRepository.findById(publisherId)
        require(publisher != null) { "发布者不存在：publisherId=$publisherId" }

        val now = nowInstant()
        val inserted = transaction {
            SubscriptionTable.insertIgnore {
                it[SubscriptionTable.subscriberId] = EntityID(subscriberId, SubscriberTable)
                it[SubscriptionTable.publisherId] = EntityID(publisherId, PublisherTable)
                it[SubscriptionTable.state] = EntityState.ACTIVE
                it[SubscriptionTable.policy] = policy
                it[createdAt] = now
                it[createdBy] = 0
                it[updatedAt] = now
            }.insertedCount > 0
        }
        if (inserted) {
            val subscription = findBySubscriberAndPublisher(subscriberId, publisherId)
                ?: error("订阅已写入但无法重新读取：subscriberId=$subscriberId publisherId=$publisherId")
            val event = SubscriptionChangedEvent(
                changeType = SubscriptionChangeType.SUBSCRIBED,
                subscription = subscription,
                publisher = publisher,
                subscriber = subscriber,
                changedAtEpochSeconds = subscription.createdAtEpochSeconds,
            )
            return SubscriptionMutationResult(changed = true, event = event)
        }
        return SubscriptionMutationResult(changed = false)
    }

    public fun findBySubscriberAndPublisher(subscriberId: Int, publisherId: Int): Subscription? {
        return transaction {
            SubscriptionTable
                .selectAll()
                .where {
                    (SubscriptionTable.subscriberId eq EntityID(subscriberId, SubscriberTable)) and
                        (SubscriptionTable.publisherId eq EntityID(publisherId, PublisherTable))
                }
                .firstOrNull()
                ?.toSubscription()
        }
    }

    public fun updatePolicy(subscriptionId: Int, policy: SubscriptionPolicy): Subscription {
        require(findById(subscriptionId) != null) { "订阅不存在：subscriptionId=$subscriptionId" }
        transaction {
            SubscriptionTable.update({ SubscriptionTable.id eq subscriptionId }) {
                it[SubscriptionTable.policy] = policy
                it[updatedAt] = nowInstant()
            }
        }
        return findById(subscriptionId) ?: error("subscription disappeared after update: $subscriptionId")
    }

    public fun unsubscribe(subscriberId: Int, publisherId: Int): SubscriptionMutationResult {
        val subscription = findBySubscriberAndPublisher(subscriberId, publisherId)
            ?: return SubscriptionMutationResult(changed = false)
        val subscriber = SubscriberRepository.findById(subscription.subscriberId)
        val publisher = PublisherRepository.findById(subscription.publisherId)
        DynamicFilterRuleRepository.clearBySubscriptionId(subscription.id)
        val removed = transaction {
            SubscriptionTable.deleteWhere { SubscriptionTable.id eq subscription.id } > 0
        }
        if (removed && subscriber != null && publisher != null) {
            val event = SubscriptionChangedEvent(
                changeType = SubscriptionChangeType.UNSUBSCRIBED,
                subscription = subscription,
                publisher = publisher,
                subscriber = subscriber,
                changedAtEpochSeconds = nowInstant().epochSeconds,
            )
            return SubscriptionMutationResult(changed = true, event = event)
        }
        return SubscriptionMutationResult(changed = removed)
    }

    public fun countByPublisherId(publisherId: Int): Long {
        return transaction {
            SubscriptionTable
                .selectAll()
                .where { SubscriptionTable.publisherId eq EntityID(publisherId, PublisherTable) }
                .count()
        }
    }

    public fun countAll(): Long {
        return transaction { SubscriptionTable.selectAll().count() }
    }

    public fun findActivePublishersBySourcePlatform(platformId: String): List<Publisher> {
        return findActivePublishersWithSubscribersBySourcePlatform(platformId).map { it.publisher }
    }

    public fun findActivePublishersWithSubscribersBySourcePlatform(platformId: String): List<PublisherSubscribers> {
        val normalizedPlatformId = platformId.trim().lowercase()
        return transaction {
            val publishers = PublisherTable
                .selectAll()
                .where {
                    (PublisherTable.platformId eq normalizedPlatformId) and
                        (PublisherTable.state eq EntityState.ACTIVE)
                }
                .map { it.toPublisher() }

            if (publishers.isEmpty()) return@transaction emptyList()

            val publisherIds = publishers.map { EntityID(it.id, PublisherTable) }
            val subscriptionRows = SubscriptionTable
                .selectAll()
                .where {
                    (SubscriptionTable.publisherId inList publisherIds) and
                        (SubscriptionTable.state eq EntityState.ACTIVE)
                }
                .toList()

            if (subscriptionRows.isEmpty()) return@transaction emptyList()

            val subscriberIds = subscriptionRows
                .map { row -> row[SubscriptionTable.subscriberId].value }
                .distinct()

            val subscribersById = SubscriberTable
                .selectAll()
                .where { SubscriberTable.id inList subscriberIds }
                .map { it.toSubscriber() }
                .filter { it.state == EntityState.ACTIVE }
                .associateBy { it.id }

            val subscriptionsByPublisherId = subscriptionRows
                .mapNotNull { row ->
                    val subscription = row.toSubscription()
                    val subscriber = subscribersById[subscription.subscriberId] ?: return@mapNotNull null
                    subscription.publisherId to SubscriptionSubscriber(subscription, subscriber)
                }
                .groupBy(
                    keySelector = { (publisherId, _) -> publisherId },
                    valueTransform = { (_, subscriptionSubscriber) -> subscriptionSubscriber },
                )

            publishers.mapNotNull { publisher ->
                val subscriptions = subscriptionsByPublisherId[publisher.id].orEmpty()
                if (subscriptions.isEmpty()) null else PublisherSubscribers(publisher, subscriptions)
            }
        }
    }

    public fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? {
        val publisher = PublisherRepository.findById(publisherId) ?: return null
        if (publisher.state != EntityState.ACTIVE) return null

        val subscriptions = findSubscriptionsWithSubscribersByPublisherId(publisherId)
        if (subscriptions.isEmpty()) return null

        return PublisherSubscribers(publisher, subscriptions)
    }

    public fun findAll(): List<Subscription> {
        return transaction {
            SubscriptionTable.selectAll().map { it.toSubscription() }
        }
    }
}

public fun ResultRow.toSubscription(): Subscription = Subscription(
    id = this[SubscriptionTable.id].value,
    subscriberId = this[SubscriptionTable.subscriberId].value,
    publisherId = this[SubscriptionTable.publisherId].value,
    state = this[SubscriptionTable.state],
    createdAtEpochSeconds = this[SubscriptionTable.createdAt].epochSeconds,
    createdBy = this[SubscriptionTable.createdBy],
    updatedAtEpochSeconds = this[SubscriptionTable.updatedAt].epochSeconds,
    policy = this[SubscriptionTable.policy],
)
