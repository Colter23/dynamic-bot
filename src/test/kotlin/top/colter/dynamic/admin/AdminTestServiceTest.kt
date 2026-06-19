package top.colter.dynamic.admin

import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.colter.dynamic.LinkParseTemplates
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.PushTemplates
import top.colter.dynamic.core.data.DynamicBlockRole
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.draw.DynamicDrawService
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey

class AdminTestServiceTest {
    @Test
    fun presetsShouldIncludeRecommendedCombinationsAndBoundaryCases() {
        val service = AdminTestService()

        val response = service.presets()

        assertEquals("combo-daily-rich", response.defaultPresetId)
        assertTrue(response.presets.count { it.recommended } >= 5)
        assertTrue(response.presets.any { it.id == "combo-repost-rich" && "转发" in it.tags })
        assertTrue(response.presets.any { it.id == "boundary-nine-grid" && it.group == "边界场景" })
        assertTrue(response.presets.any { it.id == "combo-live-start-rich" && it.eventType == "LIVE_STARTED" })
    }

    @Test
    fun mockPreviewShouldRenderPushTemplateAndDrawImage() = runBlocking {
        val drawService = FakeDrawService()
        val service = AdminTestService(
            configProvider = {
                MainDynamicConfig(
                    templates = PushTemplates(dynamic = "{draw}\n{name}\n{link}"),
                )
            },
            drawService = drawService,
            storedPublisherResolver = { null },
        )

        val response = service.preview(AdminTestPreviewRequest(mode = "MOCK", mockEventType = "DYNAMIC"))

        assertEquals("OK", response.status)
        assertEquals("MOCK_SOURCE_UPDATE", response.resolutionType)
        assertEquals("PUSH_TEMPLATE_DYNAMIC", response.templateSource)
        assertEquals("D:/tmp/admin-test-draw.png", response.drawImage?.uri)
        assertEquals(1, drawService.renderCalls)
        assertTrue(response.media.any { it.uri == "D:/tmp/admin-test-draw.png" })
        val image = response.batches.single().content.first() as MessageContent.Image
        assertEquals("D:/tmp/admin-test-draw.png", image.image.uri)
        assertTrue(response.batches.single().content.last().fallbackText.contains("日常测试 UP"))
    }

    @Test
    fun mockDefaultsShouldUseStableUidAdditionalCardAndAvoidImageVideoMix() = runBlocking {
        val service = AdminTestService(
            drawService = FakeDrawService(),
            storedPublisherResolver = { null },
        )

        val imageResponse = service.preview(
            AdminTestPreviewRequest(
                mode = "MOCK",
                presetId = "combo-daily-rich",
                template = "{content}",
            )
        )
        val imagePayload = assertIs<DynamicPayload>(imageResponse.update?.payload)
        val imageGrid = assertIs<ImageGridBlock>(imagePayload.blocks.single { it is ImageGridBlock })
        val additional = assertIs<MediaCardBlock>(
            imagePayload.blocks.single { it is MediaCardBlock && it.role == DynamicBlockRole.ADDITIONAL }
        )

        assertEquals("000000000", imageResponse.update?.publisher?.externalId)
        assertTrue(imageGrid.images.all { it.image.uri.startsWith("data/mock-assets/") })
        assertEquals(DynamicBlockRole.ADDITIONAL, additional.role)
        assertTrue(imagePayload.blocks.none {
            it is MediaCardBlock && it.role == DynamicBlockRole.BODY && it.card.kind == DynamicMediaCardKind.VIDEO
        })

        val videoResponse = service.preview(
            AdminTestPreviewRequest(
                mode = "MOCK",
                presetId = "combo-card-links",
                template = "{content}",
            )
        )
        val videoPayload = assertIs<DynamicPayload>(videoResponse.update?.payload)
        assertTrue(videoPayload.blocks.none { it is ImageGridBlock })
        assertTrue(videoPayload.blocks.any {
            it is MediaCardBlock && it.role == DynamicBlockRole.BODY && it.card.kind == DynamicMediaCardKind.VIDEO
        })
    }

