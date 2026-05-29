package top.colter.dynamic.admin

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.DynamicAttachmentKind
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MentionMode
import top.colter.dynamic.core.data.MentionRule
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.UpdateSelector
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import top.colter.dynamic.testPublisherInfo

class AdminServerTest {
    @Test
    fun createSubscriptionShouldPersistPublisherSubscriberAndPolicy() = runBlocking {
        initDb("admin-create-subscription")
        val service = service(FakePlatformPublisherPlugin())
        val policy = SubscriptionPolicy(
            updateSelectors = listOf(
                UpdateSelector(
                    eventTypes = setOf(SourceEventType.DYNAMIC_CREATED),
                    attachmentKinds = setOf(DynamicAttachmentKind.VIDEO),
                ),
            ),
            mentionRules = listOf(
                MentionRule(
                    selector = UpdateSelector(eventTypes = setOf(SourceEventType.LIVE_STARTED)),
                    mode = MentionMode.MENTION_ALL,
                ),
            ),
        )

        val response = service.createSubscription(
            CreateSubscriptionRequest(
                subscriberPlatform = "onebot",
                targetKind = "GROUP",
                subscriberTargetId = "100",
                subscriberName = "group",
                publisherPlatform = "bilibili",
                publisherExternalId = "123",
                autoFollow = true,
                policy = policy,
            ),
        )

        assertTrue(response.publisherCreated)
        assertTrue(response.subscriberCreated)
        assertTrue(response.subscriptionCreated)
        assertTrue(response.autoFollowed)
        assertEquals(policy, response.subscription.policy)
        assertEquals("GROUP", response.subscription.subscriber?.targetKind)
        assertEquals("bilibili", response.subscription.publisher?.platformId)
        assertNotNull(SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100")))
    }

    @Test
    fun createSubscriptionShouldRejectMentionAllForNonGroupTargets() = runBlocking {
        initDb("admin-create-subscription-mention")
        val service = service(FakePlatformPublisherPlugin())
        val policy = SubscriptionPolicy(
            mentionRules = listOf(
                MentionRule(
                    selector = UpdateSelector.any(),
                    mode = MentionMode.MENTION_ALL,
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            service.createSubscription(
                CreateSubscriptionRequest(
                    subscriberPlatform = "onebot",
                    targetKind = "USER",
                    subscriberTargetId = "100",
                    publisherPlatform = "bilibili",
                    publisherExternalId = "123",
                    policy = policy,
                ),
            )
        }
    }

    @Test
    fun updatePublisherAndCreateFilterRuleShouldUseNewDataModel() {
        initDb("admin-update-filter")
        val publisher = PublisherRepository.upsertInfo(testPublisherInfo(name = "demo-up")).value
        val subscriber = SubscriberRepository.ensure(TargetAddress.of("onebot", TargetKind.GROUP, "100"), "group")
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)!!
        val service = service(FakePlatformPublisherPlugin())

        val updated = service.updatePublisher(
            publisher.id,
            UpdatePublisherRequest(name = "new-up", headerUri = "https://example.com/banner.png"),
        )
        val rule = service.createFilterRule(
            CreateFilterRuleRequest(
                subscriptionId = subscription.id,
                condition = FilterCondition.HasAttachmentKind(DynamicAttachmentKind.POLL),
                priority = 5,
            ),
        )

        assertEquals("new-up", updated.name)
        assertEquals("https://example.com/banner.png", updated.bannerUri)
        assertEquals(FilterCondition.HasAttachmentKind(DynamicAttachmentKind.POLL), rule.condition)
        assertEquals(1, DynamicFilterRuleRepository.findBySubscriptionId(subscription.id).size)
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-admin-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private fun service(plugin: PlatformPublisherPlugin): AdminService {
        return AdminService(
            pluginProvider = { emptyList() },
            platformPluginResolver = { platformId -> plugin.takeIf { platformId == plugin.platformId } },
            configProvider = { MainDynamicConfig() },
        )
    }

    private class FakePlatformPublisherPlugin : PlatformPublisherPlugin {
        override val platformId: String = "bilibili"
        var followed: Boolean = false

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
            return PublisherInfo(
                key = PublisherKey.of(platformId = platformId, externalId = userId),
                name = "demo-up",
                avatar = MediaRef("https://example.com/face.png", MediaKind.AVATAR),
            )
        }

        override suspend fun queryFollowState(userId: String): FollowState = FollowState.NOT_FOLLOWING

        override suspend fun followPublisher(userId: String): FollowActionResult {
            followed = true
            return FollowActionResult(FollowActionStatus.FOLLOWED)
        }

        override fun init() {}
        override fun start() {}
        override fun stop() {}
        override fun cleanup() {}
    }
}
