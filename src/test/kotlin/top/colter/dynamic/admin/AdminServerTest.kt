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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.colter.dynamic.MainConfigForms
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.PushTemplates
import top.colter.dynamic.WebAdminConfig
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.ConfigurablePlugin
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
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.plugin.PluginCapability
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PluginInfo
import top.colter.dynamic.core.plugin.PluginReloadResult
import top.colter.dynamic.core.plugin.PluginState
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import kotlin.io.path.createTempDirectory
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.reflect.KClass

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
        val oldTemplateButtonId = "openTemplate" + "Binding"
        assertTrue(!html.contains("id=\"$oldTemplateButtonId\""))
        assertTrue(html.contains("id=\"filterScopeSubscriptionId\""))
        assertTrue(html.contains("/subscriber-target-platforms"))
        assertTrue(html.contains("/subscriber-targets"))
        assertTrue(html.contains("id='actionSubscriberTargetId'"))
        assertTrue(!html.contains("id=\"editModal\""))
        assertTrue(!html.contains("id=\"entitySummary\""))
        assertTrue(!html.contains("renderEntitySummary"))
        assertTrue(html.contains("data-action='edit-publisher'"))
        assertTrue(html.contains("data-action='edit-subscriber'"))
        assertTrue(html.contains("data-action='delete-publisher'"))
        assertTrue(html.contains("data-action='delete-subscriber'"))
        assertTrue(html.contains("id='actionEntityHeaderUri'"))
        assertTrue(html.contains("data-nav=\"configs\""))
        assertTrue(html.contains("data-page=\"configs\""))
        assertTrue(html.contains("id=\"configForm\""))
        assertTrue(html.contains("id=\"saveConfig\""))
        assertTrue(html.contains("id=\"configRestartHint\""))
        assertTrue(html.contains("class=\"restart-note\""))
        assertTrue(html.contains("restart-mark"))
        assertTrue(html.contains("⚠️"))
        assertTrue(html.contains("id=\"configItemModal\""))
        assertTrue(!html.contains("data-action='open-template-modal'"))
        assertTrue(html.contains("data-action='open-permission-modal'"))
        assertTrue(!html.contains("data-action='edit-template-row'"))
        assertTrue(html.contains("data-action='edit-permission-row'"))
        assertTrue(html.contains("data-action='reload-plugin'"))
        assertTrue(html.contains("id=\"configReloadActions\""))
        assertTrue(html.contains("id=\"reloadConfigPlugin\""))
        assertTrue(html.contains("id=\"stopApplication\""))
        assertTrue(html.contains("/plugins/"))
        assertTrue(html.contains("/reload"))
        assertTrue(html.contains("/system/stop"))
        assertTrue(html.contains("/configs/"))
        assertTrue(!html.contains("id=\"createSubscription\""))
        assertTrue(html.contains("hashchange"))
        assertTrue(html.contains("localStorage.getItem(\"dynamicBotAdminToken\")"))
    }

    @Test
    fun `system stop endpoint should require token and invoke stop requester`() = testApplication {
        initDb("admin-system-stop")
        var stopReason: String? = null
        application {
            adminModule(
                testContext(FakePlatformPublisherPlugin()).copy(
                    stopRequester = { reason -> stopReason = reason },
                )
            )
        }
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/system/stop").status)

        val response = client.post("/api/system/stop") { auth() }
        val body = response.body<ActionResultResponse>()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.changed)
        assertEquals("application stop requested", body.message)
        assertEquals("web-admin", stopReason)
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
    fun `plugin reload endpoint should require token and return success`() = testApplication {
        initDb("admin-plugin-reload")
        val fakePlugin = FakeConfigurablePlugin()
        val current = MainDynamicConfig(webAdmin = WebAdminConfig(token = "secret-token"))
        application {
            adminModule(
                configTestContext(
                    currentConfig = { current },
                    plugins = listOf(fakePlugin.info()),
                    pluginReloader = { pluginId ->
                        assertEquals("fake-plugin", pluginId)
                        PluginReloadResult(
                            pluginId = pluginId,
                            success = true,
                            changed = true,
                            pluginState = PluginState.ACTIVE,
                            message = "plugin reloaded",
                        )
                    },
                )
            )
        }
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/plugins/fake-plugin/reload").status)

        val response = client.post("/api/plugins/fake-plugin/reload") { auth() }
        val body = response.body<PluginReloadResponse>()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, body.success)
        assertEquals(true, body.changed)
        assertEquals("fake-plugin", body.pluginId)
        assertEquals("ACTIVE", body.pluginState)
    }

    @Test
    fun `plugin reload endpoint should return not found and conflict failures`() = testApplication {
        initDb("admin-plugin-reload-failures")
        val fakePlugin = FakeConfigurablePlugin()
        val current = MainDynamicConfig(webAdmin = WebAdminConfig(token = "secret-token"))
        application {
            adminModule(
                configTestContext(
                    currentConfig = { current },
                    plugins = listOf(fakePlugin.info()),
                    pluginReloader = { pluginId ->
                        PluginReloadResult(
                            pluginId = pluginId,
                            success = false,
                            changed = true,
                            pluginState = PluginState.FAILED,
                            message = "plugin reload failed",
                            error = "boom",
                        )
                    },
                )
            )
        }
        val client = jsonClient()

        assertEquals(HttpStatusCode.NotFound, client.post("/api/plugins/missing/reload") { auth() }.status)

        val response = client.post("/api/plugins/fake-plugin/reload") { auth() }
        val body = response.body<PluginReloadResponse>()

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(false, body.success)
        assertEquals("fake-plugin", body.pluginId)
        assertEquals("FAILED", body.pluginState)
        assertEquals("boom", body.error)
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
    fun `subscriber target endpoints should require token and expose sink plugin targets`() = testApplication {
        initDb("admin-subscriber-targets")
        val plugin = FakeMessageSinkPlugin()
        application { adminModule(testContext(listOf(plugin.info()))) }
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/subscriber-target-platforms").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/subscriber-targets?platformId=onebot&type=GROUP").status)

        val platforms = client.get("/api/subscriber-target-platforms") { auth() }
            .body<List<SubscriberTargetPlatformDto>>()
        assertEquals(1, platforms.size)
        assertEquals("onebot", platforms.single().platformId)
        assertEquals("onebot-gateway", platforms.single().pluginId)
        assertEquals(listOf("GROUP", "USER"), platforms.single().supportedTypes)

        val groups = client.get("/api/subscriber-targets?platformId=onebot&type=GROUP") { auth() }
            .body<List<SubscriberTargetDto>>()
        assertEquals(1, groups.size)
        assertEquals("GROUP", groups.single().type)
        assertEquals("100", groups.single().targetId)
        assertEquals("测试群", groups.single().name)

        val allTargets = client.get("/api/subscriber-targets?platformId=onebot") { auth() }
            .body<List<SubscriberTargetDto>>()
        assertEquals(2, allTargets.size)
    }

    @Test
    fun `publisher endpoint should update header state and delete related subscriptions`() = testApplication {
        initDb("admin-publisher-entity")
        seedSubscription()
        val publisher = PublisherRepository.findByPlatformAndExternalId("bilibili", "123")!!
        application { adminModule(testContext(FakePlatformPublisherPlugin())) }
        val client = jsonClient()

        val updated = client.patch("/api/publishers/${publisher.id}") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(
                UpdatePublisherRequest(
                    headerUri = "https://example.com/header.png",
                    state = "DISABLED",
                )
            )
        }.body<PublisherDto>()

        assertEquals("DISABLED", updated.state)
        assertEquals("https://example.com/header.png", updated.headerUri)

        val deleted = client.delete("/api/publishers/${publisher.id}") { auth() }
            .body<ActionResultResponse>()

        assertTrue(deleted.changed)
        assertEquals(null, PublisherRepository.findById(publisher.id))
        assertEquals(0L, SubscriptionRepository.countAll())
    }

    @Test
    fun `subscriber endpoint should update state and delete related subscriptions`() = testApplication {
        initDb("admin-subscriber-entity")
        val subscriptionId = seedSubscription()
        val subscriberId = SubscriptionRepository.findById(subscriptionId)!!.subscriberId
        application { adminModule(testContext(FakePlatformPublisherPlugin())) }
        val client = jsonClient()

        val updated = client.patch("/api/subscribers/$subscriberId") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(UpdateSubscriberRequest(state = "DISABLED"))
        }.body<SubscriberDto>()

        assertEquals("DISABLED", updated.state)

        val deleted = client.delete("/api/subscribers/$subscriberId") { auth() }
            .body<ActionResultResponse>()

        assertTrue(deleted.changed)
        assertEquals(null, SubscriberRepository.findById(subscriberId))
        assertEquals(0L, SubscriptionRepository.countAll())
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
                    atAllTypes = listOf("DYNAMIC"),
                )
            )
        }.body<CreateSubscriptionResponse>()

        assertTrue(created.subscriptionCreated)
        assertTrue(created.autoFollowed)
        assertEquals(listOf("DYNAMIC"), created.subscription.atAllTypes)
        assertEquals(1, plugin.followCalls)
        assertNotNull(PublisherRepository.findByPlatformAndExternalId("bilibili", "123"))
        assertEquals(1L, SubscriptionRepository.countAll())

        val updated = client.patch("/api/subscriptions/${created.subscription.id}") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(UpdateSubscriptionRequest(atAllTypes = listOf("LIVE", "VIDEO")))
        }.body<SubscriptionDto>()

        assertEquals(listOf("LIVE", "VIDEO"), updated.atAllTypes)

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
    fun `template binding endpoints should be removed`() = testApplication {
        initDb("admin-templates")
        val plugin = FakePlatformPublisherPlugin()
        application { adminModule(testContext(plugin)) }
        val client = jsonClient()

        val oldTemplatesPath = "/api/" + "templates"
        val oldBindingPath = "/api/template-" + "bindings/publisher/1"
        assertEquals(HttpStatusCode.NotFound, client.get(oldTemplatesPath) { auth() }.status)
        assertEquals(HttpStatusCode.NotFound, client.put(oldBindingPath) {
            auth()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status)
    }

    @Test
    fun `config endpoints should require token and list main plus configurable plugins`() = testApplication {
        initDb("admin-config-list")
        val fakePlugin = FakeConfigurablePlugin()
        val current = MainDynamicConfig(webAdmin = WebAdminConfig(token = "secret-token"))
        application { adminModule(configTestContext(currentConfig = { current }, plugins = listOf(fakePlugin.info()))) }
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/configs").status)

        val configs = client.get("/api/configs") { auth() }.body<List<ConfigSummaryDto>>()

        assertTrue(configs.any { it.id == "main" })
        assertTrue(configs.any { it.id == "fake-config" })
    }

    @Test
    fun `main config endpoint should mask token save and use new token immediately`() = testApplication {
        initDb("admin-config-main")
        var current = MainDynamicConfig(webAdmin = WebAdminConfig(token = "secret-token"))
        application {
            adminModule(
                configTestContext(
                    currentConfig = { current },
                    updateMainConfig = { next ->
                        MainConfigForms.validate(next)
                        val previous = current
                        current = next
                        val targets = MainConfigForms.restartTargets(previous, next)
                        ConfigApplyResult(true, targets.isNotEmpty(), targets, "saved")
                    },
                )
            )
        }
        val client = jsonClient()

        val detail = client.get("/api/configs/main") { auth() }.body<ConfigDetailDto>()
        assertEquals("", detail.values["webAdmin.token"].toString().trim('"'))
        assertEquals(true, detail.secretStates["webAdmin.token"])
        assertTrue(detail.schema.fields.any { it.path == "templates.dynamic" && it.type == ConfigFieldType.TEXTAREA })
        assertTrue(detail.schema.fields.any { it.path == "templates.liveStarted" && it.type == ConfigFieldType.TEXTAREA })
        assertTrue(detail.schema.fields.any { it.path == "templates.liveEnded" && it.type == ConfigFieldType.TEXTAREA })

        val updated = client.put("/api/configs/main") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(UpdateConfigRequest(buildJsonObject {
                put("webAdmin.token", "new-token")
            }))
        }.body<UpdateConfigResponse>()

        assertTrue(updated.changed)
        assertEquals("new-token", current.webAdmin.token)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/overview") { auth("secret-token") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/overview") { auth("new-token") }.status)
    }

    @Test
    fun `main config endpoint should reject invalid port`() = testApplication {
        initDb("admin-config-invalid")
        var current = MainDynamicConfig(webAdmin = WebAdminConfig(token = "secret-token"))
        application {
            adminModule(
                configTestContext(
                    currentConfig = { current },
                    updateMainConfig = { next ->
                        MainConfigForms.validate(next)
                        current = next
                        ConfigApplyResult(true)
                    },
                )
            )
        }
        val client = jsonClient()

        val response = client.put("/api/configs/main") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(UpdateConfigRequest(buildJsonObject {
                put("webAdmin.port", 70_000)
            }))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `plugin config endpoint should mask and preserve secret fields`() = testApplication {
        initDb("admin-config-plugin")
        val fakePlugin = FakeConfigurablePlugin(FakePluginConfig(enabled = true, token = "old-secret"))
        val current = MainDynamicConfig(webAdmin = WebAdminConfig(token = "secret-token"))
        application {
            adminModule(
                configTestContext(
                    currentConfig = { current },
                    plugins = listOf(fakePlugin.info()),
                )
            )
        }
        val client = jsonClient()

        val detail = client.get("/api/configs/fake-config") { auth() }.body<ConfigDetailDto>()
        assertEquals("", detail.values["token"].toString().trim('"'))
        assertEquals(true, detail.secretStates["token"])

        val updated = client.put("/api/configs/fake-config") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(UpdateConfigRequest(buildJsonObject {
                put("enabled", false)
                put("token", "")
            }))
        }.body<UpdateConfigResponse>()

        assertTrue(updated.changed)
        assertEquals(false, fakePlugin.config.enabled)
        assertEquals("old-secret", fakePlugin.config.token)
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
                        templates = PushTemplates(
                            dynamic = "{content}",
                            liveStarted = "{name} 开播",
                            liveEnded = "{name} 下播",
                        )
                    )
                },
            ),
            loginService = AdminLoginService(
                platformPluginResolver = platformPluginResolver,
            ),
        )
    }

    private fun configTestContext(
        currentConfig: () -> MainDynamicConfig,
        updateMainConfig: (MainDynamicConfig) -> ConfigApplyResult = { ConfigApplyResult(false) },
        plugins: List<PluginInfo> = emptyList(),
        pluginReloader: (String) -> PluginReloadResult = { pluginId ->
            PluginReloadResult(
                pluginId = pluginId,
                success = false,
                changed = false,
                message = "plugin reload is not configured",
                error = "plugin reload is not configured",
            )
        },
    ): AdminServerContext {
        val configService = MemoryConfigService(createTempDirectory("dynamic-bot-config-api"))
        return AdminServerContext(
            token = currentConfig().webAdmin.token,
            tokenProvider = { currentConfig().webAdmin.token },
            service = AdminService(
                pluginProvider = { plugins },
                platformPluginResolver = { null },
                configProvider = currentConfig,
                mainConfigUpdater = updateMainConfig,
                configService = configService,
                pluginReloader = pluginReloader,
            ),
            loginService = AdminLoginService(
                platformPluginResolver = { null },
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

    private class MemoryConfigService(
        private val root: Path,
    ) : ConfigService {
        private val values: MutableMap<String, Any> = mutableMapOf()

        override fun <T : Any> loadOrCreate(pluginId: String, clazz: KClass<T>, defaultProvider: () -> T): T {
            return reloadOrPut(pluginId, defaultProvider)
        }

        override fun <T : Any> save(pluginId: String, config: T, clazz: KClass<T>) {
            values[pluginId] = config
        }

        override fun <T : Any> reload(pluginId: String, clazz: KClass<T>): T {
            @Suppress("UNCHECKED_CAST")
            return values[pluginId] as? T ?: throw NoSuchElementException(pluginId)
        }

        override fun exists(pluginId: String): Boolean = values.containsKey(pluginId)

        override fun resolvePath(pluginId: String): Path = root.resolve("$pluginId.yml")

        private fun <T : Any> reloadOrPut(pluginId: String, defaultProvider: () -> T): T {
            @Suppress("UNCHECKED_CAST")
            val existing = values[pluginId] as? T
            if (existing != null) return existing
            val created = defaultProvider()
            values[pluginId] = created
            return created
        }
    }

    private data class FakePluginConfig(
        val enabled: Boolean = true,
        val token: String = "",
    )

    private class FakeConfigurablePlugin(
        initialConfig: FakePluginConfig = FakePluginConfig(),
    ) : Plugin, ConfigurablePlugin<FakePluginConfig> {
        var config: FakePluginConfig = initialConfig
            private set

        override val configId: String = "fake-config"
        override val configName: String = "Fake Config"
        override val configDescription: String = "Fake configurable plugin"
        override val configClass: KClass<FakePluginConfig> = FakePluginConfig::class
        override val configFormSpec: ConfigFormSpec = ConfigFormSpec(
            title = "Fake Config",
            fields = listOf(
                ConfigFieldSpec("enabled", "Enabled", ConfigFieldType.BOOLEAN),
                ConfigFieldSpec("token", "Token", ConfigFieldType.SECRET, secret = true),
            ),
        )

        override fun currentConfig(): FakePluginConfig = config

        override fun applyConfig(next: FakePluginConfig): ConfigApplyResult {
            val changed = next != config
            config = next
            return ConfigApplyResult(changed, message = "fake saved")
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
                id = "fake-plugin",
                name = "Fake Plugin",
                version = "0.0.1",
                mainClass = "FakeConfigurablePlugin",
                capabilities = emptySet(),
                apiVersion = "2.0.0",
            ),
            state = PluginState.ACTIVE,
            instance = this,
            classLoader = null,
            sourceJarPath = "fake-config.jar",
            loadTime = 1L,
        )
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

    private class FakeMessageSinkPlugin : MessageSinkPlugin {
        override val targetPlatformId: String = "onebot"
        override val supportedTargetTypes: Set<SubscriberType> = setOf(SubscriberType.GROUP, SubscriberType.USER)

        private val targets = listOf(
            MessageTargetCandidate(
                platformId = targetPlatformId,
                type = SubscriberType.GROUP,
                targetId = "100",
                name = "测试群",
            ),
            MessageTargetCandidate(
                platformId = targetPlatformId,
                type = SubscriberType.USER,
                targetId = "200",
                name = "测试用户",
            ),
        )

        override suspend fun listMessageTargets(type: SubscriberType?): List<MessageTargetCandidate> {
            return targets.filter { target -> type == null || target.type == type }
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
                id = "onebot-gateway",
                name = "OneBot Gateway",
                version = "0.0.1",
                mainClass = "FakeMessageSinkPlugin",
                capabilities = setOf(PluginCapability.MESSAGE_SINK),
                apiVersion = "2.0.0",
            ),
            state = PluginState.ACTIVE,
            instance = this,
            classLoader = null,
            sourceJarPath = "onebot.jar",
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
