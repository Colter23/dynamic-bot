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
import top.colter.dynamic.core.data.MessageDeliveryPolicy
import top.colter.dynamic.core.data.MessageImportance
import top.colter.dynamic.core.data.MessageRecordPolicy
import top.colter.dynamic.core.data.OutboundMessageKind
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.event.SourceUpdateDeliveryTags
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.LinkVideoDownloadRequest
import top.colter.dynamic.core.link.LinkVideoDownloadResult
import top.colter.dynamic.core.link.LinkVideoDownloader
import top.colter.dynamic.core.link.LinkVideoResource
import top.colter.dynamic.core.event.SourceUpdateDeliveryMode
import top.colter.dynamic.template.TemplateSelector
import top.colter.dynamic.template.TemplateContentKind
import top.colter.dynamic.draw.DefaultDynamicDrawService
import top.colter.dynamic.draw.DynamicDrawService
import top.colter.dynamic.repository.SourceUpdateSnapshotRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.OutboundMessagePublishRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.DefaultLinkPreviewRenderer
import top.colter.dynamic.draw.LinkPreviewRenderer
import top.colter.dynamic.message.OutboundMessageService
import top.colter.dynamic.repository.IncomingMessageAuditRepository
import top.colter.dynamic.repository.IncomingProcessingWriteRequest

internal const val LINK_PARSE_EVENT_LABEL: String = SourceUpdateDeliveryTags.LINK_PREVIEW
internal const val LINK_PARSE_EVENT_SOURCE: String = "main-link-parser"
private const val LINK_RESULT_RETENTION_SECONDS: Long = 7L * 24L * 60L * 60L

private val linkParseLogger = loggerFor<LinkParseService>()

private fun elapsedMillis(startedNanos: Long): Long {
    return ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0)
}

