package top.colter.dynamic.link

import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.LinkParseProgressReplyConfig
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.LinkParseTemplates
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.LinkVideoDownloadConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.PublisherPersistenceMode
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.LinkVideoDownloadRequest
import top.colter.dynamic.core.link.LinkVideoDownloadResult
import top.colter.dynamic.core.link.LinkVideoDownloader
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.draw.LinkPreviewRenderer
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.IncomingTextMessageEvent
import top.colter.dynamic.event.Listener
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey

class LinkParseServiceTest {
    @Test
    fun `parse command should resolve multiple links and publish read-only dynamic requests`() = runBlocking {
        initDb("parse-command-multiple")
        val eventBus = EventBus()
        val resolver = FakeLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(
                command = top.colter.dynamic.CommandConfig(requirePermissionRule = false),
                linkParsing = LinkParsingConfig(maxLinksPerMessage = 5),
            ),
            linkParseService = LinkParseService(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatchCommand(
            eventBus,
            listener,
            commandEvent("/db parse https://t.bilibili.com/1 https://t.bilibili.com/2"),
        )
        val first = withTimeout(3_000) { sourceUpdates.receive() }
        val second = withTimeout(3_000) { sourceUpdates.receive() }

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("成功 2 个"))
        assertEquals(listOf("1", "2"), resolver.resolvedIds)
        assertEquals(listOf("1", "2"), listOf(first.update.key.externalId, second.update.key.externalId))
        assertEquals(PublisherPersistenceMode.READ_ONLY, first.publisherPersistenceMode)
        assertEquals(LINK_PARSE_EVENT_LABEL, first.deliveryTag)
    }

    @Test
    fun `link parse service should enrich missing publisher info without writing publisher database`() = runBlocking {
        initDb("publisher-enrich")
        val publisherKey = testPublisherKey(platformId = "bilibili", externalId = "123")
        val resolver = FakeLinkResolver(
            publisher = testPublisherInfo(
                key = publisherKey,
                name = "",
                avatar = MediaRef("", MediaKind.AVATAR),
            ),
        )
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val service = LinkParseService(
            resolversProvider = { listOf(resolver) },
            sourceUpdatePublisher = sourceUpdates,
            publisherLookupResolver = {
                FakePublisherLookupPlugin(
                    testPublisherInfo(
                        key = testPublisherKey(platformId = "bilibili", externalId = "123"),
                        name = "补全用户",
                        avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR),
                        banner = MediaRef("https://example.com/banner.png", MediaKind.COVER),
                    )
                )
            },
        )

        val result = service.parseAndDispatch(
            text = "https://t.bilibili.com/1",
            context = commandEvent("").context,
            maxLinks = 1,
        )
        val request = withTimeout(3_000) { sourceUpdates.receive() }

