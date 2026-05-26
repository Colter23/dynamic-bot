package top.colter.dynamic.admin

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherProfile
import top.colter.dynamic.core.data.PublisherType
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.plugin.PluginCapability
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PluginInfo
import top.colter.dynamic.core.plugin.PluginState
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.PublisherTemplateRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminServerTest {
    @AfterTest
    fun cleanup() {
        CommandRegistry.clear()
    }

    @Test
    fun `api should require bearer token and return overview`() = testApplication {
        initDb("admin-auth")
        val plugin = FakePlatformPublisherPlugin()
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/overview").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/overview") { auth("wrong") }.status)

        val response = client.get("/api/overview") { auth() }
        val overview = response.body<OverviewResponse>()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, overview.plugins.size)
        assertEquals("bilibili-publisher", overview.plugins.single().id)
    }

    @Test
    fun `admin page should serve resource backed login shell`() = testApplication {
        initDb("admin-static")
        val plugin = FakePlatformPublisherPlugin()
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        val response = client.get("/admin")
        val html = response.body<String>()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(html.contains("id=\"loginView\""))
        assertTrue(html.contains("id=\"appView\""))
        assertTrue(html.contains("data-nav=\"overview\""))
        assertTrue(html.contains("data-nav=\"login\""))
        assertTrue(!html.contains("data-nav=\"login\" hidden"))
        assertTrue(html.contains("data-page=\"subscriptions\""))
        assertTrue(html.contains("id=\"platformLoginList\""))
        assertTrue(html.contains("id=\"platformLoginModal\""))
        assertTrue(html.contains("id=\"actionModal\""))
        assertTrue(html.contains("id=\"toastStack\""))
        assertTrue(html.contains("id=\"openCreateSubscription\""))
        assertTrue(html.contains("id=\"openAddFilter\""))
        assertTrue(html.contains("id=\"openTemplateBinding\""))
        assertTrue(html.contains("id=\"filterScopeSubscriptionId\""))
        assertTrue(!html.contains("id=\"createSubscription\""))
        assertTrue(html.contains("hashchange"))
        assertTrue(html.contains("localStorage.getItem(\"dynamicBotAdminToken\")"))
    }

    @Test
    fun `plugins endpoint should expose plugin state`() = testApplication {
        initDb("admin-plugins")
        val plugin = FakePlatformPublisherPlugin()
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        val plugins = client.get("/api/plugins") { auth() }.body<List<PluginDto>>()

        assertEquals(1, plugins.size)
        assertEquals("ACTIVE", plugins.single().state)
        assertTrue(plugins.single().capabilities.contains("DYNAMIC_SOURCE"))
    }

    @Test
    fun `platform login endpoint should require token and expose active platform login state`() = testApplication {
        initDb("admin-platform-logins")
        val plugin = FakePlatformPublisherPlugin(
            loginStateResult = PublisherLoginResult(
                PublisherLoginStatus.SUCCESS,
                "logged in",
                PublisherLoginAccount(userId = "123", name = "demo-up"),
            ),
        )
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/platform-logins").status)

        val logins = client.get("/api/platform-logins") { auth() }.body<List<PlatformLoginDto>>()

        assertEquals(1, logins.size)
        assertEquals("bilibili", logins.single().platformId)
        assertEquals("bilibili-publisher", logins.single().pluginId)
        assertEquals(listOf("COOKIE", "QR_CODE"), logins.single().supportedLoginMethods)
        assertEquals("SUCCESS", logins.single().status)
        assertEquals("demo-up", logins.single().account?.name)
    }

    @Test
    fun `platform login endpoint should return empty list without platform publisher plugins`() = testApplication {
        initDb("admin-platform-logins-empty")
        application { adminModule(testContext(emptyList())) }
        val client = jsonClient()

        val logins = client.get("/api/platform-logins") { auth() }.body<List<PlatformLoginDto>>()

        assertTrue(logins.isEmpty())
    }

    @Test
    fun `platform login endpoint should include unsupported login platforms as readonly entries`() = testApplication {
        initDb("admin-platform-logins-unsupported")
        val plugin = ReadOnlyPlatformPublisherPlugin()
        application { adminModule(testContext(listOf(plugin.info()), platformPluginResolver = { platform ->
            if (platform == plugin.platformId) plugin else null
        })) }
        val client = jsonClient()

        val logins = client.get("/api/platform-logins") { auth() }.body<List<PlatformLoginDto>>()

        assertEquals(1, logins.size)
        assertEquals("readonly", logins.single().platformId)
        assertTrue(logins.single().supportedLoginMethods.isEmpty())
        assertEquals("UNSUPPORTED", logins.single().status)
    }

    @Test
    fun `subscription endpoints should create and delete subscription`() = testApplication {
        initDb("admin-subscription")
        val plugin = FakePlatformPublisherPlugin(followState = FollowState.NOT_FOLLOWING)
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        val created = client.post("/api/subscriptions") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(
                CreateSubscriptionRequest(
                    subscriberPlatform = "onebot",
                    subscriberType = "GROUP",
                    subscriberTargetId = "100",
                    subscriberName = "group-100",
                    publisherPlatform = "bilibili",
                    publisherExternalId = "123",
                )
            )
        }.body<CreateSubscriptionResponse>()

        assertTrue(created.subscriptionCreated)
        assertTrue(created.autoFollowed)
        assertEquals(1, plugin.followCalls)
        assertNotNull(PublisherRepository.findByPlatformAndExternalId("bilibili", "123"))
        assertEquals(1L, SubscriptionRepository.countAll())

        val deleted = client.delete("/api/subscriptions/${created.subscription.id}") { auth() }
            .body<ActionResultResponse>()

        assertTrue(deleted.changed)
        assertEquals(0L, SubscriptionRepository.countAll())
    }

    @Test
    fun `filter rule endpoints should add list delete and clear rules`() = testApplication {
        initDb("admin-filters")
        val subscriptionId = seedSubscription()
        val plugin = FakePlatformPublisherPlugin()
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        val created = client.post("/api/filter-rules") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(
                CreateFilterRuleRequest(
                    subscriptionId = subscriptionId,
                    ruleType = "CONTENT",
                    matcher = "KEYWORD",
                    value = "spoiler",
                )
            )
        }.body<DynamicFilterRuleDto>()

        assertEquals("spoiler", created.value)
        val listed = client.get("/api/filter-rules?subscriptionId=$subscriptionId") { auth() }
            .body<List<DynamicFilterRuleDto>>()
        assertEquals(1, listed.size)

        val deleted = client.delete("/api/filter-rules/${created.id}") { auth() }
            .body<ActionResultResponse>()
        assertTrue(deleted.changed)
        assertTrue(DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId).isEmpty())

        DynamicFilterRuleRepository.addElementRule(subscriptionId, top.colter.dynamic.core.data.DynamicElementType.IMAGE)
        val cleared = client.delete("/api/subscriptions/$subscriptionId/filter-rules") { auth() }
            .body<ActionResultResponse>()
        assertTrue(cleared.changed)
        assertTrue(DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId).isEmpty())
    }

    @Test
    fun `template endpoints should list bind and remove publisher template`() = testApplication {
        initDb("admin-templates")
        val publisher = seedPublisher()
        val plugin = FakePlatformPublisherPlugin()
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        val templates = client.get("/api/templates") { auth() }.body<TemplatesResponse>()
        assertTrue(templates.templates.any { it.name == "rich" })

        val bound = client.put("/api/template-bindings/publisher/${publisher.id}") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(TemplateBindingRequest("rich"))
        }.body<ActionResultResponse>()

        assertTrue(bound.changed)
        assertEquals("rich", PublisherTemplateRepository.findTemplateName(publisher.id, "bilibili", "text"))

        val removed = client.delete("/api/template-bindings/publisher/${publisher.id}") { auth() }
            .body<ActionResultResponse>()
        assertTrue(removed.changed)
    }

    @Test
    fun `login endpoints should return cookie failure and qr success`() = testApplication {
        initDb("admin-login")
        val plugin = FakePlatformPublisherPlugin(
            cookieLoginResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "bad cookie"),
            qrLoginResult = PublisherLoginResult(
                PublisherLoginStatus.SUCCESS,
                "login success",
                PublisherLoginAccount(userId = "123", name = "demo-up"),
            ),
        )
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        val cookie = client.post("/api/platforms/bilibili/login/cookie") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(CookieLoginRequest("bad"))
        }.body<LoginResultDto>()
        assertEquals("FAILED", cookie.status)

        val started = client.post("/api/platforms/bilibili/login/qr") { auth() }
            .body<QrLoginStartResponse>()
        val image = client.get(started.imageUrl) { auth() }
        assertEquals(HttpStatusCode.OK, image.status)

        val status = client.get("/api/login/qr/${started.loginId}") { auth() }
            .body<QrLoginStatusResponse>()
        assertEquals("SUCCESS", status.status)
        assertEquals("demo-up", status.account?.name)
    }

    @Test
    fun `qr login cancel endpoint should cancel pending session`() = testApplication {
        initDb("admin-login-cancel")
        val qrWait = CompletableDeferred<Unit>()
        val plugin = FakePlatformPublisherPlugin(qrLoginWait = qrWait)
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        val started = client.post("/api/platforms/bilibili/login/qr") { auth() }
            .body<QrLoginStartResponse>()
        val canceled = client.delete("/api/login/qr/${started.loginId}") { auth() }
            .body<ActionResultResponse>()
        val status = client.get("/api/login/qr/${started.loginId}") { auth() }
            .body<QrLoginStatusResponse>()

        assertTrue(canceled.changed)
        assertEquals("CANCELED", status.status)
        assertTrue(plugin.qrLoginCancelled)
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String = "secret-token") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun testContext(plugin: FakePlatformPublisherPlugin): AdminServerContext {
        return testContext(
            pluginInfos = listOf(plugin.info()),
            platformPluginResolver = { platform -> if (platform == plugin.platformId) plugin else null },
        )
    }

    private fun testContext(
        pluginInfos: List<PluginInfo>,
        platformPluginResolver: (String) -> PlatformPublisherPlugin? = { null },
    ): AdminServerContext {
        return AdminServerContext(
            token = "secret-token",
            service = AdminService(
                pluginProvider = { pluginInfos },
                platformPluginResolver = platformPluginResolver,
                configProvider = {
                    MainDynamicConfig(
                        templates = mapOf(
                            "default" to "{content}",
                            "rich" to "{name}: {content}",
                        )
                    )
                },
            ),
            loginService = AdminLoginService(
                platformPluginResolver = platformPluginResolver,
            ),
        )
    }

    private fun seedSubscription(): Int {
        val publisher = seedPublisher()
        val subscriber = SubscriberRepository.upsert(
            platformId = "onebot",
            targetId = "100",
            name = "group-100",
            type = SubscriberType.GROUP,
        ).value
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        return SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)!!.id
    }

    private fun seedPublisher(): Publisher {
        PublisherRepository.create(
            Publisher(
                id = 1,
                platformId = "bilibili",
                type = PublisherType.USER,
                externalId = "123",
                name = "demo-up",
                state = EntityState.ACTIVE,
                face = LazyImage("https://example.com/face.png"),
                createTime = 1L,
                createUser = 1,
            )
        )
        return PublisherRepository.findByPlatformAndExternalId("bilibili", "123")!!
    }

    private fun initDb(prefix: String) {
        val tempDir = createTempDirectory("dynamic-bot-$prefix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private class FakePlatformPublisherPlugin(
        private val followState: FollowState = FollowState.FOLLOWING,
        private val loginStateResult: PublisherLoginResult = PublisherLoginResult(
            PublisherLoginStatus.SUCCESS,
            "logged in",
            PublisherLoginAccount(userId = "123", name = "demo-up"),
        ),
        private val loginMethods: Set<PublisherLoginMethod> = setOf(
            PublisherLoginMethod.COOKIE,
            PublisherLoginMethod.QR_CODE,
        ),
        private val cookieLoginResult: PublisherLoginResult = PublisherLoginResult(
            PublisherLoginStatus.SUCCESS,
            "cookie ok",
        ),
        private val qrLoginResult: PublisherLoginResult = PublisherLoginResult(
            PublisherLoginStatus.SUCCESS,
            "qr ok",
        ),
        private val qrLoginWait: CompletableDeferred<Unit>? = null,
    ) : PlatformPublisherPlugin {
        override val platformId: String = "bilibili"
        override val supportedLoginMethods: Set<PublisherLoginMethod> = loginMethods

        var followCalls: Int = 0
        var qrLoginCancelled: Boolean = false
            private set

        override suspend fun fetchPublisherProfile(userId: String): PublisherProfile? {
            if (userId != "123") return null
            return PublisherProfile(
                platformId = platformId,
                externalId = userId,
                type = PublisherType.USER,
                name = "demo-up",
                state = EntityState.ACTIVE,
                face = LazyImage("https://example.com/face.png"),
            )
        }

        override suspend fun queryFollowState(userId: String): FollowState = followState

        override suspend fun followPublisher(userId: String): FollowActionResult {
            followCalls += 1
            return FollowActionResult(FollowActionStatus.FOLLOWED)
        }

        override suspend fun checkLoginState(): PublisherLoginResult = loginStateResult

        override suspend fun loginByCookie(cookie: String): PublisherLoginResult = cookieLoginResult

        override suspend fun loginByQrCode(
            onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
            onStatusChanged: suspend (PublisherLoginResult) -> Unit,
        ): PublisherLoginResult {
            onQrCode(
                PublisherQrLoginChallenge(
                    qrContent = "https://example.com/qr",
                    expiresAtEpochSeconds = 1710000000,
                    message = "scan",
                )
            )
            onStatusChanged(PublisherLoginResult(PublisherLoginStatus.PENDING, "waiting"))
            if (qrLoginWait != null) {
                try {
                    qrLoginWait.await()
                } catch (e: CancellationException) {
                    qrLoginCancelled = true
                    throw e
                }
            }
            return qrLoginResult
        }

        override fun init() {
        }

        override fun start() {
        }

        override fun stop() {
        }

        override fun cleanup() {
        }

        fun info(): PluginInfo = PluginInfo(
            descriptor = PluginDescriptor(
                id = "bilibili-publisher",
                name = "Bilibili",
                version = "0.0.1",
                mainClass = "FakePlatformPublisherPlugin",
                capabilities = setOf(PluginCapability.DYNAMIC_SOURCE, PluginCapability.LINK_RESOLVER),
                apiVersion = "2.0.0",
            ),
            state = PluginState.ACTIVE,
            instance = this,
            classLoader = null,
            sourceJarPath = "fake.jar",
            loadTime = 1L,
        )
    }

    private class ReadOnlyPlatformPublisherPlugin : PlatformPublisherPlugin {
        override val platformId: String = "readonly"

        override suspend fun fetchPublisherProfile(userId: String): PublisherProfile? = null

        override suspend fun queryFollowState(userId: String): FollowState = FollowState.UNSUPPORTED

        override suspend fun followPublisher(userId: String): FollowActionResult {
            return FollowActionResult(FollowActionStatus.UNSUPPORTED)
        }

        override fun init() {
        }

        override fun start() {
        }

        override fun stop() {
        }

        override fun cleanup() {
        }

        fun info(): PluginInfo = PluginInfo(
            descriptor = PluginDescriptor(
                id = "readonly-publisher",
                name = "Readonly",
                version = "0.0.1",
                mainClass = "ReadOnlyPlatformPublisherPlugin",
                capabilities = setOf(PluginCapability.DYNAMIC_SOURCE),
                apiVersion = "2.0.0",
            ),
            state = PluginState.ACTIVE,
            instance = this,
            classLoader = null,
            sourceJarPath = "readonly.jar",
            loadTime = 1L,
        )
    }
}
