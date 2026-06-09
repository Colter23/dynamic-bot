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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.PluginCatalogConfig
import top.colter.dynamic.SubscriptionConfig
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.DynamicBlockKind
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformCapability
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
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
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskSnapshot
import top.colter.dynamic.core.task.TaskStatus
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry
import top.colter.dynamic.draw.PublisherThemeInitializer
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.plugin.PluginCapability
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.plugin.PluginTaskInfo
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
import kotlin.time.Duration.Companion.seconds
import top.colter.dynamic.task.DefaultTaskScheduler

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
        val tasksPage = client.get("/admin/pages/tasks.html")
        val illegal = client.get("/admin/pages/%2e%2e/x")

        assertEquals(HttpStatusCode.OK, shell.status)
        assertTrue(shell.bodyAsText().contains("/admin/assets/shell.js"))
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue(css.bodyAsText().contains(":root"))
        assertEquals(HttpStatusCode.OK, js.status)
        assertTrue(js.bodyAsText().contains("/admin/pages/dashboard.js"))
        assertTrue(js.bodyAsText().contains("/admin/pages/tasks.js"))
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("data-page-root"))
        assertEquals(HttpStatusCode.OK, messagesPage.status)
        assertTrue(messagesPage.bodyAsText().contains("data-page=\"messages\""))
        assertEquals(HttpStatusCode.OK, tasksPage.status)
        assertTrue(tasksPage.bodyAsText().contains("data-page=\"tasks\""))
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
    fun adminApiShouldServeRegisteredCommands() = testApplication {
        val registry = CommandRegistry().apply {
            register(
                FakeCommandHandler(
                    CommandSpec(
                        path = listOf("link", "set"),
                        aliases = listOf(listOf("link", "config")),
                        description = "设置链接解析触发方式",
                        usage = "link set <off|mention|always>",
                        requiredRole = CommandRole.MANAGER,
                    )
                ),
                "main",
            )
        }
        val service = AdminService(
            pluginProvider = { emptyList() },
            publisherLookupResolver = { null },
            publisherFollowResolver = { null },
            configProvider = { MainDynamicConfig() },
            commandRegistry = registry,
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

        val response = client.get("/api/commands") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"pathText\":\"link set\""))
        assertTrue(body.contains("\"aliases\":[[\"link\",\"config\"]]"))
        assertTrue(body.contains("\"requiredRole\":\"MANAGER\""))
    }

    @Test
    fun adminApiShouldServeAndOperateMainTasks() = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scheduler = DefaultTaskScheduler(scope)
        scheduler.start(
            TaskDefinition(
                id = "main-demo",
                name = "测试主任务",
                description = "用于后台任务 API 测试",
                schedule = TaskSchedule.FixedDelay(1.seconds),
                action = {},
            )
        )
        try {
            val service = AdminService(
                pluginProvider = { emptyList() },
                publisherLookupResolver = { null },
                publisherFollowResolver = { null },
                configProvider = { MainDynamicConfig() },
                mainTaskScheduler = scheduler,
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

            val list = client.get("/api/tasks") {
                header(HttpHeaders.Authorization, "Bearer test-token")
            }
            val stopped = client.post("/api/tasks/stop?ownerType=MAIN&ownerId=main&taskId=main-demo") {
                header(HttpHeaders.Authorization, "Bearer test-token")
            }
            val restarted = client.post("/api/tasks/start?ownerType=MAIN&ownerId=main&taskId=main-demo") {
                header(HttpHeaders.Authorization, "Bearer test-token")
            }

            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("\"id\":\"main-demo\""))
            assertTrue(list.bodyAsText().contains("\"name\":\"测试主任务\""))
            assertTrue(list.bodyAsText().contains("\"description\":\"用于后台任务 API 测试\""))
            assertTrue(list.bodyAsText().contains("\"scheduleType\":\"FIXED_DELAY\""))
            assertEquals(HttpStatusCode.OK, stopped.status)
            assertTrue(stopped.bodyAsText().contains("\"status\":\"CANCELLED\""))
            assertEquals(HttpStatusCode.OK, restarted.status)
            assertTrue(restarted.bodyAsText().contains("\"status\":\"RUNNING\""))
        } finally {
            runBlocking { scheduler.shutdown() }
            scope.cancel()
        }
    }

    @Test
    fun adminServiceShouldServeAndOperatePluginTasks() = runBlocking {
        var snapshot = TaskSnapshot(
            id = "plugin-poll",
            name = "插件轮询",
            description = "测试插件任务描述",
            status = TaskStatus.CANCELLED,
            schedule = TaskSchedule.Once,
            retryBackoffMillis = 5000,
            nextRunAtMillis = null,
            lastRunAtMillis = 100,
            lastSuccessAtMillis = 100,
            runCount = 1,
            lastErrorSummary = null,
        )
        val pluginTask = {
            PluginTaskInfo(
                pluginId = "bilibili",
                pluginName = "Bilibili",
                pluginVersion = "0.0.1",
                pluginState = PluginState.ACTIVE,
                task = snapshot,
            )
        }
        val service = AdminService(
            pluginProvider = { emptyList() },
            publisherLookupResolver = { null },
            publisherFollowResolver = { null },
            configProvider = { MainDynamicConfig() },
            pluginTaskProvider = { listOf(pluginTask()) },
            pluginTaskResolver = { pluginId, taskId ->
                pluginTask().takeIf { pluginId == it.pluginId && taskId == it.task.id }
            },
            pluginTaskStarter = { _, _ ->
                snapshot = snapshot.copy(status = TaskStatus.RUNNING)
                true
            },
            pluginTaskStopper = { _, _ ->
                snapshot = snapshot.copy(status = TaskStatus.CANCELLED)
                true
            },
            pluginTaskRestarter = { _, _ ->
                snapshot = snapshot.copy(status = TaskStatus.RUNNING, runCount = snapshot.runCount + 1)
                true
            },
        )

        val listed = service.tasks().tasks.single()
        val started = service.startTask("PLUGIN", "bilibili", "plugin-poll")
        val restarted = service.restartTask("PLUGIN", "bilibili", "plugin-poll")

        assertEquals("PLUGIN", listed.ownerType)
        assertEquals("Bilibili", listed.ownerName)
        assertEquals("插件轮询", listed.name)
        assertEquals("测试插件任务描述", listed.description)
        assertEquals("ONCE", listed.scheduleType)
        assertEquals("RUNNING", started.status)
        assertTrue(started.changed)
        assertEquals("RUNNING", restarted.status)
        assertTrue(restarted.changed)
        assertEquals(2L, service.tasks().tasks.single().runCount)
    }

    @Test
    fun adminApiShouldServePluginCatalog() = testApplication {
        val catalog = """
            {
              "schemaVersion": 2,
              "plugins": [
                {
                  "id": "demo-plugin",
                  "name": "Demo Plugin",
                  "release": {
                    "provider": "GITHUB_RELEASE",
                    "repository": "Colter23/demo-plugin",
                    "assetPattern": "demo-plugin-*-all.jar"
                  },
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
            downloader = StaticCatalogDownloader(catalog, sha256 = "a".repeat(64)),
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
        val body = response.bodyAsText()
        assertTrue(body.contains("后台接口处理失败"))
        assertFalse(body.contains("boom"))
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
                subscriberPlatform = "qq",
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
        assertNotNull(SubscriberRepository.findByAddress(TargetAddress.of("qq", TargetKind.GROUP, "100")))
    }

    @Test
    fun updateSubscriptionShouldBroadcastUpdatedEvent() = runBlocking {
        initDb("admin-update-subscription-event")
        val eventBus = EventBus()
        val service = service(FakePublisherFollowPlugin(), eventBus = eventBus)
        val created = service.createSubscription(
            CreateSubscriptionRequest(
                subscriberPlatform = "qq",
                targetKind = "GROUP",
                subscriberTargetId = "100",
                publisherPlatform = "bilibili",
                publisherExternalId = "123",
            ),
        )
        val received = CompletableDeferred<SubscriptionChangedEvent>()
        eventBus.subscribe(
            object : Listener<SubscriptionChangedEvent> {
                override suspend fun onMessage(event: SubscriptionChangedEvent) {
                    if (event.changeType == SubscriptionChangeType.UPDATED) {
                        received.complete(event)
                    }
                }
            }
        )
        val policy = SubscriptionPolicy(
            enabledEvents = setOf(SubscriptionEventKind.LIVE_STARTED, SubscriptionEventKind.LIVE_ENDED),
        )

        service.updateSubscription(created.subscription.id, UpdateSubscriptionRequest(policy))

        val event = withTimeout(3_000) { received.await() }
        assertEquals(SubscriptionChangeType.UPDATED, event.changeType)
        assertEquals(created.subscription.id, event.subscription.id)
        assertEquals(policy, event.subscription.policy)
        eventBus.shutdown()
    }

    @Test
    fun createPublisherShouldInitializeDrawTheme() = runBlocking {
        initDb("admin-create-publisher-theme")
        var initializedPublisherId: Int? = null
        var previousSubscriptionCount: Long? = null
        val service = service(
            plugin = FakePublisherFollowPlugin(),
            publisherThemeInitializer = PublisherThemeInitializer { publisher, count ->
                initializedPublisherId = publisher.id
                previousSubscriptionCount = count
            },
        )

        val publisher = service.createPublisher(
            CreatePublisherRequest(
                platformId = "bilibili",
                externalId = "123",
            ),
        )

        assertEquals(publisher.id, initializedPublisherId)
        assertEquals(0L, previousSubscriptionCount)
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
                subscriberPlatform = "qq",
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
    fun createSubscriptionShouldWarnButSucceedWithoutFollowPlugin() = runBlocking {
        initDb("admin-create-subscription-no-follow-plugin")
        val service = service(
            plugin = FakePublisherLookupPlugin(),
            followPlugin = null,
        )

        val response = service.createSubscription(
            CreateSubscriptionRequest(
                subscriberPlatform = "qq",
                targetKind = "GROUP",
                subscriberTargetId = "100",
                publisherPlatform = "bilibili",
                publisherExternalId = "123",
            ),
        )

        assertTrue(response.subscriptionCreated)
        assertFalse(response.autoFollowed)
        assertEquals(listOf("未找到发布者关注插件，已跳过自动关注：bilibili"), response.warnings)
        assertNotNull(SubscriptionRepository.findById(response.subscription.id))
    }

    @Test
    fun createSubscriptionShouldDeriveSubscriberNameFromMessageTargetCandidate() = runBlocking {
        initDb("admin-create-subscription-target-name")
        val avatar = MediaRef("https://example.com/onebot-group.png", MediaKind.AVATAR)
        val service = service(
            plugin = FakePublisherFollowPlugin(),
            sink = FakeMessageSinkPlugin(
                listOf(MessageTargetCandidate(TargetAddress.of("qq", TargetKind.GROUP, "100"), "测试群", avatar)),
            ),
        )

        val response = service.createSubscription(
            CreateSubscriptionRequest(
                subscriberPlatform = "qq",
                targetKind = "GROUP",
                subscriberTargetId = "100",
                publisherPlatform = "bilibili",
                publisherExternalId = "123",
            ),
        )

        assertEquals("测试群", response.subscription.subscriber?.name)
        assertEquals(avatar.uri, response.subscription.subscriber?.avatarUri)
        assertEquals(avatar, SubscriberRepository.findByAddress(TargetAddress.of("qq", TargetKind.GROUP, "100"))?.avatar)
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
                    subscriberPlatform = "qq",
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
    fun createSubscriptionShouldRejectLiveEventsWhenPublisherPlatformDoesNotSupportLive() = runBlocking {
        initDb("admin-create-subscription-live-unsupported")
        val service = service(FakePublisherLookupPlugin())
        val policy = SubscriptionPolicy(
            enabledEvents = setOf(SubscriptionEventKind.DYNAMIC, SubscriptionEventKind.LIVE_STARTED),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.createSubscription(
                CreateSubscriptionRequest(
                    subscriberPlatform = "qq",
                    targetKind = "GROUP",
                    subscriberTargetId = "100",
                    publisherPlatform = "bilibili",
                    publisherExternalId = "123",
                    policy = policy,
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("不支持开播订阅"))
        assertNull(SubscriptionRepository.findBySubscriberAndPublisher(1, 1))
    }

    @Test
    fun updateSubscriptionShouldRejectLiveEventsWhenPublisherPlatformDoesNotSupportLive() = runBlocking {
        initDb("admin-update-subscription-live-unsupported")
        val service = service(FakePublisherLookupPlugin())
        val created = service.createSubscription(
            CreateSubscriptionRequest(
                subscriberPlatform = "qq",
                targetKind = "GROUP",
                subscriberTargetId = "100",
                publisherPlatform = "bilibili",
                publisherExternalId = "123",
            ),
        )
        val policy = SubscriptionPolicy(
            enabledEvents = setOf(SubscriptionEventKind.LIVE_STARTED, SubscriptionEventKind.LIVE_ENDED),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.updateSubscription(created.subscription.id, UpdateSubscriptionRequest(policy))
        }

        assertTrue(error.message.orEmpty().contains("不支持开播、下播订阅"))
    }

    @Test
    fun updateSubscriptionShouldPreserveExistingLiveEventsWhenPublisherPlatformCapabilityIsUnknown() {
        initDb("admin-update-subscription-live-unknown")
        val publisher = PublisherRepository.upsertInfo(testPublisherInfo()).value
        val subscriber = SubscriberRepository.ensure(TargetAddress.of("qq", TargetKind.GROUP, "100"), "group")
        val livePolicy = SubscriptionPolicy(enabledEvents = setOf(SubscriptionEventKind.LIVE_STARTED))
        SubscriptionRepository.subscribe(subscriber.id, publisher.id, livePolicy)
        val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)!!
        val service = AdminService(
            pluginProvider = { emptyList() },
            publisherLookupResolver = { null },
            publisherFollowResolver = { null },
            configProvider = { MainDynamicConfig() },
        )

        val updated = service.updateSubscription(
            subscription.id,
            UpdateSubscriptionRequest(
                livePolicy.copy(enabledEvents = setOf(SubscriptionEventKind.DYNAMIC, SubscriptionEventKind.LIVE_STARTED)),
            ),
        )

        assertEquals(setOf(SubscriptionEventKind.DYNAMIC, SubscriptionEventKind.LIVE_STARTED), updated.policy.enabledEvents)
    }

    @Test
    fun updatePublisherAndCreateFilterRuleShouldUseNewDataModel() {
        initDb("admin-update-filter")
        val publisher = PublisherRepository.upsertInfo(testPublisherInfo(name = "demo-up")).value
        val subscriber = SubscriberRepository.ensure(TargetAddress.of("qq", TargetKind.GROUP, "100"), "group")
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
        val subscriber = SubscriberRepository.ensure(TargetAddress.of("qq", TargetKind.GROUP, "100"), "group")
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
        val custom = SubscriberRepository.ensure(TargetAddress.of("qq", TargetKind.GROUP, "100"), "custom")
        val fallback = SubscriberRepository.ensure(TargetAddress.of("qq", TargetKind.GROUP, "200"), "fallback")
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
                listOf(MessageTargetCandidate(TargetAddress.of("qq", TargetKind.GROUP, "100"), "测试群", avatar)),
            ),
        )

        val target = service.subscriberTargets(platformId = "qq", type = "GROUP").single()

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
                listOf(MessageTargetCandidate(TargetAddress.of("qq", TargetKind.GROUP, "100"), "测试群", avatar)),
            ),
        )

        val created = service.createSubscriber(
            CreateSubscriberRequest(
                platformId = "qq",
                targetKind = "GROUP",
                externalId = "100",
                accountId = "bot-1",
                linkParseTriggerMode = "ALWAYS",
            ),
        )

        assertEquals("测试群", created.name)
        assertEquals("bot-1", created.accountId)
        assertEquals(avatar.uri, created.avatarUri)
        assertEquals("ALWAYS", created.linkParseTriggerMode)
        assertEquals("CUSTOM", created.linkParseConfigSource)
        assertEquals(LinkParseTriggerMode.ALWAYS, LinkParseTargetConfigRepository.findByAddress(TargetAddress.of("qq", TargetKind.GROUP, "100"))?.triggerMode)
        assertEquals(avatar, SubscriberRepository.findByAddress(TargetAddress.of("qq", TargetKind.GROUP, "100"))?.avatar)
    }

    @Test
    fun updateAndDeleteSubscriberShouldManageLinkParseConfig() {
        initDb("admin-update-subscriber-link-parse")
        val subscriber = SubscriberRepository.ensure(TargetAddress.of("qq", TargetKind.GROUP, "100"), "group")
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
            targets = listOf(
                TargetAddress.of(
                    platformId = "qq",
                    kind = TargetKind.GROUP,
                    externalId = "100",
                    scopeId = "scope-1",
                    threadId = "thread-1",
                    accountId = "bot-1",
                )
            ),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )
        MessageDeliveryRepository.enqueue(message)
        val delivery = MessageDeliveryRepository.findByMessageId(message.id).single()
        MessageDeliveryRepository.markFailed(delivery.id, "network")

        val deliveries = service.deliveries(status = "FAILED", limit = 10)
        val filtered = service.deliveries(
            platformId = "qq",
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

        val detail = service.delivery(delivery.id)
        assertEquals(delivery.id, detail.delivery.id)
        assertEquals("scope-1", detail.delivery.targetScopeId)
        assertEquals("thread-1", detail.delivery.targetThreadId)
        assertEquals("bot-1", detail.delivery.targetAccountId)
        assertEquals("message-admin", assertNotNull(detail.message).jsonObject["id"]?.jsonPrimitive?.content)
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
        plugin: PublisherLookupPlugin,
        sink: MessageSinkPlugin? = null,
        config: MainDynamicConfig = MainDynamicConfig(),
        followPlugin: PublisherFollowPlugin? = plugin as? PublisherFollowPlugin,
        publisherThemeInitializer: PublisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
        eventBus: EventBus = EventBus(),
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
            publisherFollowResolver = { platformId -> followPlugin?.takeIf { platformId == it.platformId.value } },
            configProvider = { config },
            publisherThemeInitializer = publisherThemeInitializer,
            eventBus = eventBus,
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
        override val platformDescriptor: PlatformDescriptor = PlatformDescriptor.of(
            id = "bilibili",
            displayName = "Bilibili",
            capabilities = setOf(PlatformCapability.PUBLISHER_SOURCE, PlatformCapability.LIVE_SOURCE),
        )
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
            return FollowActionResult(FollowActionStatus.DONE)
        }
    }

    private class FakePublisherLookupPlugin : PublisherLookupPlugin {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo {
            return PublisherInfo(
                key = PublisherKey.of(platformId = platformId.value, externalId = userId),
                name = "demo-up",
                avatar = MediaRef("https://example.com/face.png", MediaKind.AVATAR),
            )
        }
    }

    private class FakeMessageSinkPlugin(
        private val targets: List<MessageTargetCandidate>,
    ) : MessageSinkPlugin {
        override val transportId: String = "onebot"
        override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP, TargetKind.USER)

        override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> {
            return targets.filter { kind == null || it.address.kind == kind }
        }
    }

    private class FakeCommandHandler(
        override val spec: CommandSpec,
    ) : CommandHandler {
        override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
            return CommandExecutionResult.success("ok")
        }
    }

    private class StaticCatalogDownloader(
        private val catalogBytes: ByteArray,
        private val sha256: String = "a".repeat(64),
    ) : PluginCatalogDownloader {
        override fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray {
            if (!url.contains("api.github.com/repos/")) return catalogBytes
            return """
                {
                  "tag_name": "v1.0.0",
                  "html_url": "https://github.com/Colter23/demo-plugin/releases/tag/v1.0.0",
                  "assets": [
                    {
                      "name": "demo-plugin-1.0.0-all.jar",
                      "browser_download_url": "https://github.com/Colter23/demo-plugin/releases/download/v1.0.0/demo-plugin-1.0.0-all.jar",
                      "size": 128,
                      "digest": "sha256:$sha256"
                    }
                  ]
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
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
