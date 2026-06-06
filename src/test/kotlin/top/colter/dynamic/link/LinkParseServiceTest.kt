package top.colter.dynamic.link

import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.LinkParseProgressReplyConfig
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.LinkVideoDownloadConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandStatus
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
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PersistenceManager
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
        val resolver = FakeLinkResolver(
            publisher = testPublisherInfo(
                key = testPublisherKey(platformId = "bilibili", externalId = "123"),
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

        listener.onMessage(commandEvent("https://t.bilibili.com/1", botAccountId = "42"))
        assertEquals(0, resolver.resolveCalls)

        listener.onMessage(
            commandEvent(
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

        listener.onMessage(commandEvent("https://t.bilibili.com/1", botAccountId = "24"))
        assertEquals(0, resolver.resolveCalls)

        listener.onMessage(commandEvent("https://t.bilibili.com/1", botAccountId = "42"))
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
            commandEvent(
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
    fun `video preview should append downloaded video batch when enabled`() = runBlocking {
        initDb("video-download")
        val cacheRoot = createTempDirectory("dynamic-bot-link-video-cache")
        val videoFile = cacheRoot.resolve("video.mp4")
        videoFile.writeBytes(byteArrayOf(0, 0, 0, 1))
        val resolver = FakeVideoLinkResolver()
        val downloader = FakeVideoDownloader(
            result = LinkVideoDownloadResult(
                video = MediaRef(videoFile.toString(), MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = 4,
                title = "demo video",
                durationSeconds = 120,
            ),
        )
        val service = LinkParseService(
            resolversProvider = { listOf(resolver) },
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        videoDownload = LinkVideoDownloadConfig(
                            enabled = true,
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
        )

        val result = service.parseAndDispatch(
            text = "https://www.bilibili.com/video/BV1xx411c7mD",
            context = commandEvent("").context,
            maxLinks = 1,
        )

        assertEquals(1, result.forwarded.size)
        assertEquals("BV1xx411c7mD", downloader.requests.single().parsedLink.targetId)
        val delivery = MessageDeliveryRepository.findRecent(limit = 1).single()
        val message = MessageDeliveryRepository.findMessage(delivery.messageId)!!
        assertEquals(2, message.batches.size)
        assertTrue(message.batches.first().content.single() is MessageContent.Image)
        val video = message.batches[1].content.single() as MessageContent.Video
        assertEquals(videoFile.toString(), video.video.uri)
        assertEquals(MediaKind.VIDEO, video.video.kind)
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
                        videoDownload = LinkVideoDownloadConfig(
                            enabled = true,
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
        botAccountId: String? = null,
        mentionedAccountIds: Set<String> = emptySet(),
    ): CommandEvent {
        return CommandEvent(
            sourcePlugin = "test",
            context = CommandContext.of(
                platform = "onebot",
                kind = TargetKind.GROUP,
                externalId = "100",
                senderId = "sender",
                botAccountId = botAccountId,
                mentionedAccountIds = mentionedAccountIds,
            ),
            rawText = rawText,
            traceId = "trace",
        )
    }

    private fun renderMessage(result: CommandResultEvent): String {
        return result.chain.flatMap { it.content }.joinToString("\n") { it.fallbackText }
    }

    private class RecordingProgressMessenger : LinkParseProgressMessenger {
        val sentTexts: MutableList<String> = mutableListOf()
        val recalledIds: MutableList<String> = mutableListOf()

        override suspend fun send(
            event: CommandEvent,
            config: LinkParseProgressReplyConfig,
        ): LinkParseProgressReceipt? {
            sentTexts += config.text
            return LinkParseProgressReceipt(event.context.target, "progress-${sentTexts.size}")
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

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
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
        var resolveCalls: Int = 0
            private set
        val resolvedIds: MutableList<String> = mutableListOf()

        override suspend fun parseLink(inputUrl: String): ParsedLink? {
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

    private class FakeVideoLinkResolver : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")

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
                    durationSeconds = 120,
                ),
            )
        }
    }

    private class FakeVideoDownloader(
        private val result: LinkVideoDownloadResult,
    ) : LinkVideoDownloader {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        val requests: MutableList<LinkVideoDownloadRequest> = mutableListOf()

        override suspend fun downloadVideoLink(request: LinkVideoDownloadRequest): LinkVideoDownloadResult {
            requests += request
            return result
        }
    }
}