    @Test
    fun mockPresetShouldUseStableUpdateIdSoDrawImageCanBeOverwritten() = runBlocking {
        val drawService = StablePathDrawService()
        val service = AdminTestService(
            configProvider = {
                MainDynamicConfig(
                    templates = PushTemplates(dynamic = "{draw}"),
                )
            },
            drawService = drawService,
            storedPublisherResolver = { null },
            nowEpochSeconds = sequenceOf(1_000L, 2_000L).iterator()::next,
        )

        val first = service.preview(AdminTestPreviewRequest(mode = "MOCK", presetId = "combo-daily-rich"))
        val second = service.preview(AdminTestPreviewRequest(mode = "MOCK", presetId = "combo-daily-rich"))

        assertEquals("combo-daily-rich", first.update?.key?.externalId)
        assertEquals(first.update?.key?.externalId, second.update?.key?.externalId)
        assertEquals(first.drawImage?.uri, second.drawImage?.uri)
        assertEquals("data/rendered/bilibili/000000000/combo-daily-rich.webp", second.drawImage?.uri)
    }

    @Test
    fun mockPresetOptionsShouldOverrideCombinationScenario() = runBlocking {
        val service = AdminTestService(
            configProvider = {
                MainDynamicConfig(
                    templates = PushTemplates(dynamic = "{name}:{content}"),
                )
            },
            drawService = FakeDrawService(),
            storedPublisherResolver = { null },
        )

        val response = service.preview(
            AdminTestPreviewRequest(
                mode = "MOCK",
                presetId = "combo-long-grid-video",
                presetOptions = AdminTestPresetOptions(
                    textVariant = "SHORT",
                    imageCount = 4,
                    imageRatio = "SQUARE",
                    includeVideoCard = false,
                ),
            )
        )

        val payload = assertIs<DynamicPayload>(response.update?.payload)
        val grid = assertIs<ImageGridBlock>(payload.blocks.single { it is ImageGridBlock })
        assertEquals(4, grid.images.size)
        assertEquals(1080, grid.images.first().width)
        assertTrue(payload.blocks.none { it is top.colter.dynamic.core.data.MediaCardBlock })
        assertTrue(response.batches.single().content.single().fallbackText.contains("好。"))
    }

    @Test
    fun customJsonUpdateShouldRenderDirectly() = runBlocking {
        val customUpdate = testDynamicUpdate(
            publisher = testPublisherInfo(name = "Custom UP"),
            externalId = "custom-1",
        )
        val service = AdminTestService(
            configProvider = {
                MainDynamicConfig(
                    templates = PushTemplates(dynamic = "{name}:{content}"),
                )
            },
            drawService = FakeDrawService(),
            storedPublisherResolver = { null },
        )

        val response = service.preview(
            AdminTestPreviewRequest(
                mode = "MOCK",
                customUpdate = customUpdate,
            )
        )

        assertEquals("CUSTOM_SOURCE_UPDATE", response.resolutionType)
        assertEquals("Custom UP:hello", response.batches.single().content.single().fallbackText)
    }

    @Test
    fun repostCombinationPresetShouldIncludeEmbeddedSourceUpdate() = runBlocking {
        val service = AdminTestService(
            drawService = FakeDrawService(),
            storedPublisherResolver = { null },
        )

        val response = service.preview(
            AdminTestPreviewRequest(
                mode = "MOCK",
                presetId = "combo-repost-rich",
                template = "{content}",
            )
        )

        val payload = assertIs<DynamicPayload>(response.update?.payload)
        val repost = assertIs<RepostBlock>(payload.blocks.single { it is RepostBlock })
        assertNotNull(repost.embedded)
    }

    @Test
    fun realLinkPreviewShouldUseMatchingResolverWithoutDispatchingMessages() = runBlocking {
        val resolver = FakeLinkResolver()
        val service = AdminTestService(
            resolversProvider = { listOf(resolver) },
            configProvider = {
                MainDynamicConfig(
                    templates = PushTemplates(dynamic = "{name}:{content}"),
                )
            },
            drawService = FakeDrawService(),
            storedPublisherResolver = { null },
        )

        val response = service.preview(
            AdminTestPreviewRequest(
                mode = "REAL_LINK",
                link = "https://example.com/dynamic/1",
            )
        )

        assertEquals("OK", response.status)
        assertEquals("DYNAMIC", response.resolutionType)
        assertEquals("1", response.parsedLink?.targetId)
        assertEquals(1, resolver.parseCalls)
        assertEquals(1, resolver.resolveCalls)
        assertTrue(response.batches.single().content.single().fallbackText.contains("Resolver UP:resolver content"))
    }

