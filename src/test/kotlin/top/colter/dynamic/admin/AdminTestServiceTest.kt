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
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.draw.DynamicDrawService
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisherInfo

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
    fun realLinkPreviewShouldRenderDrawThroughPreviewTemplate() = runBlocking {
        val resolver = FakePreviewLinkResolver()
        val baseDrawService = FakeDrawService("D:/tmp/admin-test-base-draw.png")
        val themedDrawService = FakeDrawService("D:/tmp/admin-test-themed-draw.png")
        val service = AdminTestService(
            resolversProvider = { listOf(resolver) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}\n{title}"),
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
        val image = response.batches.single().content.first() as MessageContent.Image
        assertEquals("D:/tmp/admin-test-themed-draw.png", image.image.uri)
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

    private class FakeDrawService(
        private val uri: String = "D:/tmp/admin-test-draw.png",
    ) : DynamicDrawService {
        var renderCalls: Int = 0

        override suspend fun render(update: SourceUpdate, storedPublisher: Publisher?): MediaRef {
            renderCalls += 1
            return MediaRef(uri, MediaKind.IMAGE)
        }
    }

    private class FakeLinkResolver : LinkResolver {
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
                    publisher = testPublisherInfo(name = "Resolver UP"),
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

    private class FakePreviewLinkResolver : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override fun matchesLink(inputUrl: String): Boolean {
            return "example.com/video" in inputUrl
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink {
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.VIDEO,
                targetId = "1",
                normalizedUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            return LinkResolution.Preview(
                parsedLink = parsedLink,
                preview = LinkPreview(
                    platformId = platformId,
                    kind = LinkKinds.VIDEO,
                    id = parsedLink.targetId,
                    url = parsedLink.normalizedUrl,
                    title = "Preview video",
                    description = "preview content",
                    cover = MediaRef("https://example.com/cover.jpg", MediaKind.COVER),
                ),
            )
        }
    }
}
