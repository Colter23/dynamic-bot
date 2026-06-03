package top.colter.dynamic.admin

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.PluginCatalogConfig
import top.colter.dynamic.SubscriptionConfig
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
import top.colter.dynamic.core.plugin.PlatformDrawAssetDescriptor
import top.colter.dynamic.core.plugin.PlatformDrawAssetKeys
import top.colter.dynamic.core.plugin.PlatformDrawAssetKind
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry
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
import java.io.File

class AdminServerTest {
    @Test
    fun adminStaticRoutesShouldServeShellAssetsAndPageFragments() = testApplication {
        application {
            adminModule(staticRouteContext())
        }

        val shell = client.get("/admin")
        val css = client.get("/admin/assets/admin.css")
        val js = client.get("/admin/assets/shell.js")
        val page = client.get("/admin/pages/dashboard.html")
        val messagesPage = client.get("/admin/pages/messages.html")
        val illegal = client.get("/admin/pages/%2e%2e/x")

        assertEquals(HttpStatusCode.OK, shell.status)
        assertTrue(shell.bodyAsText().contains("/admin/assets/shell.js"))
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue(css.bodyAsText().contains(":root"))
        assertEquals(HttpStatusCode.OK, js.status)
        assertTrue(js.bodyAsText().contains("/admin/pages/dashboard.js"))
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("data-page-root"))
        assertEquals(HttpStatusCode.OK, messagesPage.status)
        assertTrue(messagesPage.bodyAsText().contains("data-page=\"messages\""))
        assertTrue(illegal.status.value in 400..404)
    }

    @Test
    fun adminApiShouldKeepBearerTokenAuthorization() = testApplication {
        application {
            adminModule(staticRouteContext())
        }

        val unauthorized = client.get("/api/plugins")
        val authorized = client.get("/api/plugins") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals(HttpStatusCode.OK, authorized.status)
    }

    @Test
    fun adminApiShouldServePluginCatalog() = testApplication {
        val catalog = """
            {
              "schemaVersion": 1,
              "plugins": [
                {
                  "id": "demo-plugin",
                  "name": "Demo Plugin",
                  "version": "1.0.0",
                  "apiVersion": "4.0.0",
                  "downloadUrl": "https://example.com/demo-plugin.jar",
                  "sha256": "${"a".repeat(64)}",
                  "sizeBytes": 128,
                  "capabilities": ["MESSAGE_SINK"]
                }
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val catalogService = PluginCatalogService(
            configProvider = { PluginCatalogConfig(url = "https://example.com/catalog.json") },
            pluginProvider = { emptyList() },
            pluginInstaller = { _, id, version, _, _ ->
                top.colter.dynamic.plugin.PluginInstallResult(
                    pluginId = id,
                    success = true,
                    changed = true,
                    newVersion = version,
                    message = "ok",
                )
            },
            downloader = StaticCatalogDownloader(catalog),
        )
        application {
            adminModule(staticRouteContext(pluginCatalogService = catalogService))
        }

        val response = client.get("/api/plugin-catalog") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("demo-plugin"))
        assertTrue(response.bodyAsText().contains("NOT_INSTALLED"))
    }

    @Test
    fun adminApiShouldReturnUnifiedJsonForUnexpectedErrors() = testApplication {
        val info = PluginInfo(
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
            pluginStarter = { throw RuntimeException("boom") },
        )
        application {
            adminModule(
                AdminServerContext(
                    token = "test-token",
                    service = service,
                    loginService = AdminLoginService(loginProviderResolver = { null }),
                )
            )
        }

        val response = client.post("/api/plugins/demo-plugin/start") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("boom"))
    }


    @Test
    fun adminApiShouldServePlatformLogoFromRegisteredDrawAssets() = testApplication {
        val registry = PlatformDrawAssetRegistry().apply {
            registerPluginAssets(
                pluginId = "bilibili-plugin",
                descriptors = listOf(
                    PlatformDrawAssetDescriptor(
                        platformId = "bilibili",
                        key = PlatformDrawAssetKeys.PRIMARY_LOGO,
                        kind = PlatformDrawAssetKind.LOGO,
                        resourcePath = "image/banner.jpg",
                        mimeType = "image/jpeg",
                    ),
                ),
                classLoader = javaClass.classLoader,
            )
        }
        application {
            adminModule(staticRouteContext(drawAssetRegistry = registry))
        }

        val response = client.get("/api/platforms/bilibili/logo") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsBytes().isNotEmpty())
    }

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
    fun createSubscriptionShouldRespectAutoFollowConfig() = runBlocking {
        initDb("admin-create-subscription-auto-follow-disabled")
        val plugin = FakePublisherFollowPlugin()
        val service = service(
            plugin = plugin,
            config = MainDynamicConfig(
                subscription = SubscriptionConfig(autoFollowPublisherOnSubscribe = false),
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

        assertFalse(response.autoFollowed)
        assertFalse(plugin.followed)
    }

    @Test
    fun createSubscriptionShouldDeriveSubscriberNameFromMessageTargetCandidate() = runBlocking {
        initDb("admin-create-subscription-target-name")
        val avatar = MediaRef("https://example.com/onebot-group.png", MediaKind.AVATAR)
        val service = service(
            plugin = FakePublisherFollowPlugin(),
            sink = FakeMessageSinkPlugin(
                listOf(MessageTargetCandidate(TargetAddress.of("onebot", TargetKind.GROUP, "100"), "测试群", avatar)),
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
        assertEquals(avatar.uri, response.subscription.subscriber?.avatarUri)
        assertEquals(avatar, SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100"))?.avatar)
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
    fun subscriberTargetsShouldIncludeCandidateAvatar() = runBlocking {
        initDb("admin-subscriber-target-avatar")
        val avatar = MediaRef("https://example.com/group-avatar.png", MediaKind.AVATAR)
        val service = service(
            plugin = FakePublisherFollowPlugin(),
            sink = FakeMessageSinkPlugin(
                listOf(MessageTargetCandidate(TargetAddress.of("onebot", TargetKind.GROUP, "100"), "测试群", avatar)),
            ),
        )

        val target = service.subscriberTargets(platformId = "onebot", type = "GROUP").single()

        assertEquals("测试群", target.name)
        assertEquals(avatar.uri, target.avatarUri)
    }

    @Test
    fun createSubscriberShouldPersistTargetAndLinkParseMode() = runBlocking {
        initDb("admin-create-subscriber-link-parse")
        val avatar = MediaRef("https://example.com/group-avatar.png", MediaKind.AVATAR)
        val service = service(
            plugin = FakePublisherFollowPlugin(),
            sink = FakeMessageSinkPlugin(
                listOf(MessageTargetCandidate(TargetAddress.of("onebot", TargetKind.GROUP, "100"), "测试群", avatar)),
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
        assertEquals(avatar.uri, created.avatarUri)
        assertEquals("ALWAYS", created.linkParseTriggerMode)
        assertEquals("CUSTOM", created.linkParseConfigSource)
        assertEquals(LinkParseTriggerMode.ALWAYS, LinkParseTargetConfigRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100"))?.triggerMode)
        assertEquals(avatar, SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100"))?.avatar)
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
        val filtered = service.deliveries(
            platformId = "onebot",
            targetKind = "GROUP",
            query = "network",
            limit = 10,
        )
        val missed = service.deliveries(query = "missing", limit = 10)
        val dashboard = service.dashboard()

        assertEquals("FAILED", deliveries.single().status)
        assertEquals(deliveries.single().id, filtered.single().id)
        assertTrue(missed.isEmpty())
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
                timestampEpochMillis = 150,
                level = "WARN",
                loggerName = "test.warn",
                threadName = "test",
                message = "careful",
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
        val warningsAndErrors = service.logs(level = "ERROR,WARN", limit = 10)
        val afterFirst = service.logs(since = 1, limit = 10)

        assertEquals(listOf("boom"), errors.entries.map { it.message })
        assertEquals(listOf("careful", "boom"), warningsAndErrors.entries.map { it.message })
        assertEquals(listOf("careful", "boom"), afterFirst.entries.map { it.message })
    }

    @Test
    fun logsShouldTrimCapacityCoerceLimitAndMatchThrowableKeyword() {
        AdminLogBuffer.clearForTest(capacity = 3)
        try {
            repeat(5) { index ->
                AdminLogBuffer.append(
                    AdminLogRecord(
                        seq = AdminLogBuffer.nextSequence(),
                        timestampEpochMillis = index.toLong(),
                        level = "INFO",
                        loggerName = "test.capacity",
                        threadName = "test",
                        message = "message-$index",
                        throwable = if (index == 4) "needle throwable stack" else null,
                    )
                )
            }
            val service = service(FakePublisherFollowPlugin())

            val all = service.logs(limit = 100)
            val coercedLimit = service.logs(limit = 0)
            val keyword = service.logs(query = "needle", limit = 10)

            assertEquals(3, all.capacity)
            assertEquals(3, all.retainedCount)
            assertEquals(listOf("message-2", "message-3", "message-4"), all.entries.map { it.message })
            assertEquals(listOf("message-4"), coercedLimit.entries.map { it.message })
            assertEquals(listOf("message-4"), keyword.entries.map { it.message })
        } finally {
            AdminLogBuffer.clearForTest()
        }
    }

    @Test
    fun logsShouldTruncateLongMessageAndThrowableBeforeBuffering() {
        AdminLogBuffer.clearForTest()
        try {
            AdminLogBuffer.append(
                AdminLogRecord(
                    seq = AdminLogBuffer.nextSequence(),
                    timestampEpochMillis = 100,
                    level = "ERROR",
                    loggerName = "test.truncate",
                    threadName = "test",
                    message = "m".repeat(AdminLogBuffer.MAX_MESSAGE_CHARS + 200),
                    throwable = "t".repeat(AdminLogBuffer.MAX_THROWABLE_CHARS + 200),
                )
            )
            val entry = service(FakePublisherFollowPlugin()).logs(limit = 10).entries.single()

            assertTrue(entry.message.length <= AdminLogBuffer.MAX_MESSAGE_CHARS)
            assertTrue(entry.message.contains("已截断"))
            assertTrue(entry.throwable!!.length <= AdminLogBuffer.MAX_THROWABLE_CHARS)
            assertTrue(entry.throwable.contains("已截断"))
        } finally {
            AdminLogBuffer.clearForTest()
        }
    }

    @Test
    fun dashboardShouldReadRecentWarningsAndErrorsFromFullLogBuffer() = runBlocking {
        initDb("admin-dashboard-log-buffer")
        AdminLogBuffer.clearForTest(capacity = 100)
        try {
            AdminLogBuffer.append(
                AdminLogRecord(
                    seq = AdminLogBuffer.nextSequence(),
                    timestampEpochMillis = 100,
                    level = "ERROR",
                    loggerName = "test.dashboard",
                    threadName = "test",
                    message = "early boom",
                )
            )
            repeat(50) { index ->
                AdminLogBuffer.append(
                    AdminLogRecord(
                        seq = AdminLogBuffer.nextSequence(),
                        timestampEpochMillis = 200 + index.toLong(),
                        level = "INFO",
                        loggerName = "test.dashboard",
                        threadName = "test",
                        message = "info-$index",
                    )
                )
            }

            val dashboard = service(FakePublisherFollowPlugin()).dashboard()

            assertEquals(listOf("early boom"), dashboard.recentLogs.map { it.message })
        } finally {
            AdminLogBuffer.clearForTest()
        }
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

    private fun staticRouteContext(
        drawAssetRegistry: PlatformDrawAssetRegistry = PlatformDrawAssetRegistry(),
        pluginCatalogService: PluginCatalogService? = null,
    ): AdminServerContext {
        return AdminServerContext(
            token = "test-token",
            service = AdminService(
                pluginProvider = { emptyList() },
                publisherLookupResolver = { null },
                publisherFollowResolver = { null },
                configProvider = { MainDynamicConfig() },
                pluginCatalogService = pluginCatalogService,
            ),
            loginService = AdminLoginService(
                loginProviderResolver = { null },
            ),
            drawAssetRegistry = drawAssetRegistry,
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

    private class StaticCatalogDownloader(
        private val catalogBytes: ByteArray,
    ) : PluginCatalogDownloader {
        override fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray {
            return catalogBytes
        }

        override fun downloadToFile(
            url: String,
            destination: File,
            timeoutSeconds: Double,
            maxBytes: Long,
        ): PluginCatalogDownloadResult {
            destination.parentFile?.mkdirs()
            destination.writeBytes(ByteArray(0))
            return PluginCatalogDownloadResult(bytesRead = 0, sha256 = "")
        }
    }
}