    @Test
    fun realLinkPreviewShouldEnrichDynamicPublisherBeforeRenderingTemplate() = runBlocking {
        val publisherKey = testPublisherKey(platformId = "bilibili", externalId = "42")
        val lookup = FakePublisherLookupPlugin(
            testPublisherInfo(
                key = publisherKey,
                name = "远程 UP",
                avatar = MediaRef("https://example.com/remote-avatar.png", MediaKind.AVATAR),
                banner = MediaRef("https://example.com/remote-banner.png", MediaKind.COVER),
            ),
        )
        val service = AdminTestService(
            resolversProvider = {
                listOf(
                    FakeLinkResolver(
                        publisher = testPublisherInfo(
                            key = publisherKey,
                            name = "",
                            avatar = MediaRef("", MediaKind.AVATAR),
                        ),
                    )
                )
            },
            configProvider = {
                MainDynamicConfig(
                    templates = PushTemplates(dynamic = "{name}"),
                )
            },
            drawService = FakeDrawService(),
            storedPublisherResolver = { null },
            publisherLookupResolver = { lookup },
        )

        val response = service.preview(
            AdminTestPreviewRequest(
                mode = "REAL_LINK",
                link = "https://example.com/dynamic/1",
            )
        )

        assertEquals("OK", response.status)
        assertEquals(1, lookup.fetchPublisherInfoCalls)
        assertEquals("远程 UP", response.update?.publisher?.name)
        assertEquals("https://example.com/remote-banner.png", response.update?.publisher?.banner?.uri)
        assertEquals("远程 UP", response.batches.single().content.single().fallbackText)
    }