public class LinkParseService(
    private val resolversProvider: () -> List<LinkResolver>,
    private val configProvider: () -> MainDynamicConfig = { MainDynamicConfig() },
    private val videoDownloadersProvider: () -> List<LinkVideoDownloader> = { emptyList() },
    private val publisherLookupResolver: (String) -> PublisherLookupPlugin? = { null },
    private val previewRenderer: LinkPreviewRenderer = DefaultLinkPreviewRenderer(),
    private val dynamicDrawService: DynamicDrawService = DefaultDynamicDrawService(configProvider = configProvider),
    private val onMessagesQueued: suspend () -> Unit = {},
    private val outboundMessageService: OutboundMessageService = OutboundMessageService(
        onMessagesQueued = onMessagesQueued,
    ),
    private val progressMessenger: LinkParseProgressMessenger = NoopLinkParseProgressMessenger,
    private val backgroundScope: CoroutineScope? = null,
    private val projectRootProvider: () -> File = { File(System.getProperty("user.dir")) },
    private val snapshotRecorder: suspend (String, SourceUpdate) -> Unit = { source, update -> SourceUpdateSnapshotRepository.upsert(source, update); Unit },
    private val incomingProcessingRecorder: (IncomingProcessingWriteRequest) -> Boolean = {
        IncomingMessageAuditRepository.recordProcessing(it)
    },
) {
    private val videoDownloadSemaphores: MutableMap<Int, Semaphore> = ConcurrentHashMap()
    private val videoDownloadLocks: MutableMap<String, VideoDownloadLock> = ConcurrentHashMap()
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
    ): LinkParseBatchResult = parseAndDispatch(
        urls = LinkUrlExtractor.extract(text),
        context = context,
        maxLinks = maxLinks,
        dedupe = dedupe,
        dedupeTtlMs = dedupeTtlMs,
        inReplyTo = inReplyTo,
        correlationId = correlationId,
        onForwardingStarted = onForwardingStarted,
    )

    internal suspend fun parseAndDispatch(
        urls: List<String>,
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

        val normalizedUrls = urls.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedUrls.isEmpty()) return recordLinkProcessing(
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

        for (url in normalizedUrls) {
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
                    is LinkResolution.Dynamic -> forwardResolved(resolution, context, inReplyTo, correlationId)
                    is LinkResolution.Preview -> forwardResolved(resolution, context, inReplyTo, correlationId)
                    is LinkResolution.Message -> forwardMessage(resolution.batches, parsedLink, context, correlationId, inReplyTo)
                    is LinkResolution.Failed -> LinkParseItemResult.Failed(parsedLink, resolution.reason)
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
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

    internal fun hasSupportedLinkCandidate(text: String, maxLinks: Int = 1): Boolean =
        hasSupportedLinkCandidate(LinkUrlExtractor.extract(text), maxLinks)

    internal fun hasSupportedLinkCandidate(urls: List<String>, maxLinks: Int = 1): Boolean {
        if (maxLinks <= 0) return false
        val resolvers = resolversProvider()
        if (resolvers.isEmpty()) return false
        return urls.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .any { url -> resolvers.any { resolver -> resolver.safeMatchesLink(url) } }
    }

    private suspend fun parseUrl(url: String, resolvers: List<LinkResolver>): ParsedLinkCandidate? {
        resolvers.forEach { resolver ->
            if (!resolver.safeMatchesLink(url)) return@forEach
            val parsedLink = runCatching { resolver.parseLink(url) }
                .onFailure {
                    if (it is CancellationException) throw it
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

    private suspend fun forwardResolved(
        resolution: LinkResolution,
        context: CommandContext,
        inReplyTo: String?,
        correlationId: String?,
    ): LinkParseItemResult {
        val config = configProvider()
        val parsedLink = resolution.parsedLink
        val update = (resolution as? LinkResolution.Dynamic)?.update?.let { publisherEnricher.enrich(it) }
        val resource = when (resolution) {
            is LinkResolution.Dynamic -> resolution.video
            is LinkResolution.Preview -> resolution.video
            else -> null
        }
        val preview = when (resolution) {
            is LinkResolution.Dynamic -> LinkPreview(
                platformId = parsedLink.platformId, kind = LinkKinds.DYNAMIC, id = update!!.key.externalId,
                url = update.link ?: parsedLink.normalizedUrl, title = "${update.publisher.name} 的动态",
                publisher = update.publisher, durationSeconds = resource?.durationSeconds,
                publishedAtEpochSeconds = update.occurredAtEpochSeconds,
            )
            is LinkResolution.Preview -> publisherEnricher.enrich(resolution.preview)
            else -> error("不支持的模板内容")
        }
        if (update != null && update.publisher.externalId.isBlank()) return LinkParseItemResult.Failed(parsedLink, "动态发布者 ID 缺失")
        val selected = TemplateSelector.select(config.templates, parsedLink.platformId.value,
            if (update != null) TemplateContentKind.of(update) else TemplateContentKind.ofLink(preview.kind),
            SourceUpdateDeliveryMode.LINK_PREVIEW)
        val templateRenderer = LinkPreviewTemplateRenderer(previewRenderer.withConfig(config))
        val drawService = dynamicDrawService.withConfig(config)
        val plan = templateRenderer.plan(selected.template)
        val session = templateRenderer.session(preview, update) {
            try {
                drawService.render(update!!, PublisherRepository.findByKey(update.publisher.key))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                linkParseLogger.warn { "链接动态绘图失败，回退为文本：update=${update!!.key.stableValue()}" }
                null
            }
        }
        update?.let { snapshotRecorder(LINK_PARSE_EVENT_SOURCE, it) }
        var count = 0
        var contentCount = 0
        suspend fun publish(batches: List<MessageBatch>): LinkParseItemResult.Failed? {
            if (batches.isEmpty()) return null
            return when (val result = forwardMessage(batches, parsedLink, context, correlationId, inReplyTo, update?.key)) {
                is LinkParseItemResult.Forwarded -> { count += result.deliveryCount; contentCount++; null }
                is LinkParseItemResult.Failed -> result
                else -> LinkParseItemResult.Failed(parsedLink, "链接结果发送失败")
            }
        }
        publish(session.render(plan.immediateSteps))?.let { return it }
        suspend fun finish(): LinkParseItemResult {
            if (plan.requiresVideo) {
                val downloadPreview = preview.copy(kind = LinkKinds.VIDEO, durationSeconds = resource?.durationSeconds ?: preview.durationSeconds)
                val downloadPlan = if (resource == null) VideoDownloadPlan.NotSent(
                    if (preview.kind == LinkKinds.VIDEO) videoFailurePrompt(config.linkParsing.videoDownload, downloadPreview, parsedLink, "当前内容未提供可下载视频") else "")
                    else videoDownloadPlan(downloadPreview, parsedLink, config.linkParsing.videoDownload)
                var prompt: VideoPromptResult? = null
                try {
                    val outcome = when (downloadPlan) {
                        is VideoDownloadPlan.Download -> {
                            prompt = sendVideoPrompt(downloadPlan.downloadingPrompt, parsedLink, context, inReplyTo, correlationId)
                            count += prompt.deliveryCount
                            downloadVideo(downloadPreview, parsedLink, downloadPlan.downloader, config.linkParsing.videoDownload, resource)
                        }
                        is VideoDownloadPlan.NotSent -> VideoDownloadOutcome.NotSent(downloadPlan.message)
                        null -> VideoDownloadOutcome.NotSent("当前内容不支持视频下载")
                    }
                    val video = (outcome as? VideoDownloadOutcome.Downloaded)?.result
                    if (outcome is VideoDownloadOutcome.NotSent) {
                        count += sendVideoPrompt(outcome.message, parsedLink, context, inReplyTo, correlationId).deliveryCount
                    }
                    publish(session.render(plan.videoSteps, video))?.let { failure ->
                        if (video != null) sendVideoPrompt(
                            videoFailurePrompt(config.linkParsing.videoDownload, downloadPreview, parsedLink, "发送未完成，请查看消息记录后再决定是否重发"),
                            parsedLink, context, inReplyTo, correlationId,
                        )
                        return failure
                    }
                } finally {
                    if (config.linkParsing.progressReply.recallOnComplete) prompt?.receipt?.let { progressMessenger.recall(it) }
                }
            }
            if (contentCount == 0) {
                val fallback = if (update == null) preview.fallbackText() else
                    top.colter.dynamic.listener.PushTemplateRenderer().render("{name}\\n{content}\\n{link}", update, null)
                        .flatMap { it.content }.filterIsInstance<MessageContent.Text>().joinToString("\n") { it.fallbackText }
                publish(listOf(MessageBatch(listOf(MessageContent.Text(fallback)))))?.let { return it }
            }
            return LinkParseItemResult.Forwarded(parsedLink, context.target, count)
        }
        if (plan.requiresVideo && !plan.requiresVideoInForward && backgroundScope != null) {
            backgroundScope.launch {
                try {
                    val result = finish()
                    if (result is LinkParseItemResult.Failed) linkParseLogger.warn { "链接后续发送失败：${result.reason}" }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { linkParseLogger.warn { "链接后续处理失败：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}" } }
            }
            return LinkParseItemResult.Forwarded(parsedLink, context.target, count)
        }
        return finish()
    }

    private suspend fun downloadVideo(
        preview: LinkPreview,
        parsedLink: ParsedLink,
        downloader: LinkVideoDownloader,
        config: LinkVideoDownloadConfig = configProvider().linkParsing.videoDownload,
        resource: LinkVideoResource? = null,
    ): VideoDownloadOutcome {
        if (preview.kind != LinkKinds.VIDEO) {
            return VideoDownloadOutcome.NotSent(
                videoFailurePrompt(config, preview, parsedLink, "当前链接不是视频"),
            )
        }

        val duration = preview.durationSeconds
        // 与 videoDownloadPlan 保持同一规则：仅在设置了时长上限且时长未知时拒绝。
        if (duration == null && config.maxDurationSeconds > 0) {
            linkParseLogger.info {
                "链接视频下载跳过：时长未知且设置了时长上限，platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，max=${config.maxDurationSeconds}"
            }
            return VideoDownloadOutcome.NotSent(
                videoFailurePrompt(
                    config,
                    preview,
                    parsedLink,
                    "视频时长未知；如需下载，可在后台把「最大视频时长」设为 0 或调大",
                ),
            )
        }
        if (duration != null && config.maxDurationSeconds > 0 && duration > config.maxDurationSeconds) {
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
            resource = resource,
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
            linkParseLogger.warn {
                "链接视频下载失败，已回退预览：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，类型=${e::class.simpleName}"
            }
            VideoDownloadOutcome.NotSent(e.toVideoDownloadFeedback(config, preview, parsedLink))
        }
    }

    private fun videoDownloadPlan(
        preview: LinkPreview,
        parsedLink: ParsedLink,
        config: LinkVideoDownloadConfig = configProvider().linkParsing.videoDownload,
    ): VideoDownloadPlan? {
        if (preview.kind != LinkKinds.VIDEO) return null

        val duration = preview.durationSeconds
        // 时长未知时：只有明确设置了时长上限才拒绝（无法判断是否超限）。
        // 上限为 0 表示用户不要时长限制，此时允许下载，大小由 maxFileBytes 与下载器自身校验兜底。
        if (duration == null && config.maxDurationSeconds > 0) {
            return VideoDownloadPlan.NotSent(
                videoFailurePrompt(
                    config,
                    preview,
                    parsedLink,
                    "视频时长未知；如需下载，可在后台把「最大视频时长」设为 0 或调大",
                ),
            )
        }
        if (duration != null && config.maxDurationSeconds > 0 && duration > config.maxDurationSeconds) {
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

    private suspend fun forwardMessage(
        batches: List<MessageBatch>,
        parsedLink: ParsedLink,
        context: CommandContext,
        correlationId: String?,
        inReplyTo: String? = null,
        sourceUpdateKey: top.colter.dynamic.core.data.UpdateKey? = null,
    ): LinkParseItemResult {
        if (batches.isEmpty()) {
            return LinkParseItemResult.Failed(parsedLink, "链接解析结果为空")
        }

        val result = outboundMessageService.publish(
            OutboundMessagePublishRequest(
                sourcePlugin = LINK_PARSE_EVENT_SOURCE,
                messageId = buildLinkParseMessageId(parsedLink),
                sourceUpdateKey = sourceUpdateKey,
                targets = listOf(context.target),
                batches = batches,
                renderVariant = LINK_PARSE_EVENT_LABEL,
                kind = OutboundMessageKind.LINK_RESULT,
                importance = MessageImportance.LOW,
                recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = LINK_RESULT_RETENTION_SECONDS),
                correlationId = correlationId?.trim()?.takeIf { it.isNotBlank() },
                replyToMessageId = inReplyTo,
                deliveryPolicy = MessageDeliveryPolicy(requireActiveTarget = false),
            ),
        )
        // 回执未知不重放当前批次，也不阻断后续独立的视频等步骤；投递记录保留 SEND_UNKNOWN。
        val canContinue = result.sendResults.isNotEmpty() && result.sendResults.all {
            it is MessageSendResult.Sent || it is MessageSendResult.Uncertain
        }
        if (!result.accepted && !canContinue) {
            return LinkParseItemResult.Failed(parsedLink, "链接结果未完整发送，请在消息记录中查看状态")
        }
        if (result.sendResults.any { it is MessageSendResult.Uncertain }) {
            linkParseLogger.warn {
                "链接结果发送状态未知，保留记录并继续后续独立步骤，不自动重发：messageId=${result.message.id}，target=${context.target.stableValue()}"
            }
        }
        return LinkParseItemResult.Forwarded(
            parsedLink = parsedLink,
            target = context.target,
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
        val reason = message?.trim()?.replace(Regex("https?://[^\\s]+"), "[资源地址已隐藏]")
            ?.takeIf { it.isNotBlank() } ?: "下载失败，原因未知"
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
    )

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
        val target: TargetAddress,
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
