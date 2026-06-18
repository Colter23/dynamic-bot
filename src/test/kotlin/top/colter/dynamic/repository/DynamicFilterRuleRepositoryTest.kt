package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.DynamicBlockKind
import top.colter.dynamic.core.data.DynamicFilterAction
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testTargetAddress

class DynamicFilterRuleRepositoryTest {
    @Test
    fun shouldAddFindRemoveAndClearSubscriptionFilterRules() {
        initTestDatabase("dynamic-bot-core-filter-db")
        val subscriptionId = seedSubscription()

        val imageRule = DynamicFilterRuleRepository.addRule(
            subscriptionId,
            FilterCondition.HasElement(DynamicBlockKind.IMAGE),
        )
        val keywordRule = DynamicFilterRuleRepository.addRule(
            subscriptionId,
            FilterCondition.TextContains("spoiler"),
            DynamicFilterAction.ALLOW,
        )
        val regexRule = DynamicFilterRuleRepository.addRule(
            subscriptionId,
            FilterCondition.TextRegex("foo\\s+bar"),
        )

        assertTrue(imageRule.created)
        assertEquals(DynamicFilterAction.BLOCK, imageRule.value.action)
        assertEquals(DynamicFilterAction.ALLOW, keywordRule.value.action)
        assertEquals(FilterCondition.TextContains("spoiler"), keywordRule.value.condition)
        assertEquals(FilterCondition.TextRegex("foo\\s+bar"), regexRule.value.condition)
        assertEquals(3, DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId).size)
        assertEquals(
            listOf(imageRule.value.id, keywordRule.value.id, regexRule.value.id).sorted(),
            DynamicFilterRuleRepository.findBySubscriptionIds(listOf(subscriptionId))[subscriptionId]
                .orEmpty()
                .map { it.id }
                .sorted(),
        )

        assertTrue(DynamicFilterRuleRepository.removeById(keywordRule.value.id))
        assertEquals(2, DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId).size)
        assertEquals(2, DynamicFilterRuleRepository.clearBySubscriptionId(subscriptionId))
        assertTrue(DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId).isEmpty())
    }

    @Test
    fun shouldRejectUnknownSubscriptionInvalidRegexAndBlankText() {
        initTestDatabase("dynamic-bot-core-filter-invalid-db")
        val subscriptionId = seedSubscription()

        assertFailsWith<IllegalArgumentException> {
            DynamicFilterRuleRepository.addRule(
                404,
                FilterCondition.HasElement(DynamicBlockKind.IMAGE),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DynamicFilterRuleRepository.addRule(subscriptionId, FilterCondition.TextRegex("["))
        }
        assertFailsWith<IllegalArgumentException> {
            DynamicFilterRuleRepository.addRule(subscriptionId, FilterCondition.TextContains(" "))
        }
    }

    @Test
    fun unsubscribeShouldClearFilterRules() {
        initTestDatabase("dynamic-bot-core-filter-unsubscribe-db")
        val subscriptionId = seedSubscription()
        val subscription = SubscriptionRepository.findById(subscriptionId)!!
        DynamicFilterRuleRepository.addRule(
            subscriptionId,
            FilterCondition.TextContains("spoiler"),
        )

        assertTrue(SubscriptionRepository.unsubscribe(subscription.subscriberId, subscription.publisherId).changed)
        assertTrue(DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId).isEmpty())
    }

    private fun seedSubscription(): Int {
        PublisherRepository.create(testPublisher(id = 1))
        val subscriber = SubscriberRepository.upsert(
            address = testTargetAddress(kind = TargetKind.GROUP, externalId = "100"),
            name = "group",
        ).value
        assertTrue(SubscriptionRepository.subscribe(subscriber.id, 1).changed)
        return SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, 1)!!.id
    }
}