    @Test
    fun realLinkPreviewShouldEnrichPreviewPublisherBeforeRenderingTemplate() = runBlocking {
        val publisherKey = testPublisherKey(platformId = "bilibili", externalId = "42")
        val lookup = FakePublisherLookupPlugin(
            testPublisherInfo(
                key = publisherKey,
                name = "远程 UP",
                avatar = MediaRef("https://example.com/remote-avatar.png", MediaKind.AVATAR),
                banner = MediaRef("https://example.com/remote-banner.png", MediaKind.COVER),
            ),
        )
        val service = AdminTestService(
            resolversProvider = {
                listOf(
                    FakePreviewLinkResolver(
                        kind = LinkKinds.USER,
                        cover = null,
                        publisher = testPublisherInfo(
                            key = publisherKey,
                            name = "Preview UP",
                            avatar = MediaRef("https://example.com/preview-avatar.png", MediaKind.AVATAR),
                            banner = null,
                        ),
                    )
                )
            },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{name}\\r{cover}"),
                    ),
                )
            },
            drawService = FakeDrawService(),
            storedPublisherResolver = {
                testPublisher(
                    key = publisherKey,
                    name = "本地 UP",
                    avatar = MediaRef("https://example.com/local-avatar.png", MediaKind.AVATAR),
                    banner = MediaRef("https://example.com/local-banner.png", MediaKind.COVER),
                )
            },
            publisherLookupResolver = { lookup },
        )

        val response = service.preview(
            AdminTestPreviewRequest(
                mode = "REAL_LINK",
                link = "https://example.com/video/1",
            )
        )

        assertEquals("OK", response.status)
        assertEquals(0, lookup.fetchPublisherInfoCalls)
        assertEquals("Preview UP", response.preview?.publisher?.name)
        assertEquals("https://example.com/local-banner.png", response.preview?.publisher?.banner?.uri)
        assertEquals("https://example.com/local-banner.png", response.preview?.cover?.uri)
        val image = response.batches
            .flatMap { it.content }
            .filterIsInstance<MessageContent.Image>()
            .single()
        assertEquals("https://example.com/local-banner.png", image.image.uri)
    }

    @Test
    fun realLinkPreviewShouldRenderDrawThroughPreviewTemplate() = runBlocking {
        val resolver = FakePreviewLinkResolver()
        val baseDrawService = FakeDrawService("D:/tmp/admin-test-base-draw.png")
        val themedDrawService = FakeDrawService("D:/tmp/admin-test-themed-draw.png")
        val service = AdminTestService(
            resolversProvider = { listOf(resolver) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}\n{stats}"),
                    ),
                )
            },
            drawService = baseDrawService,
            drawServiceFactory = { themedDrawService },
            storedPublisherResolver = { null },
        )

        val response = service.preview(
            AdminTestPreviewRequest(
                mode = "REAL_LINK",
                link = "https://example.com/video/1",
                presetOptions = AdminTestPresetOptions(themeColors = "#111111;#eeeeee"),
            )
        )

        assertEquals("OK", response.status)
        assertEquals("PREVIEW", response.resolutionType)
        assertEquals("LINK_PARSING_TEMPLATE", response.templateSource)
        assertEquals("D:/tmp/admin-test-themed-draw.png", response.drawImage?.uri)
        assertEquals(0, baseDrawService.renderCalls)
        assertEquals(1, themedDrawService.renderCalls)
        assertTrue(response.media.any { it.uri == "D:/tmp/admin-test-themed-draw.png" })
        assertEquals(
            listOf("play", "danmaku", "like", "coin", "favorite", "comment", "share"),
            response.preview?.metrics?.map { it.key },
        )
        val image = response.batches.single().content.first() as MessageContent.Image
        assertEquals("D:/tmp/admin-test-themed-draw.png", image.image.uri)
        val text = response.batches.single().content.filterIsInstance<MessageContent.Text>().single()
        assertEquals(
            "12.3万播放 / 234弹幕 / 56点赞 / 9投币 / 8收藏 / 7评论 / 10转发",
            text.fallbackText.trim(),
        )

        val drawPayload = assertIs<DynamicPayload>(themedDrawService.renderedUpdates.single().payload)
        val card = assertIs<MediaCardBlock>(drawPayload.blocks.single { it is MediaCardBlock }).card
        assertEquals("12.3万播放 / 234弹幕 / 56点赞", card.info)
        assertEquals(7, card.metrics.size)
    }

    @Test
    fun realLinkPreviewShouldWarnWhenTemplateUsesUnavailablePreviewFields() = runBlocking {
        val service = AdminTestService(
            resolversProvider = { listOf(FakePreviewLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(
                            message = "标题：{title}\\n大小：{size}\\n未知：{not_exists}\\r{video}",
                        ),
                    ),
                )
            },
            drawService = FakeDrawService(),
            storedPublisherResolver = { null },
        )

        val response = service.preview(
            AdminTestPreviewRequest(
                mode = "REAL_LINK",
                link = "https://example.com/video/1",
            )
        )

        assertEquals("WARN", response.status)
        assertEquals("标题：Preview video", (response.batches.single().content.single() as MessageContent.Text).fallbackText)
        assertTrue(response.warnings.any { "{not_exists}" in it && "{size}" in it })
        assertTrue(response.warnings.any { "不会下载视频" in it })
    }

    @Test
    fun apiShouldServeAdminTestPreviewBehindAuthorization() = testApplication {
        application {
            adminModule(testContext())
        }

        val unauthorized = client.post("/api/test/preview") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"MOCK"}""")
        }
        val authorized = client.post("/api/test/preview") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"MOCK","template":"{name}"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals(HttpStatusCode.OK, authorized.status)
        val body = authorized.bodyAsText()
        assertTrue(body.contains("\"resolutionType\":\"MOCK_SOURCE_UPDATE\""))
        assertTrue(body.contains("日常测试 UP"))
    }

    @Test
    fun apiShouldServeAdminTestPresetsBehindAuthorization() = testApplication {
        application {
            adminModule(testContext())
        }

        val unauthorized = client.get("/api/test/presets")
        val authorized = client.get("/api/test/presets") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals(HttpStatusCode.OK, authorized.status)
        val body = authorized.bodyAsText()
        assertTrue(body.contains("\"defaultPresetId\":\"combo-daily-rich\""))
        assertTrue(body.contains("\"id\":\"combo-repost-rich\""))
    }

    private fun testContext(): AdminServerContext {
        return AdminServerContext(
            token = "test-token",
            service = AdminService(
                pluginProvider = { emptyList() },
                publisherLookupResolver = { null },
                publisherFollowResolver = { null },
                configProvider = { MainDynamicConfig() },
            ),
            loginService = AdminLoginService(loginProviderResolver = { null }),
            testService = AdminTestService(
                drawService = FakeDrawService(),
                storedPublisherResolver = { null },
            ),
        )
    }

    private class FakePublisherLookupPlugin(
        private val info: PublisherInfo,
    ) : PublisherLookupPlugin {
        override val platformId: PlatformId = info.platformId
        var fetchPublisherInfoCalls: Int = 0
            private set

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
            fetchPublisherInfoCalls += 1
            return info.takeIf { it.externalId == userId }
        }
    }

    private class FakeDrawService(
        private val uri: String = "D:/tmp/admin-test-draw.png",
    ) : DynamicDrawService {
        var renderCalls: Int = 0
        val renderedUpdates: MutableList<SourceUpdate> = mutableListOf()

        override suspend fun render(update: SourceUpdate, storedPublisher: Publisher?): MediaRef {
            renderCalls += 1
            renderedUpdates += update
            return MediaRef(uri, MediaKind.IMAGE)
        }
    }

    private class StablePathDrawService : DynamicDrawService {
        override suspend fun render(update: SourceUpdate, storedPublisher: Publisher?): MediaRef {
            return MediaRef(
                uri = "data/rendered/${update.platformId.value}/${update.publisher.externalId}/${update.key.externalId}.webp",
                kind = MediaKind.IMAGE,
            )
        }
    }

    private class FakeLinkResolver(
        private val publisher: PublisherInfo = testPublisherInfo(name = "Resolver UP"),
    ) : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        var parseCalls: Int = 0
        var resolveCalls: Int = 0

        override fun matchesLink(inputUrl: String): Boolean {
            return "example.com/dynamic" in inputUrl
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink {
            parseCalls += 1
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.DYNAMIC,
                targetId = "1",
                normalizedUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            resolveCalls += 1
            return LinkResolution.Dynamic(
                parsedLink = parsedLink,
                update = testDynamicUpdate(
                    publisher = publisher,
                    externalId = parsedLink.targetId,
                    payload = top.colter.dynamic.core.data.DynamicPayload(
                        blocks = listOf(
                            top.colter.dynamic.core.data.TextBlock(
                                top.colter.dynamic.core.data.DynamicContent.text("resolver content"),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    private class FakePreviewLinkResolver(
        private val kind: String = LinkKinds.VIDEO,
        private val cover: MediaRef? = MediaRef("https://example.com/cover.jpg", MediaKind.COVER),
        private val publisher: PublisherInfo? = null,
    ) : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override fun matchesLink(inputUrl: String): Boolean {
            return "example.com/video" in inputUrl
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink {
            return ParsedLink(
                platformId = platformId,
                kind = kind,
                targetId = "1",
                normalizedUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            return LinkResolution.Preview(
                parsedLink = parsedLink,
                preview = LinkPreview(
                    platformId = platformId,
                    kind = kind,
                    id = parsedLink.targetId,
                    url = parsedLink.normalizedUrl,
                    title = "Preview video",
                    description = "preview content",
                    cover = cover,
                    publisher = publisher,
                    metrics = listOf(
                        DynamicMetric("play", raw = 123_000, display = "12.3万"),
                        DynamicMetric("danmaku", raw = 234, display = "234"),
                        DynamicMetric("like", raw = 56, display = "56"),
                        DynamicMetric("coin", raw = 9, display = "9"),
                        DynamicMetric("favorite", raw = 8, display = "8"),
                        DynamicMetric("comment", raw = 7, display = "7"),
                        DynamicMetric("share", raw = 10, display = "10"),
                    ),
                ),
            )
        }
    }
}