        assertEquals(1, result.forwarded.size)
        assertEquals("补全用户", request.update.publisher.name)
        assertEquals("https://example.com/avatar.png", request.update.publisher.avatar.uri)
        assertEquals("https://example.com/banner.png", request.update.publisher.banner?.uri)
        assertNull(PublisherRepository.findByKey(publisherKey))
    }

    @Test
    fun `link parse service should continue remote lookup when stored dynamic publisher is incomplete`() = runBlocking {
        initDb("publisher-enrich-stored-incomplete")
        val publisherKey = testPublisherKey(platformId = "bilibili", externalId = "123")
        PublisherRepository.upsertInfo(
            testPublisherInfo(
                key = publisherKey,
                name = "本地用户",
                avatar = MediaRef("https://example.com/local-avatar.png", MediaKind.AVATAR),
                banner = null,
            ),
        )
        val resolver = FakeLinkResolver(
            publisher = testPublisherInfo(
                key = publisherKey,
                name = "",
                avatar = MediaRef("", MediaKind.AVATAR),
                banner = null,
            ),
        )
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val lookup = FakePublisherLookupPlugin(
            testPublisherInfo(
                key = publisherKey,
                name = "远程用户",
                avatar = MediaRef("https://example.com/remote-avatar.png", MediaKind.AVATAR),
                banner = MediaRef("https://example.com/remote-banner.png", MediaKind.COVER),
            )
        )
        val service = LinkParseService(
            resolversProvider = { listOf(resolver) },
            sourceUpdatePublisher = sourceUpdates,
            publisherLookupResolver = { lookup },
        )

        val result = service.parseAndDispatch(
            text = "https://t.bilibili.com/1",
            context = commandEvent("").context,
            maxLinks = 1,
        )
        val request = withTimeout(3_000) { sourceUpdates.receive() }

        assertEquals(1, result.forwarded.size)
        assertEquals(1, lookup.fetchPublisherInfoCalls)
        assertEquals("本地用户", request.update.publisher.name)
        assertEquals("https://example.com/local-avatar.png", request.update.publisher.avatar.uri)
        assertEquals("https://example.com/remote-banner.png", request.update.publisher.banner?.uri)
        assertNull(PublisherRepository.findByKey(publisherKey)?.banner)
    }

    @Test
    fun `preview publisher should prefer local banner without remote lookup`() = runBlocking {
        initDb("preview-publisher-local-banner")
        val publisherKey = testPublisherKey(platformId = "bilibili", externalId = "42")
        PublisherRepository.upsertInfo(
            testPublisherInfo(
                key = publisherKey,
                name = "本地 UP",
                avatar = MediaRef("https://example.com/local-avatar.png", MediaKind.AVATAR),
                banner = MediaRef("https://example.com/local-banner.png", MediaKind.COVER),
            ),
        )
        val lookup = FakePublisherLookupPlugin(
            testPublisherInfo(
                key = publisherKey,
                name = "远程 UP",
                avatar = MediaRef("https://example.com/remote-avatar.png", MediaKind.AVATAR),
                banner = MediaRef("https://example.com/remote-banner.png", MediaKind.COVER),
            )
        )
        var renderedPreview: LinkPreview? = null
        val service = LinkParseService(
            resolversProvider = {
                listOf(
                    FakeVideoLinkResolver(
                        publisher = testPublisherInfo(
                            key = publisherKey,
                            name = "视频 UP",
                            avatar = MediaRef("https://example.com/video-avatar.png", MediaKind.AVATAR),
                            banner = null,
                        )
                    )
                )
            },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}"),
                    ),
                )
            },
            publisherLookupResolver = { lookup },
            previewRenderer = LinkPreviewRenderer { preview ->
                renderedPreview = preview
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        assertEquals(0, lookup.fetchPublisherInfoCalls)
        assertEquals("https://example.com/local-banner.png", renderedPreview?.publisher?.banner?.uri)
    }

    @Test
    fun `preview publisher should use remote lookup when local info is missing`() = runBlocking {
        initDb("preview-publisher-remote-banner")
        val publisherKey = testPublisherKey(platformId = "bilibili", externalId = "42")
        val lookup = FakePublisherLookupPlugin(
            testPublisherInfo(
                key = publisherKey,
                name = "远程 UP",
                avatar = MediaRef("https://example.com/remote-avatar.png", MediaKind.AVATAR),
                banner = MediaRef("https://example.com/remote-banner.png", MediaKind.COVER),
            )
        )
        var renderedPreview: LinkPreview? = null
        val service = LinkParseService(
            resolversProvider = {
                listOf(
                    FakeVideoLinkResolver(
                        publisher = testPublisherInfo(
                            key = publisherKey,
                            name = "视频 UP",
                            avatar = MediaRef("https://example.com/video-avatar.png", MediaKind.AVATAR),
                            banner = null,
                        )
                    )
                )
            },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}"),
                    ),
                )
            },
            publisherLookupResolver = { lookup },
            previewRenderer = LinkPreviewRenderer { preview ->
                renderedPreview = preview
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        assertEquals(1, lookup.fetchPublisherInfoCalls)
        assertEquals("https://example.com/remote-banner.png", renderedPreview?.publisher?.banner?.uri)
        assertNull(PublisherRepository.findByKey(publisherKey))
    }

    @Test
    fun `user preview should use enriched publisher banner as cover`() = runBlocking {
        initDb("preview-user-cover")
        val publisherKey = testPublisherKey(platformId = "bilibili", externalId = "42")
        val lookup = FakePublisherLookupPlugin(
            testPublisherInfo(
                key = publisherKey,
                name = "远程 UP",
                avatar = MediaRef("https://example.com/remote-avatar.png", MediaKind.AVATAR),
                banner = MediaRef("https://example.com/remote-banner.png", MediaKind.COVER),
            )
        )
        val service = LinkParseService(
            resolversProvider = {
                listOf(
                    FakeUserLinkResolver(
                        publisher = testPublisherInfo(
                            key = publisherKey,
                            name = "用户 UP",
                            avatar = MediaRef("https://example.com/user-avatar.png", MediaKind.AVATAR),
                            banner = null,
                        )
                    )
                )
            },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{cover}"),
                    ),
                )
            },
            publisherLookupResolver = { lookup },
        )

        val result = service.parseAndDispatch(
            text = "https://space.bilibili.com/42",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        assertEquals(1, lookup.fetchPublisherInfoCalls)
        val delivery = MessageDeliveryRepository.findRecent(limit = 1).single()
        val message = MessageDeliveryRepository.findMessage(delivery.messageId)!!
        val cover = message.batches.single().content.single() as MessageContent.Image
        assertEquals("https://example.com/remote-banner.png", cover.image.uri)
    }

    @Test
    fun `auto parser should require bot mention and recall one progress prompt`() = runBlocking {
        initDb("auto-mention-progress")
        val resolver = FakeLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val messenger = RecordingProgressMessenger()
        val listener = LinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        fallbackTriggerMode = LinkParseTriggerMode.MENTION_ONLY,
                        progressReply = LinkParseProgressReplyConfig(),
                    ),
                )
            },
            linkParseService = LinkParseService(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            progressMessenger = messenger,
        )

        listener.onMessage(incomingTextEvent("https://t.bilibili.com/1", botAccountId = "42"))
        assertEquals(0, resolver.resolveCalls)

        listener.onMessage(
            incomingTextEvent(
                "@42 https://t.bilibili.com/1",
                botAccountId = "42",
                mentionedAccountIds = setOf("42"),
            )
        )
        val request = withTimeout(3_000) { sourceUpdates.receive() }

        assertEquals("1", request.update.key.externalId)
        assertEquals(1, resolver.resolveCalls)
        assertEquals(listOf("链接解析中，请稍候..."), messenger.sentTexts)
        assertEquals(listOf("progress-1"), messenger.recalledIds)
    }

    @Test
    fun `auto parser should send progress before slow link detection completes`() = runBlocking {
        initDb("auto-progress-before-parse")
        val resolver = BlockingLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val messenger = RecordingProgressMessenger()
        val listener = LinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        fallbackTriggerMode = LinkParseTriggerMode.ALWAYS,
                        progressReply = LinkParseProgressReplyConfig(),
                    ),
                )
            },
            linkParseService = LinkParseService(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            progressMessenger = messenger,
        )

        val job = async {
            listener.onMessage(incomingTextEvent("https://slow.example/1"))
        }
        withTimeout(3_000) { resolver.parseStarted.await() }

        assertEquals(1, messenger.sentTexts.size)
        assertEquals(0, resolver.resolveCalls)

        resolver.continueParsing.complete(Unit)
        val request = withTimeout(3_000) { sourceUpdates.receive() }
        job.await()

        assertEquals("1", request.update.key.externalId)
        assertEquals(1, resolver.resolveCalls)
        assertEquals(listOf("progress-1"), messenger.recalledIds)
    }

    @Test
    fun `auto parser should not send progress when progress text is blank`() = runBlocking {
        initDb("auto-progress-blank-text")
        val resolver = BlockingLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val messenger = RecordingProgressMessenger()
        val listener = LinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        fallbackTriggerMode = LinkParseTriggerMode.ALWAYS,
                        progressReply = LinkParseProgressReplyConfig(text = ""),
                    ),
                )
            },
            linkParseService = LinkParseService(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            progressMessenger = messenger,
        )

        val job = async {
            listener.onMessage(incomingTextEvent("https://slow.example/1"))
        }
        withTimeout(3_000) { resolver.parseStarted.await() }

        assertEquals(emptyList(), messenger.sentTexts)

        resolver.continueParsing.complete(Unit)
        val request = withTimeout(3_000) { sourceUpdates.receive() }
        job.await()

        assertEquals("1", request.update.key.externalId)
        assertEquals(1, resolver.resolveCalls)
        assertEquals(emptyList(), messenger.recalledIds)
    }

    @Test
    fun `auto parser should not send progress for unsupported urls`() = runBlocking {
        initDb("auto-progress-unsupported")
        val resolver = FakeLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val messenger = RecordingProgressMessenger()
        val listener = LinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        fallbackTriggerMode = LinkParseTriggerMode.ALWAYS,
                        progressReply = LinkParseProgressReplyConfig(),
                    ),
                )
            },
            linkParseService = LinkParseService(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            progressMessenger = messenger,
        )

        listener.onMessage(incomingTextEvent("https://example.com/not-supported", hasSupportedLinks = false))

        assertEquals(emptyList(), messenger.sentTexts)
        assertEquals(0, resolver.parseCalls)
        assertEquals(0, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should not reply failure for plain text without links`() = runBlocking {
        initDb("auto-failure-reply-plain-text")
        val eventBus = EventBus()
        val reply = CompletableDeferred<CommandResultEvent>()
        eventBus.subscribe(
            object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) {
                    reply.complete(event)
                }
            },
        )
        val resolver = FakeLinkResolver()
        val listener = LinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        fallbackTriggerMode = LinkParseTriggerMode.ALWAYS,
                    ),
                )
            },
            linkParseService = LinkParseService(
                resolversProvider = { listOf(resolver) },
            ),
            eventBus = eventBus,
        )

        listener.onMessage(incomingTextEvent("今天也正常聊天", hasSupportedLinks = false))

        assertNull(withTimeoutOrNull(300) { reply.await() })
        assertEquals(0, resolver.parseCalls)
        assertEquals(0, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should not reply failure for unsupported url`() = runBlocking {
        initDb("auto-failure-no-reply-unsupported-url")
        val eventBus = EventBus()
        val reply = CompletableDeferred<CommandResultEvent>()
        eventBus.subscribe(
            object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) {
                    reply.complete(event)
                }
            },
        )
        val resolver = FakeLinkResolver()
        val listener = LinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        fallbackTriggerMode = LinkParseTriggerMode.ALWAYS,
                    ),
                )
            },
            linkParseService = LinkParseService(
                resolversProvider = { listOf(resolver) },
            ),
            eventBus = eventBus,
        )

        listener.onMessage(incomingTextEvent("https://example.com/not-supported", hasSupportedLinks = false))

        assertNull(withTimeoutOrNull(300) { reply.await() })
        assertEquals(0, resolver.parseCalls)
        assertEquals(0, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should reply when supported link resolution fails`() = runBlocking {
        initDb("auto-resolution-failure")
        val eventBus = EventBus()
        val reply = CompletableDeferred<CommandResultEvent>()
        eventBus.subscribe(
            object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) {
                    reply.complete(event)
                }
            },
        )
        val messenger = RecordingProgressMessenger()
        val listener = LinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        fallbackTriggerMode = LinkParseTriggerMode.ALWAYS,
                        progressReply = LinkParseProgressReplyConfig(),
                    ),
                )
            },
            linkParseService = LinkParseService(
                resolversProvider = { listOf(FailedVideoResolver()) },
            ),
            eventBus = eventBus,
            progressMessenger = messenger,
        )

        listener.onMessage(incomingTextEvent("https://fail.example/video/BV1PnV46DEP"))
        val result = withTimeout(3_000) { reply.await() }

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(renderMessage(result).contains("CODE: -400; MESSAGE: 请求错误"))
        assertEquals(listOf("progress-1"), messenger.recalledIds)
    }

    @Test
    fun `auto parser should only run on primary bot by default`() = runBlocking {
        initDb("auto-primary-bot")
        val resolver = FakeLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val listener = LinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        fallbackTriggerMode = LinkParseTriggerMode.ALWAYS,
                    ),
                )
            },
            linkParseService = LinkParseService(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            primaryBotAccountResolver = { "42" },
        )

        listener.onMessage(incomingTextEvent("https://t.bilibili.com/1", botAccountId = "24"))
        assertEquals(0, resolver.resolveCalls)

        listener.onMessage(incomingTextEvent("https://t.bilibili.com/1", botAccountId = "42"))
        val request = withTimeout(3_000) { sourceUpdates.receive() }

        assertEquals("1", request.update.key.externalId)
        assertEquals(1, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should run on mentioned non-primary bot`() = runBlocking {
        initDb("auto-mentioned-non-primary-bot")
        val resolver = FakeLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val listener = LinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        fallbackTriggerMode = LinkParseTriggerMode.ALWAYS,
                    ),
                )
            },
            linkParseService = LinkParseService(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            primaryBotAccountResolver = { "42" },
        )

        listener.onMessage(
            incomingTextEvent(
                "@24 https://t.bilibili.com/2",
                botAccountId = "24",
                mentionedAccountIds = setOf("24"),
            )
        )
        val request = withTimeout(3_000) { sourceUpdates.receive() }

        assertEquals("2", request.update.key.externalId)
        assertEquals(1, resolver.resolveCalls)
    }

    @Test
    fun `video preview should enqueue preview before downloading and video separately when enabled`() = runBlocking {
        initDb("video-download")
        val cacheRoot = createTempDirectory("dynamic-bot-link-video-cache")
        val videoFile = cacheRoot.resolve("video.mp4")
        videoFile.writeBytes(byteArrayOf(0, 0, 0, 1))
        val resolver = FakeVideoLinkResolver()
        val messenger = RecordingProgressMessenger()
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef(videoFile.toString(), MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 4,
                title = "demo video",
                durationSeconds = 120,
            ),
            onRequest = {
                val deliveries = MessageDeliveryRepository.findRecent(limit = 10)
                assertEquals(1, deliveries.size)
                val previewMessage = MessageDeliveryRepository.findMessage(deliveries.single().messageId)!!
                assertEquals(1, previewMessage.batches.size)
                val content = previewMessage.batches.single().content
                assertTrue(content.any { it is MessageContent.Image })
                assertEquals(emptyList(), content.filterIsInstance<MessageContent.Text>())
                assertEquals(listOf("视频下载中，请稍候..."), messenger.sentTexts)
            },
        )
        val service = LinkParseService(
            resolversProvider = { listOf(resolver) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}\\r{video}"),
                        videoDownload = LinkVideoDownloadConfig(
                            maxDurationSeconds = 300,
                            maxFileMegabytes = 1.0,
                            cacheRoot = cacheRoot.toString(),
                        ),
                    ),
                )
            },
            videoDownloadersProvider = { listOf(downloader) },
            previewRenderer = LinkPreviewRenderer {
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
            progressMessenger = messenger,
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
            inReplyTo = "trace",
        )

        assertEquals(1, result.forwarded.size)
        assertEquals(2, result.forwarded.single().deliveryCount)
        assertEquals("BV1xx411c7mD", downloader.requests.single().parsedLink.targetId)
        assertEquals(listOf("progress-1"), messenger.recalledIds)
        val messages = MessageDeliveryRepository.findRecent(limit = 10)
            .mapNotNull { MessageDeliveryRepository.findMessage(it.messageId) }
        assertEquals(2, messages.size)
        assertEquals(1, messages.count { message -> message.batches.single().content.any { it is MessageContent.Image } })
        val videoMessage = messages.single { message -> message.batches.single().content.any { it is MessageContent.Video } }
        val video = videoMessage.batches.single().content.single() as MessageContent.Video
        assertEquals(videoFile.toString(), video.video.uri)
        assertEquals(MediaKind.VIDEO, video.video.kind)
    }

    @Test
    fun `video preview stats placeholder should render metrics`() = runBlocking {
        initDb("video-preview-stats")
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{stats}"),
                    ),
                )
            },
            previewRenderer = LinkPreviewRenderer {
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        val delivery = MessageDeliveryRepository.findRecent(limit = 1).single()
        val message = MessageDeliveryRepository.findMessage(delivery.messageId)!!
        val text = message.batches.single().content.single() as MessageContent.Text
        assertEquals("12.3万播放 / 234弹幕 / 56点赞 / 9投币 / 8收藏 / 7评论 / 10转发", text.fallbackText)
    }

    @Test
    fun `missing preview placeholders should be removed from rendered text`() = runBlocking {
        initDb("live-preview-missing-placeholders")
        val service = LinkParseService(
            resolversProvider = { listOf(FakeLiveLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(
                            message = "标题：{title} 大小：{size}\\n未知：{not_exists}\\n{stats}\\r{video}",
                        ),
                    ),
                )
            },
            previewRenderer = LinkPreviewRenderer {
                error("缺失占位符清理不应触发绘图")
            },
        )

        val result = service.parseAndDispatch(
            text = "https://live.bilibili.com/14375476",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        val message = MessageDeliveryRepository.findRecent(limit = 1)
            .mapNotNull { MessageDeliveryRepository.findMessage(it.messageId) }
            .single()
        val text = message.batches.single().content.single() as MessageContent.Text
        assertEquals("标题：demo live\n42在线", text.fallbackText)
        assertTrue(text.fallbackText.startsWith("标题：demo live"))
        assertFalse(text.fallbackText.contains("{"))
        assertFalse(text.fallbackText.contains("大小"))
        assertFalse(text.fallbackText.contains("未知"))
    }

    @Test
    fun `preview template diagnostics should report skipped video fields`() = runBlocking {
        initDb("video-preview-diagnostics")
        val renderer = LinkPreviewTemplateRenderer(
            LinkPreviewRenderer {
                error("preview-only rendering should not request draw here")
            },
        )
        val result = renderer.renderPreviewResult(
            "{title}\\n{size}\\n{stats}\\r{video}",
            LinkPreview(
                platformId = PlatformId.of("bilibili"),
                kind = LinkKinds.VIDEO,
                id = "BV1xx411c7mD",
                url = "https://www.bilibili.com/video/BV1xx411c7mD",
                title = "demo video",
                description = "demo description",
                metrics = listOf(DynamicMetric("play", display = "12.3万")),
            ),
        )

        assertEquals("demo video\n12.3万播放", result.batches.single().content.single().fallbackText)
        assertTrue(result.diagnostics.missingPlaceholders.contains("size"))
        assertTrue(result.diagnostics.missingPlaceholders.contains("video"))
        assertTrue(result.diagnostics.previewSkippedVideoPlaceholders)
        assertTrue(result.diagnostics.clearedFragments >= 1)
    }

    @Test
    fun `video download should not limit file size when max megabytes is zero`() = runBlocking {
        initDb("video-download-unlimited-size")
        val cacheRoot = createTempDirectory("dynamic-bot-link-video-cache")
        val videoFile = cacheRoot.resolve("video.mp4")
        videoFile.writeBytes(byteArrayOf(0, 0, 0, 1))
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef(videoFile.toString(), MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 512L * 1024L * 1024L,
            ),
        )
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}\\r{video}"),
                        videoDownload = LinkVideoDownloadConfig(
                            maxFileMegabytes = 0.0,
                            cacheRoot = cacheRoot.toString(),
                        ),
                    ),
                )
            },
            videoDownloadersProvider = { listOf(downloader) },
            previewRenderer = LinkPreviewRenderer {
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        assertEquals(0, downloader.requests.single().maxBytes)
        val messages = MessageDeliveryRepository.findRecent(limit = 10)
            .mapNotNull { MessageDeliveryRepository.findMessage(it.messageId) }
        assertTrue(messages.any { message -> message.batches.single().content.any { it is MessageContent.Video } })
    }

    @Test
    fun `video download should continue in background when scope is configured`() = runBlocking {
        initDb("video-download-background")
        val cacheRoot = createTempDirectory("dynamic-bot-link-video-cache")
        val videoFile = cacheRoot.resolve("video.mp4")
        videoFile.writeBytes(byteArrayOf(0, 0, 0, 1))
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef(videoFile.toString(), MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 4,
            ),
            onRequest = {
                downloadStarted.complete(Unit)
                releaseDownload.await()
            },
        )
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}\\r{video}"),
                        videoDownload = LinkVideoDownloadConfig(
                            maxFileMegabytes = 1.0,
                            cacheRoot = cacheRoot.toString(),
                        ),
                    ),
                )
            },
            videoDownloadersProvider = { listOf(downloader) },
            previewRenderer = LinkPreviewRenderer {
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
            progressMessenger = RecordingProgressMessenger(),
            backgroundScope = this,
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
            inReplyTo = "trace",
        )

        assertEquals(1, result.forwarded.size)
        assertEquals(1, result.forwarded.single().deliveryCount)
        withTimeout(3_000) { downloadStarted.await() }
        assertEquals(1, MessageDeliveryRepository.findRecent(limit = 10).size)

        releaseDownload.complete(Unit)
        withTimeout(3_000) {
            while (MessageDeliveryRepository.findRecent(limit = 10).size < 2) {
                delay(10)
            }
        }
    }

    @Test
    fun `same video link should not run duplicate downloads concurrently`() = runBlocking {
        initDb("video-download-same-link")
        val cacheRoot = createTempDirectory("dynamic-bot-link-video-cache")
        val videoFile = cacheRoot.resolve("video.mp4")
        val firstDownloadStarted = CompletableDeferred<Unit>()
        val releaseFirstDownload = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger()
        val activeDownloads = AtomicInteger()
        val maxActiveDownloads = AtomicInteger()
        var secondRequestSawCachedFile = false
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef(videoFile.toString(), MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 4,
            ),
            onRequest = {
                val active = activeDownloads.incrementAndGet()
                maxActiveDownloads.updateAndGet { current -> maxOf(current, active) }
                try {
                    when (requestCount.incrementAndGet()) {
                        1 -> {
                            firstDownloadStarted.complete(Unit)
                            releaseFirstDownload.await()
                            videoFile.writeBytes(byteArrayOf(0, 0, 0, 1))
                        }
                        2 -> {
                            secondRequestSawCachedFile = videoFile.toFile().isFile
                        }
                    }
                } finally {
                    activeDownloads.decrementAndGet()
                }
            },
        )
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}\\r{video}"),
                        videoDownload = LinkVideoDownloadConfig(
                            maxConcurrentDownloads = 2,
                            maxFileMegabytes = 1.0,
                            cacheRoot = cacheRoot.toString(),
                        ),
                    ),
                )
            },
            videoDownloadersProvider = { listOf(downloader) },
            previewRenderer = LinkPreviewRenderer {
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
        )

        val first = async {
            service.parseAndDispatch(
                text = "https://www.bilibili.com/video/BV1xx411c7mD",
                context = commandEvent("", targetExternalId = "100").context,
                maxLinks = 1,
            )
        }
        val second = async {
            service.parseAndDispatch(
                text = "https://www.bilibili.com/video/BV1xx411c7mD",
                context = commandEvent("", targetExternalId = "200").context,
                maxLinks = 1,
            )
        }

        withTimeout(3_000) { firstDownloadStarted.await() }
        delay(100)
        assertEquals(1, requestCount.get())

        releaseFirstDownload.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, requestCount.get())
        assertEquals(1, maxActiveDownloads.get())
        assertTrue(secondRequestSawCachedFile)
    }

    @Test
    fun `link preview should use configured text template without drawing`() = runBlocking {
        initDb("preview-template-text")
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(
                            message = "【{kind}】{title}\\n{name}@{uid}\\n{content}\\n{link}",
                        ),
                    ),
                )
            },
            previewRenderer = LinkPreviewRenderer {
                error("纯文本链接解析模板不应触发绘图")
            },
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        val delivery = MessageDeliveryRepository.findRecent(limit = 1).single()
        val message = MessageDeliveryRepository.findMessage(delivery.messageId)!!
        val text = message.batches.single().content.single() as MessageContent.Text
        assertEquals(
            "【视频】demo video\nbilibili@BV1xx411c7mD\ndemo description\nhttps://www.bilibili.com/video/BV1xx411c7mD",
            text.fallbackText,
        )
    }

    @Test
    fun `video link template without video placeholder should not download video`() = runBlocking {
        initDb("video-download-template-without-video")
        val cacheRoot = createTempDirectory("dynamic-bot-link-video-cache")
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef("video.mp4", MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 4,
            ),
        )
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{title}\\n{link}"),
                        videoDownload = LinkVideoDownloadConfig(
                            maxFileMegabytes = 1.0,
                            cacheRoot = cacheRoot.toString(),
                        ),
                    ),
                )
            },
            videoDownloadersProvider = { listOf(downloader) },
            previewRenderer = LinkPreviewRenderer {
                error("纯文本链接解析模板不应触发绘图")
            },
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        assertEquals(emptyList(), downloader.requests)
        val delivery = MessageDeliveryRepository.findRecent(limit = 1).single()
        val message = MessageDeliveryRepository.findMessage(delivery.messageId)!!
        val text = message.batches.single().content.single() as MessageContent.Text
        assertEquals("demo video\nhttps://www.bilibili.com/video/BV1xx411c7mD", text.fallbackText)
    }

    @Test
    fun `video placeholder inside forward should wait for download and send video in merged forward`() = runBlocking {
        initDb("video-download-forward-sync")
        val cacheRoot = createTempDirectory("dynamic-bot-link-video-cache")
        val videoFile = cacheRoot.resolve("video.mp4")
        videoFile.writeBytes(byteArrayOf(0, 0, 0, 1))
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        val messenger = RecordingProgressMessenger()
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef(videoFile.toString(), MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 4,
            ),
            onRequest = {
                downloadStarted.complete(Unit)
                releaseDownload.await()
            },
        )
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{>>}{draw}\\r{video}{<<}"),
                        videoDownload = LinkVideoDownloadConfig(
                            maxFileMegabytes = 1.0,
                            cacheRoot = cacheRoot.toString(),
                        ),
                    ),
                )
            },
            videoDownloadersProvider = { listOf(downloader) },
            previewRenderer = LinkPreviewRenderer {
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
            progressMessenger = messenger,
            backgroundScope = this,
        )

        val resultJob = async {
            service.parseAndDispatch(
                text = "https://www.bilibili.com/video/BV1xx411c7mD",
                context = commandEvent("").context,
                maxLinks = 1,
                inReplyTo = "trace",
            )
        }

        withTimeout(3_000) { downloadStarted.await() }
        assertFalse(resultJob.isCompleted)
        assertEquals(emptyList(), MessageDeliveryRepository.findRecent(limit = 10))
        assertEquals(listOf("视频下载中，请稍候..."), messenger.sentTexts)

        releaseDownload.complete(Unit)
        val result = withTimeout(3_000) { resultJob.await() }

        assertEquals(1, result.forwarded.size)
        assertEquals(1, result.forwarded.single().deliveryCount)
        assertEquals(listOf("progress-1"), messenger.recalledIds)
        val message = MessageDeliveryRepository.findRecent(limit = 1)
            .mapNotNull { MessageDeliveryRepository.findMessage(it.messageId) }
            .single()
        val forward = message.batches.single().content.single() as MessageContent.Forward
        assertEquals(2, forward.nodes.size)
        assertTrue(forward.nodes[0].batches.single().content.any { it is MessageContent.Image })
        assertTrue(forward.nodes[1].batches.single().content.any { it is MessageContent.Video })
    }

    @Test
    fun `video placeholder inside forward should keep non-video nodes when duration exceeds limit`() = runBlocking {
        initDb("video-download-forward-skip-duration")
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef("video.mp4", MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 4,
            ),
        )
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{>>}{title}\\n{content}\\r{cover}\\r{video}{<<}"),
                        videoDownload = LinkVideoDownloadConfig(
                            maxDurationSeconds = 60,
                            maxFileMegabytes = 1.0,
                        ),
                    ),
                )
            },
            videoDownloadersProvider = { listOf(downloader) },
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        assertEquals(2, result.forwarded.single().deliveryCount)
        assertEquals(emptyList(), downloader.requests)
        val messages = MessageDeliveryRepository.findRecent(limit = 10)
            .mapNotNull { MessageDeliveryRepository.findMessage(it.messageId) }
        assertEquals(2, messages.size)
        val forward = messages
            .flatMap { message -> message.batches.single().content }
            .filterIsInstance<MessageContent.Forward>()
            .single()
        assertEquals(2, forward.nodes.size)
        val firstNodeText = forward.nodes[0].batches.single().content.single() as MessageContent.Text
        assertEquals("demo video\ndemo description", firstNodeText.fallbackText)
        assertTrue(forward.nodes[1].batches.single().content.single() is MessageContent.Image)
        assertFalse(forward.nodes.any { node ->
            node.batches.any { batch -> batch.content.any { it is MessageContent.Video } }
        })
        val feedback = messages
            .flatMap { message -> message.batches.single().content }
            .filterIsInstance<MessageContent.Text>()
            .single { it.fallbackText.contains("视频下载或推送未完成") }
        assertTrue(feedback.fallbackText.contains("视频时长 2m 超过限制 1m"))
    }

    @Test
    fun `video preview should send skipped download hint separately when duration exceeds limit`() = runBlocking {
        initDb("video-download-skip-duration")
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef("video.mp4", MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 4,
            ),
        )
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}\\r{video}"),
                        videoDownload = LinkVideoDownloadConfig(
                            maxDurationSeconds = 60,
                            maxFileMegabytes = 1.0,
                        ),
                    ),
                )
            },
            videoDownloadersProvider = { listOf(downloader) },
            previewRenderer = LinkPreviewRenderer {
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        assertEquals(2, result.forwarded.single().deliveryCount)
        assertEquals(emptyList(), downloader.requests)
        val messages = MessageDeliveryRepository.findRecent(limit = 10)
            .mapNotNull { MessageDeliveryRepository.findMessage(it.messageId) }
        assertEquals(2, messages.size)
        val previewMessage = messages.single { message -> message.batches.single().content.any { it is MessageContent.Image } }
        val content = previewMessage.batches.single().content
        val image = content.filterIsInstance<MessageContent.Image>().single()
        assertEquals("", image.fallbackText)
        assertEquals(emptyList(), content.filterIsInstance<MessageContent.Text>())
        val feedback = messages.single { message -> message.batches.single().content.any { it is MessageContent.Text } }
        val text = feedback.batches.single().content.single() as MessageContent.Text
        assertTrue(text.fallbackText.contains("视频下载或推送未完成"))
        assertTrue(text.fallbackText.contains("视频时长 2m 超过限制 1m"))
    }

    @Test
    fun `live link with video only template should fallback to preview text`() = runBlocking {
        initDb("live-video-only-template")
        val service = LinkParseService(
            resolversProvider = { listOf(FakeLiveLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{>>}{title}\\n{content}\\r{cover}\\r{video}{<<}"),
                    ),
                )
            },
            previewRenderer = LinkPreviewRenderer {
                error("纯视频占位符直播模板不应触发绘图")
            },
        )

        val result = service.parseAndDispatch(
            text = "https://live.bilibili.com/14375476",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(emptyList(), result.failures)
        assertEquals(1, result.forwarded.size)
        assertEquals(1, result.forwarded.single().deliveryCount)
        val message = MessageDeliveryRepository.findRecent(limit = 1)
            .mapNotNull { MessageDeliveryRepository.findMessage(it.messageId) }
            .single()
        val forward = message.batches.single().content.single() as MessageContent.Forward
        assertEquals(2, forward.nodes.size)
        val firstNodeText = forward.nodes[0].batches.single().content.single() as MessageContent.Text
        assertEquals("demo live\ndemo live description", firstNodeText.fallbackText)
        assertTrue(forward.nodes[1].batches.single().content.single() is MessageContent.Image)
    }

    @Test
    fun `video download should resolve blank ffmpeg path from project root`() = runBlocking {
        initDb("video-download-ffmpeg")
        val cacheRoot = createTempDirectory("dynamic-bot-link-video-cache")
        val projectRoot = createTempDirectory("dynamic-bot-project-root")
        val ffmpegName = if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
            "ffmpeg.exe"
        } else {
            "ffmpeg"
        }
        val ffmpeg = projectRoot.resolve("ffmpeg").resolve("bin").resolve(ffmpegName)
        ffmpeg.parent.createDirectories()
        ffmpeg.writeBytes(byteArrayOf(1))
        val videoFile = cacheRoot.resolve("video.mp4")
        videoFile.writeBytes(byteArrayOf(0, 0, 0, 1))
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef(videoFile.toString(), MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 4,
            ),
        )
        val service = LinkParseService(
            resolversProvider = { listOf(FakeVideoLinkResolver()) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        templates = LinkParseTemplates(message = "{draw}\\r{video}"),
                        videoDownload = LinkVideoDownloadConfig(
                            maxFileMegabytes = 1.0,
                            cacheRoot = cacheRoot.toString(),
                            ffmpegPath = "",
                        ),
                    ),
                )
            },
            videoDownloadersProvider = { listOf(downloader) },
            previewRenderer = LinkPreviewRenderer {
                MediaRef("https://example.com/preview.png", MediaKind.IMAGE)
            },
            projectRootProvider = { projectRoot.toFile() },
        )

        service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(ffmpeg.toFile().absolutePath, downloader.requests.single().ffmpegPath)
    }

    private suspend fun dispatchCommand(
        eventBus: EventBus,
        listener: CommandListener,
        event: CommandEvent,
    ): CommandResultEvent {
        val result = CompletableDeferred<CommandResultEvent>()
        eventBus.subscribe(
            object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) {
                    result.complete(event)
                }
            },
        )

        listener.onMessage(event)
        return withTimeout(3_000) { result.await() }
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-main-link-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private fun commandEvent(
        rawText: String,
        targetExternalId: String = "100",
        botAccountId: String? = null,
        mentionedAccountIds: Set<String> = emptySet(),
    ): CommandEvent {
        return CommandEvent(
            sourcePlugin = "test",
            context = CommandContext.of(
                platform = "onebot",
                kind = TargetKind.GROUP,
                externalId = targetExternalId,
                senderId = "sender",
                botAccountId = botAccountId,
                mentionedAccountIds = mentionedAccountIds,
            ),
            rawText = rawText,
            traceId = "trace",
        )
    }

    private fun incomingTextEvent(
        rawText: String,
        targetExternalId: String = "100",
        botAccountId: String? = null,
        mentionedAccountIds: Set<String> = emptySet(),
        hasSupportedLinks: Boolean = true,
    ): IncomingTextMessageEvent {
        val context = CommandContext.of(
            platform = "onebot",
            kind = TargetKind.GROUP,
            externalId = targetExternalId,
            senderId = "sender",
            botAccountId = botAccountId,
            mentionedAccountIds = mentionedAccountIds,
        )
        return IncomingTextMessageEvent(
            sourcePlugin = "test",
            message = IncomingMessage(
                platformId = context.target.platformId,
                target = context.target,
                senderId = context.senderId,
                botAccountId = context.botAccountId,
                text = rawText,
                mentions = context.mentionedAccountIds,
            ),
            context = context,
            rawText = rawText,
            traceId = "trace",
            hasSupportedLinks = hasSupportedLinks,
        )
    }

    private fun renderMessage(result: CommandResultEvent): String {
        return result.chain.flatMap { it.content }.joinToString("\n") { it.fallbackText }
    }

    private class RecordingProgressMessenger : LinkParseProgressMessenger {
        val sentTexts: MutableList<String> = mutableListOf()
        val recalledIds: MutableList<String> = mutableListOf()

        override suspend fun send(
            context: CommandContext,
            inReplyTo: String,
            text: String,
        ): LinkParseProgressReceipt? {
            sentTexts += text
            return LinkParseProgressReceipt(context.target, "progress-${sentTexts.size}")
        }

        override suspend fun recall(receipt: LinkParseProgressReceipt) {
            recalledIds += receipt.sinkMessageId
        }
    }

    private class RecordingSourceUpdatePublisher : SourceUpdatePublisher {
        private val received = Channel<SourceUpdatePublishRequest>(Channel.UNLIMITED)

        override suspend fun publish(request: SourceUpdatePublishRequest): SourceUpdatePublishResult {
            received.send(request)
            return SourceUpdatePublishResult.enqueued(1)
        }

        suspend fun receive(): SourceUpdatePublishRequest = received.receive()
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

    private class FakeLinkResolver(
        private val publisher: PublisherInfo = testPublisherInfo(
            key = testPublisherKey(platformId = "bilibili", externalId = "123"),
            name = "demo-up",
        ),
    ) : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        var parseCalls: Int = 0
            private set
        var resolveCalls: Int = 0
            private set
        val resolvedIds: MutableList<String> = mutableListOf()

        override fun matchesLink(inputUrl: String): Boolean {
            return inputUrl.startsWith("https://t.bilibili.com/")
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink? {
            parseCalls += 1
            val id = inputUrl.substringAfter("https://t.bilibili.com/", missingDelimiterValue = "")
                .takeWhile { it.isDigit() }
                .takeIf { it.isNotBlank() }
                ?: return null
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.DYNAMIC,
                targetId = id,
                normalizedUrl = "https://t.bilibili.com/$id",
                sourceUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            resolveCalls += 1
            resolvedIds += parsedLink.targetId
            return LinkResolution.Dynamic(
                parsedLink = parsedLink,
                update = testDynamicUpdate(
                    publisher = publisher,
                    externalId = parsedLink.targetId,
                ).copy(link = parsedLink.normalizedUrl),
            )
        }
    }

    private class BlockingLinkResolver : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        val parseStarted = CompletableDeferred<Unit>()
        val continueParsing = CompletableDeferred<Unit>()
        var resolveCalls: Int = 0
            private set

        override fun matchesLink(inputUrl: String): Boolean {
            return inputUrl.startsWith("https://slow.example/")
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink? {
            parseStarted.complete(Unit)
            continueParsing.await()
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.DYNAMIC,
                targetId = "1",
                normalizedUrl = "https://t.bilibili.com/1",
                sourceUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            resolveCalls += 1
            return LinkResolution.Dynamic(
                parsedLink = parsedLink,
                update = testDynamicUpdate(
                    publisher = testPublisherInfo(
                        key = testPublisherKey(platformId = "bilibili", externalId = "123"),
                        name = "demo-up",
                    ),
                    externalId = parsedLink.targetId,
                ).copy(link = parsedLink.normalizedUrl),
            )
        }
    }

    private class FailedVideoResolver : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override fun matchesLink(inputUrl: String): Boolean {
            return inputUrl.startsWith("https://fail.example/video/")
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink {
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.VIDEO,
                targetId = inputUrl.substringAfterLast("/"),
                normalizedUrl = inputUrl,
                sourceUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "CODE: -400; MESSAGE: 请求错误",
            )
        }
    }

    private class FakeVideoLinkResolver(
        private val publisher: PublisherInfo? = null,
    ) : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override fun matchesLink(inputUrl: String): Boolean {
            return inputUrl.startsWith("https://www.bilibili.com/video/")
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink? {
            val id = inputUrl.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() } ?: return null
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.VIDEO,
                targetId = id,
                normalizedUrl = "https://www.bilibili.com/video/$id",
                sourceUrl = inputUrl,
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
                    title = "demo video",
                    description = "demo description",
                    cover = MediaRef("https://example.com/cover.png", MediaKind.IMAGE),
                    publisher = publisher,
                    metrics = listOf(
                        DynamicMetric("play", raw = 123_456, display = "12.3万"),
                        DynamicMetric("danmaku", raw = 234, display = "234"),
                        DynamicMetric("like", raw = 56, display = "56"),
                        DynamicMetric("coin", raw = 9, display = "9"),
                        DynamicMetric("favorite", raw = 8, display = "8"),
                        DynamicMetric("comment", raw = 7, display = "7"),
                        DynamicMetric("share", raw = 10, display = "10"),
                    ),
                    durationSeconds = 120,
                ),
            )
        }
    }

    private class FakeUserLinkResolver(
        private val publisher: PublisherInfo,
    ) : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override fun matchesLink(inputUrl: String): Boolean {
            return inputUrl.startsWith("https://space.bilibili.com/")
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink? {
            val id = inputUrl.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() } ?: return null
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.USER,
                targetId = id,
                normalizedUrl = "https://space.bilibili.com/$id",
                sourceUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            return LinkResolution.Preview(
                parsedLink = parsedLink,
                preview = LinkPreview(
                    platformId = platformId,
                    kind = LinkKinds.USER,
                    id = parsedLink.targetId,
                    url = parsedLink.normalizedUrl,
                    title = publisher.name,
                    description = "Bilibili 用户 ${publisher.externalId}",
                    badge = "用户",
                    cover = null,
                    publisher = publisher,
                ),
            )
        }
    }

    private class FakeLiveLinkResolver : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override fun matchesLink(inputUrl: String): Boolean {
            return inputUrl.startsWith("https://live.bilibili.com/")
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink? {
            val id = inputUrl.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() } ?: return null
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.LIVE,
                targetId = id,
                normalizedUrl = "https://live.bilibili.com/$id",
                sourceUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            return LinkResolution.Preview(
                parsedLink = parsedLink,
                preview = LinkPreview(
                    platformId = platformId,
                    kind = LinkKinds.LIVE,
                    id = parsedLink.targetId,
                    url = parsedLink.normalizedUrl,
                    title = "demo live",
                    description = "demo live description",
                    cover = MediaRef("https://example.com/live-cover.png", MediaKind.IMAGE),
                    metrics = listOf(
                        top.colter.dynamic.core.data.DynamicMetric(
                            key = "online",
                            display = "42",
                        ),
                    ),
                ),
            )
        }
    }

    private class FakeVideoDownloader(
        private val result: LinkVideoDownloadResult,
        private val onRequest: suspend () -> Unit = {},
    ) : LinkVideoDownloader {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        val requests: MutableList<LinkVideoDownloadRequest> = mutableListOf()

        override suspend fun downloadVideoLink(request: LinkVideoDownloadRequest): LinkVideoDownloadResult {
            requests += request
            onRequest()
            return result
        }
    }
}
