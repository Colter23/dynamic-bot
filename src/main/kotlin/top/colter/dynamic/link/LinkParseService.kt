package top.colter.dynamic.link

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.LinkVideoDownloadConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.IncomingProcessingResult
import top.colter.dynamic.core.data.IncomingProcessingStage
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscriber
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
import top.colter.dynamic.core.plugin.OutboundMessagePublishRequest
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.DefaultLinkPreviewRenderer
import top.colter.dynamic.draw.LinkPreviewRenderer
import top.colter.dynamic.message.OutboundMessageService
import top.colter.dynamic.repository.IncomingMessageAuditRepository
import top.colter.dynamic.repository.IncomingProcessingWriteRequest
import top.colter.dynamic.repository.SubscriberRepository

internal const val LINK_PARSE_EVENT_LABEL: String = "link-parse"
internal const val LINK_PARSE_EVENT_SOURCE: String = "main-link-parser"

private val linkParseLogger = loggerFor<LinkParseService>()

private fun elapsedMillis(startedNanos: Long): Long {
    return ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0)
}

public class LinkParseService(
    private val resolversProvider: () -> List<LinkResolver>,
    private val configProvider: () -> MainDynamicConfig = { MainDynamicConfig() },
    private val sourceUpdatePublisher: SourceUpdatePublisher = SourceUpdatePublisher {
        SourceUpdatePublishResult.failed("来源更新发布器未配置")
    },
    private val videoDownloadersProvider: () -> List<LinkVideoDownloader> = { emptyList() },
    private val publisherLookupResolver: (String) -> PublisherLookupPlugin? = { null },
    private val previewRenderer: LinkPreviewRenderer = DefaultLinkPreviewRenderer(),
    private val onMessagesQueued: suspend () -> Unit = {},
    private val outboundMessageService: OutboundMessageService = OutboundMessageService(
        onMessagesQueued = onMessagesQueued,
    ),
    private val progressMessenger: LinkParseProgressMessenger = NoopLinkParseProgressMessenger,
    private val backgroundScope: CoroutineScope? = null,
    private val projectRootProvider: () -> File = { File(System.getProperty("user.dir")) },
    private val incomingProcessingRecorder: (IncomingProcessingWriteRequest) -> Boolean = {
        IncomingMessageAuditRepository.recordProcessing(it)
    },
) {
    private val videoDownloadSemaphores: MutableMap<Int, Semaphore> = ConcurrentHashMap()
    private val videoDownloadLocks: MutableMap<String, VideoDownloadLock> = ConcurrentHashMap()
    private val templateRenderer: LinkPreviewTemplateRenderer = LinkPreviewTemplateRenderer(previewRenderer)
    private val publisherEnricher: LinkPublisherEnricher = LinkPublisherEnricher(
        publisherLookupResolver = publisherLookupResolver,
        onLookupFailure = { publisher, error ->
            linkParseLogger.debug(error) {
                "链接解析发布者资料补全失败：platform=${publisher.platformId.value}，publisher=${publisher.externalId}"
            }
        },
    )

    internal suspend fun parseAndDispatch(
        text: String,
        context: CommandContext,
        maxLinks: Int = 1,
        dedupe: LinkParseDedupe? = null,
        dedupeTtlMs: Long = 0,
        inReplyTo: String? = null,
        correlationId: String? = null,
        onForwardingStarted: suspend (ParsedLink) -> Unit = {},
    ): LinkParseBatchResult {
        val startedNanos = System.nanoTime()
        if (maxLinks <= 0) return recordLinkProcessing(
            correlationId = correlationId,
            result = LinkParseBatchResult.disabled(),
            startedNanos = startedNanos,
        )

        val urls = LinkUrlExtractor.extract(text)
        if (urls.isEmpty()) return recordLinkProcessing(
            correlationId = correlationId,
            result = LinkParseBatchResult.noSupportedLink(),
            startedNanos = startedNanos,
        )

        val resolvers = resolversProvider()
        if (resolvers.isEmpty()) {
            return recordLinkProcessing(
                correlationId = correlationId,
                result = LinkParseBatchResult.failed("没有可用的链接解析插件"),
                startedNanos = startedNanos,
            )
        }

        val results = mutableListOf<LinkParseItemResult>()
        var supportedLinks = 0
        var started = false

        for (url in urls) {
            if (supportedLinks >= maxLinks) break
            val candidate = parseUrl(url, resolvers) ?: continue
            supportedLinks += 1

            val parsedLink = candidate.parsedLink
            if (dedupe != null && dedupeTtlMs > 0 && !dedupe.markIfNew(dedupeKey(context, parsedLink), dedupeTtlMs)) {
                results += LinkParseItemResult.Duplicate(parsedLink)
                continue
            }

            if (!started) {
                onForwardingStarted(parsedLink)
                started = true
            }

            results += runCatching {
                when (val resolution = candidate.resolver.resolveLink(parsedLink)) {
                    is LinkResolution.Dynamic -> forwardDynamic(resolution.update, parsedLink, context, correlationId)
                    is LinkResolution.Preview -> forwardPreview(resolution.preview, parsedLink, context, inReplyTo, correlationId)
                    is LinkResolution.Message -> forwardMessage(resolution.batches, parsedLink, context, correlationId)
                    is LinkResolution.Failed -> LinkParseItemResult.Failed(parsedLink, resolution.reason)
                }
            }.getOrElse { error ->
                linkParseLogger.warn(error) {
                    "链接解析处理失败：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
                }
                LinkParseItemResult.Failed(parsedLink, error.message ?: "链接解析处理失败")
            }
        }

        return recordLinkProcessing(
            correlationId = correlationId,
            result = LinkParseBatchResult(results),
            startedNanos = startedNanos,
        )
    }

    private fun recordLinkProcessing(
        correlationId: String?,
        result: LinkParseBatchResult,
        startedNanos: Long,
    ): LinkParseBatchResult {
        val traceId = correlationId?.trim()?.takeIf { it.isNotBlank() } ?: return result
        val processingResult = when {
            result.disabledReason != null -> IncomingProcessingResult.FAILED
            !result.hasSupportedLinks -> IncomingProcessingResult.IGNORED
            result.failures.isNotEmpty() -> IncomingProcessingResult.FAILED
            else -> IncomingProcessingResult.SUCCEEDED
        }
        val errorMessage = result.disabledReason
            ?: result.failureSummary.takeIf { result.failures.isNotEmpty() }
        runCatching {
            incomingProcessingRecorder(
                IncomingProcessingWriteRequest(
                    traceId = traceId,
                    stage = IncomingProcessingStage.LINK_PARSE,
                    handlerId = LINK_PARSE_EVENT_SOURCE,
                    result = processingResult,
                    errorMessage = errorMessage,
                    durationMs = elapsedMillis(startedNanos),
                ),
            )
        }.onFailure { error ->
            linkParseLogger.warn(error) { "链接解析入站处理审计记录失败：traceId=$traceId" }
        }
        return result
    }

    internal fun hasSupportedLinkCandidate(text: String, maxLinks: Int = 1): Boolean {
        if (maxLinks <= 0) return false
        val urls = LinkUrlExtractor.extract(text)
        if (urls.isEmpty()) return false
        val resolvers = resolversProvider()
        if (resolvers.isEmpty()) return false
        return urls.any { url -> resolvers.any { resolver -> resolver.safeMatchesLink(url) } }
    }

    private suspend fun parseUrl(url: String, resolvers: List<LinkResolver>): ParsedLinkCandidate? {
        resolvers.forEach { resolver ->
            if (!resolver.safeMatchesLink(url)) return@forEach
            val parsedLink = runCatching { resolver.parseLink(url) }
                .onFailure {
                    linkParseLogger.warn(it) {
                        "链接识别失败：resolver=${resolver.platformId.value}，url=$url"
                    }
                }
                .getOrNull()
                ?: return@forEach
            return ParsedLinkCandidate(resolver, parsedLink)
        }
        return null
    }

    private fun LinkResolver.safeMatchesLink(url: String): Boolean {
        return runCatching { matchesLink(url) }
            .onFailure {
                linkParseLogger.warn(it) {
                    "链接匹配失败：resolver=${platformId.value}，url=$url"
                }
            }
            .getOrDefault(false)
    }

    private suspend fun forwardDynamic(
        update: SourceUpdate,
        parsedLink: ParsedLink,
        context: CommandContext,
        correlationId: String?,
    ): LinkParseItemResult {
        val enrichedUpdate = enrichPublisherForLinkParse(update)
        val sourcePublisher = enrichedUpdate.publisher
        if (sourcePublisher.externalId.isBlank()) {
            return LinkParseItemResult.Failed(parsedLink, "动态发布者 ID 缺失")
        }

        val subscriber = SubscriberRepository.ensure(
            address = context.target,
            name = context.chatId,
        )
        val result = sourceUpdatePublisher.publish(
            SourceUpdatePublishRequest(
                sourcePlugin = LINK_PARSE_EVENT_SOURCE,
                deliveryTarget = subscriber,
                deliveryTag = LINK_PARSE_EVENT_LABEL,
                correlationId = correlationId,
                update = enrichedUpdate,
                publisherPersistenceMode = PublisherPersistenceMode.READ_ONLY,
            ),
        )
        if (!result.accepted) {
            return LinkParseItemResult.Failed(parsedLink, result.message.ifBlank { "链接转发失败" })
        }

        return LinkParseItemResult.Forwarded(
            parsedLink = parsedLink,
            subscriber = subscriber,
            deliveryCount = result.deliveryCount,
        )
    }

    private suspend fun enrichPublisherForLinkParse(update: SourceUpdate): SourceUpdate {
        return publisherEnricher.enrich(update)
    }

    private suspend fun enrichPreviewForLinkParse(preview: LinkPreview): LinkPreview {
        return publisherEnricher.enrich(preview)
    }

    private suspend fun forwardPreview(
        preview: LinkPreview,
        parsedLink: ParsedLink,
        context: CommandContext,
        inReplyTo: String?,
        correlationId: String?,
    ): LinkParseItemResult {
        val enrichedPreview = enrichPreviewForLinkParse(preview)
        val template = configProvider().linkParsing.templates.message
        val templatePlan = runCatching {
            templateRenderer.plan(template)
        }.getOrElse { error ->
            linkParseLogger.warn(error) {
                "链接解析模板解析失败，回退为文本：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
            }
            return forwardMessage(
                listOf(MessageBatch(listOf(MessageContent.Text(enrichedPreview.fallbackText())))),
                parsedLink,
                context,
                correlationId,
            )
        }

        val immediateBatches = runCatching {
            templateRenderer.renderPlanPreview(templatePlan, enrichedPreview)
        }.getOrElse { error ->
            linkParseLogger.warn(error) {
                "链接卡片模板渲染失败，回退为文本：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
            }
            listOf(MessageBatch(listOf(MessageContent.Text(enrichedPreview.fallbackText()))))
        }
        var currentResult = when {
            immediateBatches.isEmpty() -> null
            else -> when (val immediateResult = forwardMessage(immediateBatches, parsedLink, context, correlationId)) {
                is LinkParseItemResult.Forwarded -> immediateResult
                else -> return immediateResult
            }
        }

        if (!templatePlan.requiresVideo) {
            return currentResult
                ?: forwardMessage(
                    listOf(MessageBatch(listOf(MessageContent.Text(enrichedPreview.fallbackText())))),
                    parsedLink,
                    context,
                    correlationId,
                )
        }

        val downloadPlan = when (val planned = videoDownloadPlan(enrichedPreview, parsedLink)) {
            null -> {
                val fallbackBatches = renderVideoFallbackBatches(templatePlan, enrichedPreview, parsedLink)
                return when {
                    fallbackBatches.isNotEmpty() -> {
                        val fallbackResult = forwardMessage(fallbackBatches, parsedLink, context, correlationId)
                        when (fallbackResult) {
                            is LinkParseItemResult.Forwarded -> currentResult?.withAdditionalDeliveries(fallbackResult.deliveryCount)
                                ?: fallbackResult
                            else -> currentResult
                                ?: forwardMessage(
                                    listOf(MessageBatch(listOf(MessageContent.Text(enrichedPreview.fallbackText())))),
                                    parsedLink,
                                    context,
                                    correlationId,
                                )
                        }
                    }
                    currentResult != null -> currentResult
                    else -> forwardMessage(
                        listOf(MessageBatch(listOf(MessageContent.Text(enrichedPreview.fallbackText())))),
                        parsedLink,
                        context,
                        correlationId,
                    )
                }
            }
            is VideoDownloadPlan.NotSent -> {
                val feedback = sendVideoPrompt(
                    message = planned.message,
                    parsedLink = parsedLink,
                    context = context,
                    inReplyTo = inReplyTo,
                    correlationId = correlationId,
                )
                val fallbackBatches = renderVideoFallbackBatches(templatePlan, enrichedPreview, parsedLink)
                if (fallbackBatches.isNotEmpty()) {
                    val fallbackResult = forwardMessage(fallbackBatches, parsedLink, context, correlationId)
                    return if (fallbackResult is LinkParseItemResult.Forwarded) {
                        currentResult
                            ?.withAdditionalDeliveries(fallbackResult.deliveryCount + feedback.deliveryCount)
                            ?: fallbackResult.withAdditionalDeliveries(feedback.deliveryCount)
                    } else {
                        currentResult?.withAdditionalDeliveries(feedback.deliveryCount)
                            ?: videoPromptOnlyResult(feedback, parsedLink, context)
                            ?: LinkParseItemResult.Failed(parsedLink, planned.message.ifBlank { "视频无法下载或推送" })
                    }
                }
                return currentResult?.withAdditionalDeliveries(feedback.deliveryCount)
                    ?: videoPromptOnlyResult(feedback, parsedLink, context)
                    ?: LinkParseItemResult.Failed(parsedLink, planned.message.ifBlank { "视频无法下载或推送" })
            }
            is VideoDownloadPlan.Download -> planned
        }

        val downloadPrompt = sendVideoPrompt(
            message = downloadPlan.downloadingPrompt,
            parsedLink = parsedLink,
            context = context,
            inReplyTo = inReplyTo,
            correlationId = correlationId,
        )
        currentResult = currentResult?.withAdditionalDeliveries(downloadPrompt.deliveryCount)
            ?: forwardedForContext(parsedLink, context, downloadPrompt.deliveryCount)
        val scope = backgroundScope
        if (scope != null && !templatePlan.requiresVideoInForward) {
            launchVideoDownloadFollowUp(
                scope = scope,
                templatePlan = templatePlan,
                preview = enrichedPreview,
                parsedLink = parsedLink,
                context = context,
                downloader = downloadPlan.downloader,
                currentResult = currentResult,
                downloadPromptReceipt = downloadPrompt.receipt,
                inReplyTo = inReplyTo,
                correlationId = correlationId,
            )
            return currentResult
        }

        return forwardVideoAfterDownload(
            templatePlan = templatePlan,
            preview = enrichedPreview,
            parsedLink = parsedLink,
            context = context,
            downloader = downloadPlan.downloader,
            currentResult = currentResult,
            downloadPromptReceipt = downloadPrompt.receipt,
            inReplyTo = inReplyTo,
            correlationId = correlationId,
        )
    }

    private fun launchVideoDownloadFollowUp(
        scope: CoroutineScope,
        templatePlan: LinkPreviewTemplatePlan,
        preview: LinkPreview,
        parsedLink: ParsedLink,
        context: CommandContext,
        downloader: LinkVideoDownloader,
        currentResult: LinkParseItemResult.Forwarded,
        downloadPromptReceipt: LinkParseProgressReceipt?,
        inReplyTo: String?,
        correlationId: String?,
    ) {
        scope.launch {
            try {
                forwardVideoAfterDownload(
                    templatePlan = templatePlan,
                    preview = preview,
                    parsedLink = parsedLink,
                    context = context,
                    downloader = downloader,
                    currentResult = currentResult,
                    downloadPromptReceipt = downloadPromptReceipt,
                    inReplyTo = inReplyTo,
                    correlationId = correlationId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                linkParseLogger.warn(e) {
                    "链接视频后续推送失败：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，原因=${e.message ?: "未知错误"}"
                }
            }
        }
    }

    private suspend fun forwardVideoAfterDownload(
        templatePlan: LinkPreviewTemplatePlan,
        preview: LinkPreview,
        parsedLink: ParsedLink,
        context: CommandContext,
        downloader: LinkVideoDownloader,
        currentResult: LinkParseItemResult.Forwarded,
        downloadPromptReceipt: LinkParseProgressReceipt?,
        inReplyTo: String?,
        correlationId: String?,
    ): LinkParseItemResult.Forwarded {
        try {
            val video = when (val outcome = downloadVideo(preview, parsedLink, downloader)) {
                is VideoDownloadOutcome.Downloaded -> outcome.result
                is VideoDownloadOutcome.NotSent -> {
                    val feedback = sendVideoPrompt(
                        message = outcome.message,
                        parsedLink = parsedLink,
                        context = context,
                        inReplyTo = inReplyTo,
                        correlationId = correlationId,
                    )
                    val fallbackBatches = runCatching {
                        templateRenderer.renderPlanVideoFallback(templatePlan, preview)
                    }.getOrElse { error ->
                        linkParseLogger.warn(error) {
                            "链接视频模板回退渲染失败，跳过视频消息：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
                        }
                        emptyList()
                    }
                    if (fallbackBatches.isNotEmpty()) {
                        val fallbackResult = forwardMessage(fallbackBatches, parsedLink, context, correlationId)
                        return when (fallbackResult) {
                            is LinkParseItemResult.Forwarded -> currentResult.withAdditionalDeliveries(
                                fallbackResult.deliveryCount + feedback.deliveryCount,
                            )
                            else -> currentResult.withAdditionalDeliveries(feedback.deliveryCount)
                        }
                    }
                    return currentResult.withAdditionalDeliveries(feedback.deliveryCount)
                }
            }
            val videoBatches = runCatching {
                templateRenderer.renderPlanVideo(templatePlan, preview, video)
                    .takeIf { it.isNotEmpty() }
                    ?: error("视频模板未产生可发送内容")
            }.getOrElse { error ->
                linkParseLogger.warn(error) {
                    "链接视频模板渲染失败，跳过视频消息：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
                }
                val feedback = sendVideoPrompt(
                    message = videoFailurePrompt(configProvider().linkParsing.videoDownload, preview, parsedLink, "视频消息渲染失败"),
                    parsedLink = parsedLink,
                    context = context,
                    inReplyTo = inReplyTo,
                    correlationId = correlationId,
                )
                return currentResult.withAdditionalDeliveries(feedback.deliveryCount)
            }
            val videoResult = forwardMessage(videoBatches, parsedLink, context, correlationId)
            return if (videoResult is LinkParseItemResult.Forwarded) {
                currentResult.copy(deliveryCount = currentResult.deliveryCount + videoResult.deliveryCount)
            } else {
                val feedback = sendVideoPrompt(
                    message = videoFailurePrompt(
                        configProvider().linkParsing.videoDownload,
                        preview,
                        parsedLink,
                        videoResult.failureReason(),
                    ),
                    parsedLink = parsedLink,
                    context = context,
                    inReplyTo = inReplyTo,
                    correlationId = correlationId,
                )
                currentResult.withAdditionalDeliveries(feedback.deliveryCount)
            }
        } finally {
            recallVideoPrompt(downloadPromptReceipt)
        }
    }

    private suspend fun downloadVideo(
        preview: LinkPreview,
        parsedLink: ParsedLink,
        downloader: LinkVideoDownloader,
    ): VideoDownloadOutcome {
        val config = configProvider().linkParsing.videoDownload
        if (parsedLink.kind != LinkKinds.VIDEO || preview.kind != LinkKinds.VIDEO) {
            return VideoDownloadOutcome.NotSent(
                videoFailurePrompt(config, preview, parsedLink, "当前链接不是视频"),
            )
        }

        val duration = preview.durationSeconds
        if (duration == null) {
            linkParseLogger.info {
                "链接视频下载跳过：时长未知，platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}"
            }
            return VideoDownloadOutcome.NotSent(
                videoFailurePrompt(config, preview, parsedLink, "视频时长未知"),
            )
        }
        if (config.maxDurationSeconds > 0 && duration > config.maxDurationSeconds) {
            linkParseLogger.info {
                "链接视频下载跳过：时长超限，platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，duration=$duration，max=${config.maxDurationSeconds}"
            }
            return VideoDownloadOutcome.NotSent(
                videoFailurePrompt(
                    config,
                    preview,
                    parsedLink,
                    "视频时长 ${duration.formatDuration()} 超过限制 ${config.maxDurationSeconds.formatDuration()}",
                ),
            )
        }

        val directory = videoCacheDirectory(config, parsedLink.platformId.value)
        val request = LinkVideoDownloadRequest(
            parsedLink = parsedLink,
            directory = directory,
            maxBytes = config.maxFileBytes,
            quality = config.quality,
            ffmpegPath = resolveFfmpegPath(config),
        )

        return try {
            withVideoDownloadLock(videoDownloadLockKey(directory, parsedLink)) {
                semaphoreFor(config).withPermit {
                    val result = withTimeoutOrNull(secondsToMillis(config.timeoutSeconds, minimumMillis = 1)) {
                        downloader.downloadVideoLink(request)
                    } ?: run {
                        linkParseLogger.warn {
                            "链接视频下载超时，已回退预览：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，timeoutSeconds=${config.timeoutSeconds}"
                        }
                        return@withPermit VideoDownloadOutcome.NotSent(
                            videoFailurePrompt(
                                config,
                                preview,
                                parsedLink,
                                "下载超时，限制 ${config.timeoutSeconds.formatSeconds()}",
                            ),
                        )
                    }
                    if (config.maxFileBytes > 0 && result.fileSizeBytes > config.maxFileBytes) {
                        linkParseLogger.warn {
                            "链接视频下载结果超出大小限制，已回退预览：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，size=${result.fileSizeBytes}，max=${config.maxFileBytes}"
                        }
                        return@withPermit VideoDownloadOutcome.NotSent(
                            videoFailurePrompt(
                                config,
                                preview,
                                parsedLink,
                                "文件大小 ${result.fileSizeBytes.formatMegabytes()} 超过限制 ${config.maxFileBytes.formatMegabytes()}",
                            ),
                        )
                    }
                    linkParseLogger.info {
                        "链接视频下载完成：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，size=${result.fileSizeBytes}"
                    }
                    VideoDownloadOutcome.Downloaded(result)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            linkParseLogger.warn(e) {
                "链接视频下载失败，已回退预览：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，原因=${e.message ?: "未知错误"}"
            }
            VideoDownloadOutcome.NotSent(e.toVideoDownloadFeedback(config, preview, parsedLink))
        }
    }

    private fun videoDownloadPlan(
        preview: LinkPreview,
        parsedLink: ParsedLink,
    ): VideoDownloadPlan? {
        val config = configProvider().linkParsing.videoDownload
        if (parsedLink.kind != LinkKinds.VIDEO || preview.kind != LinkKinds.VIDEO) return null

        val duration = preview.durationSeconds
        if (duration == null) {
            return VideoDownloadPlan.NotSent(
                videoFailurePrompt(config, preview, parsedLink, "视频时长未知"),
            )
        }
        if (config.maxDurationSeconds > 0 && duration > config.maxDurationSeconds) {
            return VideoDownloadPlan.NotSent(
                videoFailurePrompt(
                    config,
                    preview,
                    parsedLink,
                    "视频时长 ${duration.formatDuration()} 超过限制 ${config.maxDurationSeconds.formatDuration()}",
                ),
            )
        }
        val downloader = videoDownloadersProvider()
            .firstOrNull { it.platformId == parsedLink.platformId }
        if (downloader == null) {
            return VideoDownloadPlan.NotSent(
                videoFailurePrompt(config, preview, parsedLink, "当前没有可用的视频下载器"),
            )
        }
        return VideoDownloadPlan.Download(
            downloadingPrompt = videoPrompt(config.prompts.downloading, preview, parsedLink),
            downloader = downloader,
        )
    }

    private suspend fun renderVideoFallbackBatches(
        templatePlan: LinkPreviewTemplatePlan,
        preview: LinkPreview,
        parsedLink: ParsedLink,
    ): List<MessageBatch> {
        return runCatching {
            templateRenderer.renderPlanVideoFallback(templatePlan, preview)
        }.getOrElse { error ->
            linkParseLogger.warn(error) {
                "链接视频模板回退渲染失败，跳过视频消息：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
            }
            emptyList()
        }
    }

    private suspend fun forwardMessage(
        batches: List<MessageBatch>,
        parsedLink: ParsedLink,
        context: CommandContext,
        correlationId: String?,
    ): LinkParseItemResult {
        if (batches.isEmpty()) {
            return LinkParseItemResult.Failed(parsedLink, "链接解析结果为空")
        }

        val subscriber = SubscriberRepository.ensure(
            address = context.target,
            name = context.chatId,
        )
        val result = outboundMessageService.publish(
            OutboundMessagePublishRequest(
                sourcePlugin = LINK_PARSE_EVENT_SOURCE,
                messageId = buildLinkParseMessageId(parsedLink),
                targets = listOf(subscriber.address),
                batches = batches,
                renderVariant = LINK_PARSE_EVENT_LABEL,
                correlationId = correlationId?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
        return LinkParseItemResult.Forwarded(
            parsedLink = parsedLink,
            subscriber = subscriber,
            deliveryCount = result.newDeliveries.size,
        )
    }

    private suspend fun sendVideoPrompt(
        message: String,
        parsedLink: ParsedLink,
        context: CommandContext,
        inReplyTo: String?,
        correlationId: String?,
    ): VideoPromptResult {
        val text = message.trim().takeIf { it.isNotBlank() } ?: return VideoPromptResult()
        if (!inReplyTo.isNullOrBlank()) {
            val receipt = progressMessenger.send(context, inReplyTo, correlationId, text)
            return VideoPromptResult(receipt = receipt)
        }
        val feedback = forwardMessage(
            listOf(MessageBatch(listOf(MessageContent.Text(text)))),
            parsedLink,
            context,
            correlationId,
        )
        return VideoPromptResult(deliveryCount = (feedback as? LinkParseItemResult.Forwarded)?.deliveryCount ?: 0)
    }

    private suspend fun videoPromptOnlyResult(
        prompt: VideoPromptResult,
        parsedLink: ParsedLink,
        context: CommandContext,
    ): LinkParseItemResult.Forwarded? {
        if (!prompt.hasOutput) return null
        return forwardedForContext(parsedLink, context, prompt.deliveryCount)
    }

    private suspend fun forwardedForContext(
        parsedLink: ParsedLink,
        context: CommandContext,
        deliveryCount: Int,
    ): LinkParseItemResult.Forwarded {
        val subscriber = SubscriberRepository.ensure(
            address = context.target,
            name = context.chatId,
        )
        return LinkParseItemResult.Forwarded(
            parsedLink = parsedLink,
            subscriber = subscriber,
            deliveryCount = deliveryCount,
        )
    }

    private suspend fun recallVideoPrompt(receipt: LinkParseProgressReceipt?) {
        if (receipt == null) return
        if (!configProvider().linkParsing.progressReply.recallOnComplete) return
        progressMessenger.recall(receipt)
    }

    private fun LinkParseItemResult.Forwarded.withAdditionalDeliveries(count: Int): LinkParseItemResult.Forwarded {
        return if (count > 0) copy(deliveryCount = deliveryCount + count) else this
    }

    private fun LinkParseItemResult.failureReason(): String {
        return when (this) {
            is LinkParseItemResult.Failed -> reason
            is LinkParseItemResult.Duplicate -> "重复链接"
            is LinkParseItemResult.Forwarded -> "视频消息提交失败"
        }
    }

    private fun dedupeKey(context: CommandContext, parsedLink: ParsedLink): String {
        return listOf(
            context.target.stableValue(),
            parsedLink.platformId.value,
            parsedLink.kind,
            parsedLink.targetId,
        ).joinToString(":")
    }

    private fun buildLinkParseMessageId(parsedLink: ParsedLink): String {
        val safeTarget = parsedLink.targetId
            .replace(Regex("[^a-zA-Z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "target" }
        return listOf(
            LINK_PARSE_EVENT_LABEL,
            parsedLink.platformId.value,
            parsedLink.kind,
            safeTarget,
            System.currentTimeMillis().toString(),
            UUID.randomUUID().toString(),
        ).joinToString(":")
    }

    private fun semaphoreFor(config: LinkVideoDownloadConfig): Semaphore {
        val permits = config.maxConcurrentDownloads.coerceAtLeast(1)
        return videoDownloadSemaphores.getOrPut(permits) { Semaphore(permits) }
    }

    private suspend fun <T> withVideoDownloadLock(key: String, block: suspend () -> T): T {
        val lock = videoDownloadLocks.compute(key) { _, existing ->
            (existing ?: VideoDownloadLock()).also { it.references.incrementAndGet() }
        }!!
        return try {
            lock.mutex.withLock {
                block()
            }
        } finally {
            videoDownloadLocks.computeIfPresent(key) { _, existing ->
                if (existing !== lock) {
                    existing
                } else if (existing.references.decrementAndGet() <= 0) {
                    null
                } else {
                    existing
                }
            }
        }
    }

    private fun videoDownloadLockKey(directory: File, parsedLink: ParsedLink): String {
        return listOf(
            directory.normalizedPath(),
            parsedLink.platformId.value,
            parsedLink.kind,
            parsedLink.targetId,
        ).joinToString("\u0000")
    }

    private fun videoCacheDirectory(config: LinkVideoDownloadConfig, platformId: String): File {
        return File(config.cacheRoot).resolve(platformId)
    }

    private fun File.normalizedPath(): String {
        return runCatching { canonicalFile.absolutePath }
            .getOrElse { absoluteFile.toPath().normalize().toString() }
    }

    private fun resolveFfmpegPath(config: LinkVideoDownloadConfig): String {
        return config.ffmpegPath.trim().takeIf { it.isNotBlank() }
            ?: findProjectFfmpeg()?.absolutePath.orEmpty()
    }

    private fun findProjectFfmpeg(): File? {
        return projectRootProvider().absoluteFile
            .ancestorRoots(maxDepth = 4)
            .firstNotNullOfOrNull(::findFfmpegInRoot)
    }

    private fun findFfmpegInRoot(root: File): File? {
        val names = if (isWindows()) {
            listOf("ffmpeg.exe", "ffmpeg")
        } else {
            listOf("ffmpeg", "ffmpeg.exe")
        }
        val fixedCandidates = buildList {
            names.forEach { name -> add(root.resolve(name)) }
            listOf("bin", "tools", "ffmpeg", "ffmpeg/bin").forEach { directory ->
                names.forEach { name -> add(root.resolve(directory).resolve(name)) }
            }
        }
        fixedCandidates.firstOrNull { it.isFile }?.let { return it }

        return root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.startsWith("ffmpeg", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .flatMap { directory ->
                names.asSequence()
                    .flatMap { name -> sequenceOf(directory.resolve("bin").resolve(name), directory.resolve(name)) }
            }
            .firstOrNull { it.isFile }
    }

    private fun File.ancestorRoots(maxDepth: Int): Sequence<File> {
        return generateSequence(absoluteFile.toPath().normalize().toFile()) { current ->
            current.parentFile?.takeIf { it != current }
        }.take(maxDepth + 1)
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("windows", ignoreCase = true)
    }

    private fun Long.formatDuration(): String {
        val total = coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return buildList {
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (seconds > 0 || isEmpty()) add("${seconds}s")
        }.joinToString(" ")
    }

    private fun Double.formatSeconds(): String {
        val rounded = roundToLong()
        return if (kotlin.math.abs(this - rounded.toDouble()) < 0.001) {
            "${rounded}s"
        } else {
            "%.1fs".format(this).trimEnd('0').trimEnd('.')
        }
    }

    private fun Long.formatMegabytes(): String {
        val megabytes = this / (1024.0 * 1024.0)
        return if (megabytes >= 10.0) {
            "%.0f MB".format(megabytes)
        } else {
            "%.1f MB".format(megabytes).replace(".0 MB", " MB")
        }
    }

    private fun videoPrompt(
        template: String,
        preview: LinkPreview,
        parsedLink: ParsedLink,
        values: Map<String, String> = emptyMap(),
    ): String {
        val replacements = linkedMapOf(
            "id" to parsedLink.targetId,
            "title" to preview.title.ifBlank { parsedLink.targetId },
            "link" to preview.url.ifBlank { parsedLink.normalizedUrl },
        )
        replacements.putAll(values)
        return replacements.entries.fold(template) { current, (key, value) ->
            current.replace("{$key}", value)
        }.trim()
    }

    private fun videoFailurePrompt(
        config: LinkVideoDownloadConfig,
        preview: LinkPreview,
        parsedLink: ParsedLink,
        reason: String,
    ): String {
        return videoPrompt(
            config.prompts.failed,
            preview,
            parsedLink,
            mapOf("reason" to reason.normalizedVideoReason()),
        )
    }

    private fun Throwable.toVideoDownloadFeedback(
        config: LinkVideoDownloadConfig,
        preview: LinkPreview,
        parsedLink: ParsedLink,
    ): String {
        val reason = message?.trim()?.takeIf { it.isNotBlank() } ?: "下载失败，原因未知"
        val isSizeExceeded = reason.contains("maxBytes", ignoreCase = true) ||
            reason.contains("大小") ||
            reason.contains("size", ignoreCase = true)
        val normalized = when {
            isSizeExceeded && config.maxFileBytes > 0 -> {
                "文件超过大小限制 ${config.maxFileBytes.formatMegabytes()}"
            }
            else -> reason
        }
        return videoFailurePrompt(config, preview, parsedLink, normalized)
    }

    private fun String.normalizedVideoReason(): String {
        return trim()
            .ifBlank { "原因未知" }
            .trimEnd('。', '.', '！', '!', '；', ';')
    }

    private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
        if (seconds <= 0.0 && minimumMillis <= 0) return 0
        return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
    }

    private data class ParsedLinkCandidate(
        val resolver: LinkResolver,
        val parsedLink: ParsedLink,
    )

    private sealed interface VideoDownloadPlan {
        data class Download(
            val downloadingPrompt: String,
            val downloader: LinkVideoDownloader,
        ) : VideoDownloadPlan

        data class NotSent(val message: String) : VideoDownloadPlan
    }

    private data class VideoPromptResult(
        val receipt: LinkParseProgressReceipt? = null,
        val deliveryCount: Int = 0,
    ) {
        val hasOutput: Boolean
            get() = receipt != null || deliveryCount > 0
    }

    private data class VideoDownloadLock(
        val mutex: Mutex = Mutex(),
        val references: AtomicInteger = AtomicInteger(),
    )

    private sealed interface VideoDownloadOutcome {
        data class Downloaded(val result: LinkVideoDownloadResult) : VideoDownloadOutcome

        data class NotSent(val message: String) : VideoDownloadOutcome
    }
}

internal data class LinkParseBatchResult(
    val items: List<LinkParseItemResult>,
    val disabledReason: String? = null,
) {
    val forwarded: List<LinkParseItemResult.Forwarded>
        get() = items.filterIsInstance<LinkParseItemResult.Forwarded>()

    val failures: List<LinkParseItemResult.Failed>
        get() = items.filterIsInstance<LinkParseItemResult.Failed>()

    val duplicates: List<LinkParseItemResult.Duplicate>
        get() = items.filterIsInstance<LinkParseItemResult.Duplicate>()

    val hasSupportedLinks: Boolean
        get() = items.isNotEmpty()

    val hasForwarded: Boolean
        get() = forwarded.isNotEmpty()

    val failureSummary: String
        get() = failures.joinToString("；") { failure ->
            "${failure.parsedLink.platformId.value}:${failure.parsedLink.kind}:${failure.parsedLink.targetId} ${failure.reason}"
        }

    companion object {
        fun disabled(): LinkParseBatchResult = LinkParseBatchResult(
            items = emptyList(),
            disabledReason = "链接解析已禁用",
        )

        fun failed(reason: String): LinkParseBatchResult = LinkParseBatchResult(
            items = emptyList(),
            disabledReason = reason,
        )

        fun noSupportedLink(): LinkParseBatchResult = LinkParseBatchResult(emptyList())
    }
}

internal sealed interface LinkParseItemResult {
    val parsedLink: ParsedLink

    data class Forwarded(
        override val parsedLink: ParsedLink,
        val subscriber: Subscriber,
        val deliveryCount: Int,
    ) : LinkParseItemResult

    data class Failed(
        override val parsedLink: ParsedLink,
        val reason: String,
    ) : LinkParseItemResult

    data class Duplicate(
        override val parsedLink: ParsedLink,
    ) : LinkParseItemResult
}

internal class LinkParseDedupe {
    private val expiresByKey: MutableMap<String, Long> = ConcurrentHashMap()

    fun markIfNew(key: String, ttlMs: Long): Boolean {
        val now = System.currentTimeMillis()
        purgeExpired(now)

        val expiresAt = expiresByKey[key]
        if (expiresAt != null && expiresAt > now) return false

        expiresByKey[key] = now + ttlMs
        return true
    }

    private fun purgeExpired(now: Long) {
        expiresByKey.entries.removeIf { it.value <= now }
    }
}

internal object LinkUrlExtractor {
    private val urlRegex: Regex = Regex("""https?://[^\s<>"']+""")
    private val trailingPunctuation: CharArray = charArrayOf(
        '.',
        ',',
        ';',
        ':',
        '!',
        '?',
        ')',
        ']',
        '}',
        '>',
        '。',
        '，',
        '；',
        '：',
        '！',
        '？',
        '）',
        '】',
        '》',
    )

    fun extract(text: String): List<String> {
        return urlRegex.findAll(text)
            .map { it.value.trimEnd(*trailingPunctuation) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }
}
