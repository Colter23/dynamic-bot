package top.colter.dynamic.admin

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.DynamicBlockKind
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.draw.PublisherThemeInitializer
import top.colter.dynamic.plugin.PluginCapability
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.repository.DynamicFilterRuleRepository
import top.colter.dynamic.repository.LinkParseTargetConfigRepository
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.PublisherDrawThemeRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.testPublisherInfo

class AdminServerTest {
    @Test
    fun createSubscriptionShouldPersistPublisherSubscriberAndPolicy() = runBlocking {
        initDb("admin-create-subscription")
        val service = service(FakePublisherFollowPlugin())
        val policy = SubscriptionPolicy(
            enabledEvents = setOf(SubscriptionEventKind.DYNAMIC, SubscriptionEventKind.LIVE_STARTED),
            mentionAllEvents = setOf(SubscriptionEventKind.LIVE_STARTED),
        )

        val response = service.createSubscription(
            CreateSubscriptionRequest(
                subscriberPlatform = "onebot",
                targetKind = "GROUP",
                subscriberTargetId = "100",
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
        assertEquals("100", response.subscription.subscriber?.name)
        assertEquals("bilibili", response.subscription.publisher?.platformId)
        assertNotNull(SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100")))
    }

    @Test
    fun createSubscriptionShouldDeriveSubscriberNameFromMessageTargetCandidate() = runBlocking {
        initDb("admin-create-subscription-target-name")
        val service = service(
            plugin = FakePublisherFollowPlugin(),
            sink = FakeMessageSinkPlugin(
                listOf(MessageTargetCandidate(TargetAddress.of("onebot", TargetKind.GROUP, "100"), "测试群")),
            ),
        )

        val response = service.createSubscription(
            CreateSubscriptionRequest(
                subscriberPlatform = "onebot",
                targetKind = "GROUP",
                subscriberTargetId = "100",
                publisherPlatform = "bilibili",
                publisherExternalId = "123",
            ),
        )

        assertEquals("测试群", response.subscription.subscriber?.name)
    }

    @Test
    fun createSubscriptionShouldRejectMentionAllForNonGroupTargets() = runBlocking {
        initDb("admin-create-subscription-mention")
        val service = service(FakePublisherFollowPlugin())
        val policy = SubscriptionPolicy(
            enabledEvents = setOf(SubscriptionEventKind.DYNAMIC),
            mentionAllEvents = setOf(SubscriptionEventKind.DYNAMIC),
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
        val service = service(FakePublisherFollowPlugin())

        val updated = service.updatePublisher(
            publisher.id,
            UpdatePublisherRequest(name = "new-up", headerUri = "https://example.com/banner.png"),
        )
        val rule = service.createFilterRule(
            subscription.id,
            CreateFilterRuleRequest(
                condition = FilterCondition.HasBlockKind(DynamicBlockKind.POLL),
            ),
        )

        assertEquals("new-up", updated.name)
        assertEquals("https://example.com/banner.png", updated.bannerUri)
        assertEquals(FilterCondition.HasBlockKind(DynamicBlockKind.POLL), rule.condition)
        assertEquals(1, DynamicFilterRuleRepository.findBySubscriptionId(subscription.id).size)
    }

    @Test
    fun subscriptionsShouldIncludeFilterRulesAndEntityCounts() {
        initDb("admin-subscription-ops")
        val publisher = PublisherRepository.upsertInfo(testPublisherInfo(name = "demo-up")).value
        val subscriber = SubscriberRepository.ensure(TargetAddress.of("onebot", TargetKind.GROUP, "100"), "group")
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)!!
        DynamicFilterRuleRepository.addRule(
            subscription.id,
            FilterCondition.HasBlockKind(DynamicBlockKind.VIDEO),
        )
        val service = service(FakePublisherFollowPlugin())

        val dto = service.subscriptions().single()
        val publisherDto = service.publishers().single()
        val subscriberDto = service.subscribers().single()

        assertEquals(1, dto.filterRuleCount)
        assertEquals(FilterCondition.HasBlockKind(DynamicBlockKind.VIDEO), dto.filterRules.single().condition)
        assertEquals(1, publisherDto.subscriptionCount)
        assertEquals(1, subscriberDto.subscriptionCount)
    }

    @Test
    fun subscribersShouldIncludeLinkParseConfigAndFallback() {
        initDb("admin-subscriber-link-parse-list")
        val custom = SubscriberRepository.ensure(TargetAddress.of("onebot", TargetKind.GROUP, "100"), "custom")
        val fallback = SubscriberRepository.ensure(TargetAddress.of("onebot", TargetKind.GROUP, "200"), "fallback")
        LinkParseTargetConfigRepository.upsert(custom.address, LinkParseTriggerMode.DISABLED, updatedBy = "test")
        val service = service(
            plugin = FakePublisherFollowPlugin(),
            config = MainDynamicConfig(
                linkParsing = LinkParsingConfig(fallbackTriggerMode = LinkParseTriggerMode.ALWAYS),
            ),
        )

        val subscribers = service.subscribers().associateBy { it.externalId }

        assertEquals("DISABLED", subscribers.getValue("100").linkParseTriggerMode)
        assertEquals("DISABLED", subscribers.getValue("100").effectiveLinkParseTriggerMode)
        assertEquals("CUSTOM", subscribers.getValue("100").linkParseConfigSource)
        assertNull(subscribers.getValue("200").linkParseTriggerMode)
        assertEquals("ALWAYS", subscribers.getValue("200").effectiveLinkParseTriggerMode)
        assertEquals("FALLBACK", subscribers.getValue("200").linkParseConfigSource)
        assertEquals(fallback.id, SubscriberRepository.findByAddress(fallback.address)?.id)
    }

    @Test
    fun createSubscriberShouldPersistTargetAndLinkParseMode() = runBlocking {
        initDb("admin-create-subscriber-link-parse")
        val service = service(
            plugin = FakePublisherFollowPlugin(),
            sink = FakeMessageSinkPlugin(
                listOf(MessageTargetCandidate(TargetAddress.of("onebot", TargetKind.GROUP, "100"), "测试群")),
            ),
        )

        val created = service.createSubscriber(
            CreateSubscriberRequest(
                platformId = "onebot",
                targetKind = "GROUP",
                externalId = "100",
                linkParseTriggerMode = "ALWAYS",
            ),
        )

        assertEquals("测试群", created.name)
        assertEquals("ALWAYS", created.linkParseTriggerMode)
        assertEquals("CUSTOM", created.linkParseConfigSource)
        assertEquals(LinkParseTriggerMode.ALWAYS, LinkParseTargetConfigRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100"))?.triggerMode)
    }

    @Test
    fun updateAndDeleteSubscriberShouldManageLinkParseConfig() {
        initDb("admin-update-subscriber-link-parse")
        val subscriber = SubscriberRepository.ensure(TargetAddress.of("onebot", TargetKind.GROUP, "100"), "group")
        val service = service(FakePublisherFollowPlugin())

        val updated = service.updateSubscriber(
            subscriber.id,
            UpdateSubscriberRequest(linkParseTriggerMode = "MENTION_ONLY"),
        )

        assertEquals("MENTION_ONLY", updated.linkParseTriggerMode)
        assertEquals(LinkParseTriggerMode.MENTION_ONLY, LinkParseTargetConfigRepository.findByAddress(subscriber.address)?.triggerMode)

        val cleared = service.updateSubscriber(
            subscriber.id,
            UpdateSubscriberRequest(clearLinkParseTrigger = true),
        )

        assertNull(cleared.linkParseTriggerMode)
        assertNull(LinkParseTargetConfigRepository.findByAddress(subscriber.address))

        service.updateSubscriber(subscriber.id, UpdateSubscriberRequest(linkParseTriggerMode = "ALWAYS"))
        service.deleteSubscriber(subscriber.id)
        assertNull(LinkParseTargetConfigRepository.findByAddress(subscriber.address))
    }

    @Test
    fun updatePublisherShouldSetClearAndListDrawTheme() {
        initDb("admin-publisher-theme")
        val publisher = PublisherRepository.upsertInfo(testPublisherInfo(name = "demo-up")).value
        val service = service(FakePublisherFollowPlugin())

        val themed = service.updatePublisher(
            publisher.id,
            UpdatePublisherRequest(themeColors = "#FE65A6;#BFFAFF"),
        )
        val listed = service.publishers().single()

        assertNotNull(themed.drawTheme)
        val listedTheme = assertNotNull(listed.drawTheme)
        assertEquals(
            listedTheme.backgroundColors,
            PublisherDrawThemeRepository.findByPublisherId(publisher.id)!!.palette.backgroundColors,
        )

        val cleared = service.updatePublisher(
            publisher.id,
            UpdatePublisherRequest(clearTheme = true),
        )

        assertNull(cleared.drawTheme)
        assertNull(PublisherDrawThemeRepository.findByPublisherId(publisher.id))
    }

    @Test
    fun deliveriesShouldReturnRecentRowsAndDashboardCounts() = runBlocking {
        initDb("admin-delivery-dashboard")
        val service = service(FakePublisherFollowPlugin())
        val message = Message(
            id = "message-admin",
            time = 1L,
            targets = listOf(TargetAddress.of("onebot", TargetKind.GROUP, "100")),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )
        MessageDeliveryRepository.enqueue(message)
        val delivery = MessageDeliveryRepository.findByMessageId(message.id).single()
        MessageDeliveryRepository.markFailed(delivery.id, "network")

        val deliveries = service.deliveries(status = "FAILED", limit = 10)
        val dashboard = service.dashboard()

        assertEquals("FAILED", deliveries.single().status)
        assertTrue(dashboard.deliveryStatusCounts.any { it.state == DeliveryStatus.FAILED.name && it.count == 1L })
    }

    @Test
    fun logsShouldFilterBySinceAndLevel() {
        AdminLogBuffer.clearForTest()
        AdminLogBuffer.append(
            AdminLogRecord(
                seq = AdminLogBuffer.nextSequence(),
                timestampEpochMillis = 100,
                level = "INFO",
                loggerName = "test.info",
                threadName = "test",
                message = "ready",
            )
        )
        AdminLogBuffer.append(
            AdminLogRecord(
                seq = AdminLogBuffer.nextSequence(),
                timestampEpochMillis = 200,
                level = "ERROR",
                loggerName = "test.error",
                threadName = "test",
                message = "boom",
            )
        )
        val service = service(FakePublisherFollowPlugin())

        val errors = service.logs(level = "ERROR", limit = 10)
        val afterFirst = service.logs(since = 1, limit = 10)

        assertEquals(listOf("boom"), errors.entries.map { it.message })
        assertEquals(listOf("boom"), afterFirst.entries.map { it.message })
    }

    @Test
    fun pluginLifecycleShouldStartAndStopLoadedPlugins() {
        var info = PluginInfo(
            descriptor = PluginDescriptor(
                id = "demo-plugin",
                name = "Demo",
                version = "1.0.0",
                mainClass = "Demo",
            ),
            capabilities = setOf(PluginCapability.MESSAGE_SINK),
            state = PluginState.LOADED,
            sourceJarPath = "plugins/demo.jar",
        )
        val service = AdminService(
            pluginProvider = { listOf(info) },
            publisherLookupResolver = { null },
            publisherFollowResolver = { null },
            configProvider = { MainDynamicConfig() },
            pluginStarter = { info = info.copy(state = PluginState.ACTIVE) },
            pluginStopper = { info = info.copy(state = PluginState.LOADED) },
        )

        assertTrue(service.startPlugin("demo-plugin").changed)
        assertEquals("ACTIVE", service.plugins().single().state)
        assertTrue(service.stopPlugin("demo-plugin").changed)
        assertEquals("LOADED", service.plugins().single().state)
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-admin-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private fun service(
        plugin: PublisherFollowPlugin,
        sink: MessageSinkPlugin? = null,
        config: MainDynamicConfig = MainDynamicConfig(),
    ): AdminService {
        return AdminService(
            pluginProvider = { emptyList() },
            messageSinkProvider = {
                sink?.let {
                    listOf(
                        PluginHandle(
                            PluginInfo(
                                descriptor = PluginDescriptor("onebot", "OneBot", "1.0.0", "OneBot"),
                                capabilities = setOf(PluginCapability.MESSAGE_SINK),
                                state = PluginState.ACTIVE,
                                sourceJarPath = "plugins/onebot.jar",
                            ),
                            it,
                        )
                    )
                }.orEmpty()
            },
            publisherLookupResolver = { platformId -> plugin.takeIf { platformId == plugin.platformId.value } },
            publisherFollowResolver = { platformId -> plugin.takeIf { platformId == plugin.platformId.value } },
            configProvider = { config },
            publisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
        )
    }

    private class FakePublisherFollowPlugin : PublisherFollowPlugin {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        var followed: Boolean = false

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
            return PublisherInfo(
                key = PublisherKey.of(platformId = platformId.value, externalId = userId),
                name = "demo-up",
                avatar = MediaRef("https://example.com/face.png", MediaKind.AVATAR),
            )
        }

        override suspend fun queryFollowState(userId: String): FollowState = FollowState.NOT_FOLLOWING

        override suspend fun followPublisher(userId: String): FollowActionResult {
            followed = true
            return FollowActionResult(FollowActionStatus.FOLLOWED)
        }
    }

    private class FakeMessageSinkPlugin(
        private val targets: List<MessageTargetCandidate>,
    ) : MessageSinkPlugin {
        override val platformId: PlatformId = PlatformId.of("onebot")
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP, TargetKind.USER)

        override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> {
            return targets.filter { kind == null || it.address.kind == kind }
        }
    }
}
