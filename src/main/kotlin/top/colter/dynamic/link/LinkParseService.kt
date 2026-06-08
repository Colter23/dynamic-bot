package top.colter.dynamic.link

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.LinkVideoDownloadConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PublisherInfo
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
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.DefaultLinkPreviewRenderer
import top.colter.dynamic.draw.LinkPreviewRenderer
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository

internal const val LINK_PARSE_EVENT_LABEL: String = "link-parse"
internal const val LINK_PARSE_EVENT_SOURCE: String = "main-link-parser"

private val linkParseLogger = loggerFor<LinkParseService>()

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
    private val backgroundScope: CoroutineScope? = null,
    private val projectRootProvider: () -> File = { File(System.getProperty("user.dir")) },
) {
    private val videoDownloadSemaphores: MutableMap<Int, Semaphore> = ConcurrentHashMap()
    private val templateRenderer: LinkPreviewTemplateRenderer = LinkPreviewTemplateRenderer(previewRenderer)

    internal suspend fun parseAndDispatch(
        text: String,
        context: CommandContext,
        maxLinks: Int = 1,
        dedupe: LinkParseDedupe? = null,
        dedupeTtlMs: Long = 0,
        onForwardingStarted: suspend (ParsedLink) -> Unit = {},
    ): LinkParseBatchResult {
        if (maxLinks <= 0) return LinkParseBatchResult.disabled()

        val urls = LinkUrlExtractor.extract(text)
        if (urls.isEmpty()) return LinkParseBatchResult.noSupportedLink()

        val resolvers = resolversProvider()
        if (resolvers.isEmpty()) {
            return LinkParseBatchResult.failed("没有可用的链接解析插件")
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
                    is LinkResolution.Dynamic -> forwardDynamic(resolution.update, parsedLink, context)
                    is LinkResolution.Preview -> forwardPreview(resolution.preview, parsedLink, context)
                    is LinkResolution.Message -> forwardMessage(resolution.batches, parsedLink, context)
                    is LinkResolution.Failed -> LinkParseItemResult.Failed(parsedLink, resolution.reason)
                }
            }.getOrElse { error ->
                linkParseLogger.warn(error) {
                    "链接解析处理失败：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
                }
                LinkParseItemResult.Failed(parsedLink, error.message ?: "链接解析处理失败")
            }
        }

        return LinkParseBatchResult(results)
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
        val incoming = update.publisher
        val stored = PublisherRepository.findByKey(incoming.key)
        if (stored != null) {
            return update.copy(publisher = mergePublisherInfo(incoming, stored.toInfo()))
        }
        if (!incoming.needsLookup()) return update

        val plugin = publisherLookupResolver(incoming.platformId.value) ?: return update
        val fetched = runCatching { plugin.fetchPublisherInfo(incoming.externalId) }
            .onFailure {
                linkParseLogger.debug(it) {
                    "链接解析发布者资料补全失败：platform=${incoming.platformId.value}，publisher=${incoming.externalId}"
                }
            }
            .getOrNull()
            ?: return update
        return update.copy(publisher = mergePublisherInfo(incoming, fetched))
    }

    private suspend fun forwardPreview(
        preview: LinkPreview,
        parsedLink: ParsedLink,
        context: CommandContext,
    ): LinkParseItemResult {
        val videoPlan = videoDownloadPlan(preview, parsedLink)
        val previewBatches = runCatching {
            templateRenderer.renderPreview(configProvider().linkParsing.templates.forKind(preview.kind), preview)
                .takeIf { it.isNotEmpty() }
                ?: listOf(MessageBatch(listOf(MessageContent.Text(preview.fallbackText()))))
        }.getOrElse { error ->
            linkParseLogger.warn(error) {
                "链接卡片绘图失败，回退为文本：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
            }
            listOf(MessageBatch(listOf(MessageContent.Text(preview.fallbackText()))))
        }.withAppendedText(videoPlan?.hint)
        val previewResult = forwardMessage(previewBatches, parsedLink, context)
        if (previewResult !is LinkParseItemResult.Forwarded) return previewResult

        if (videoPlan?.shouldDownload != true || videoPlan.downloader == null) return previewResult
        val scope = backgroundScope
        if (scope != null) {
            launchVideoDownloadFollowUp(scope, preview, parsedLink, context, videoPlan.downloader, previewResult)
            return previewResult
        }

        return forwardVideoAfterDownload(preview, parsedLink, context, videoPlan.downloader, previewResult)
    }

    private fun launchVideoDownloadFollowUp(
        scope: CoroutineScope,
        preview: LinkPreview,
        parsedLink: ParsedLink,
        context: CommandContext,
        downloader: LinkVideoDownloader,
        previewResult: LinkParseItemResult.Forwarded,
    ) {
        scope.launch {
            try {
                forwardVideoAfterDownload(preview, parsedLink, context, downloader, previewResult)
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
        preview: LinkPreview,
        parsedLink: ParsedLink,
        context: CommandContext,
        downloader: LinkVideoDownloader,
        previewResult: LinkParseItemResult.Forwarded,
    ): LinkParseItemResult.Forwarded {
        val video = when (val outcome = downloadVideo(preview, parsedLink, downloader)) {
            is VideoDownloadOutcome.Downloaded -> outcome.result
            is VideoDownloadOutcome.NotSent -> {
                val message = outcome.message.trim()
                if (message.isBlank()) return previewResult
                val feedback = forwardMessage(
                    listOf(MessageBatch(listOf(MessageContent.Text(message)))),
                    parsedLink,
                    context,
                )
                return if (feedback is LinkParseItemResult.Forwarded) {
                    previewResult.copy(deliveryCount = previewResult.deliveryCount + feedback.deliveryCount)
                } else {
                    previewResult
                }
            }
        }
        val videoBatches = runCatching {
            templateRenderer.renderVideoFile(configProvider().linkParsing.templates.videoFile, preview, video)
                .takeIf { it.isNotEmpty() }
                ?: listOf(MessageBatch(listOf(MessageContent.Text(preview.fallbackText()))))
        }.getOrElse { error ->
            linkParseLogger.warn(error) {
                "链接视频模板渲染失败，跳过视频消息：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
            }
            return previewResult
        }
        val videoResult = forwardMessage(videoBatches, parsedLink, context)
        return if (videoResult is LinkParseItemResult.Forwarded) {
            previewResult.copy(deliveryCount = previewResult.deliveryCount + videoResult.deliveryCount)
        } else {
            previewResult
        }
    }

    private suspend fun downloadVideo(
        preview: LinkPreview,
        parsedLink: ParsedLink,
        downloader: LinkVideoDownloader,
    ): VideoDownloadOutcome {
        val config = configProvider().linkParsing.videoDownload
        if (!config.enabled) {
            return VideoDownloadOutcome.NotSent(
                videoPrompt(
                    config.prompts.failed,
                    preview,
                    parsedLink,
                    mapOf("reason" to "视频下载功能未开启"),
                ),
            )
        }
        if (parsedLink.kind != LinkKinds.VIDEO || preview.kind != LinkKinds.VIDEO) {
            return VideoDownloadOutcome.NotSent(
                videoPrompt(
                    config.prompts.failed,
                    preview,
                    parsedLink,
                    mapOf("reason" to "当前链接不是视频"),
                ),
            )
        }

        val duration = preview.durationSeconds
        if (duration == null) {
            linkParseLogger.info {
                "链接视频下载跳过：时长未知，platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}"
            }
            return VideoDownloadOutcome.NotSent(
                videoPrompt(config.prompts.durationUnknown, preview, parsedLink),
            )
        }
        if (config.maxDurationSeconds > 0 && duration > config.maxDurationSeconds) {
            linkParseLogger.info {
                "链接视频下载跳过：时长超限，platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，duration=$duration，max=${config.maxDurationSeconds}"
            }
            return VideoDownloadOutcome.NotSent(
                videoPrompt(
                    config.prompts.durationTooLong,
                    preview,
                    parsedLink,
                    mapOf(
                        "duration" to duration.formatDuration(),
                        "maxDuration" to config.maxDurationSeconds.formatDuration(),
                    ),
                ),
            )
        }

        return try {
            semaphoreFor(config).withPermit {
                val result = withTimeoutOrNull(secondsToMillis(config.timeoutSeconds, minimumMillis = 1)) {
                    downloader.downloadVideoLink(
                        LinkVideoDownloadRequest(
                            parsedLink = parsedLink,
                            directory = videoCacheDirectory(config, parsedLink.platformId.value),
                            maxBytes = config.maxFileBytes,
                            quality = config.quality,
                            ffmpegPath = resolveFfmpegPath(config),
                        ),
                    )
                } ?: run {
                    linkParseLogger.warn {
                        "链接视频下载超时，已回退预览：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，timeoutSeconds=${config.timeoutSeconds}"
                    }
                    return VideoDownloadOutcome.NotSent(
                        videoPrompt(
                            config.prompts.timeout,
                            preview,
                            parsedLink,
                            mapOf("timeout" to config.timeoutSeconds.formatSeconds()),
                        ),
                    )
                }
                if (result.fileSizeBytes > config.maxFileBytes) {
                    linkParseLogger.warn {
                        "链接视频下载结果超出大小限制，已回退预览：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，size=${result.fileSizeBytes}，max=${config.maxFileBytes}"
                    }
                    return VideoDownloadOutcome.NotSent(
                        videoPrompt(
                            config.prompts.fileTooLarge,
                            preview,
                            parsedLink,
                            mapOf(
                                "size" to result.fileSizeBytes.formatMegabytes(),
                                "maxSize" to config.maxFileBytes.formatMegabytes(),
                            ),
                        ),
                    )
                }
                linkParseLogger.info {
                    "链接视频下载完成：platform=${parsedLink.platformId.value}，id=${parsedLink.targetId}，size=${result.fileSizeBytes}"
                }
                VideoDownloadOutcome.Downloaded(result)
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
        if (!config.enabled) return null
        if (parsedLink.kind != LinkKinds.VIDEO || preview.kind != LinkKinds.VIDEO) return null

        val duration = preview.durationSeconds
        if (duration == null) {
            return VideoDownloadPlan(
                shouldDownload = false,
                hint = videoPrompt(config.prompts.durationUnknown, preview, parsedLink),
            )
        }
        if (config.maxDurationSeconds > 0 && duration > config.maxDurationSeconds) {
            return VideoDownloadPlan(
                shouldDownload = false,
                hint = videoPrompt(
                    config.prompts.durationTooLong,
                    preview,
                    parsedLink,
                    mapOf(
                        "duration" to duration.formatDuration(),
                        "maxDuration" to config.maxDurationSeconds.formatDuration(),
                    ),
                ),
            )
        }
        val downloader = videoDownloadersProvider()
            .firstOrNull { it.platformId == parsedLink.platformId }
        if (downloader == null) {
            return VideoDownloadPlan(
                shouldDownload = false,
                hint = videoPrompt(config.prompts.noDownloader, preview, parsedLink),
            )
        }
        return VideoDownloadPlan(
            shouldDownload = true,
            hint = videoPrompt(config.prompts.downloading, preview, parsedLink),
            downloader = downloader,
        )
    }

    private suspend fun forwardMessage(
        batches: List<MessageBatch>,
        parsedLink: ParsedLink,
        context: CommandContext,
    ): LinkParseItemResult {
        if (batches.isEmpty()) {
            return LinkParseItemResult.Failed(parsedLink, "链接解析结果为空")
        }

        val subscriber = SubscriberRepository.ensure(
            address = context.target,
            name = context.chatId,
        )
        val message = Message(
            id = buildLinkParseMessageId(parsedLink),
            time = System.currentTimeMillis() / 1000,
            sourceUpdateKey = null,
            renderVariant = LINK_PARSE_EVENT_LABEL,
            targets = listOf(subscriber.address),
            batches = batches,
        )
        val enqueue = MessageDeliveryRepository.enqueue(message)
        if (enqueue.newDeliveries.isNotEmpty()) {
            onMessagesQueued()
        }
        return LinkParseItemResult.Forwarded(
            parsedLink = parsedLink,
            subscriber = subscriber,
            deliveryCount = enqueue.newDeliveries.size,
        )
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

    private fun List<MessageBatch>.withAppendedText(text: String?): List<MessageBatch> {
        val value = text?.trim()?.takeIf { it.isNotBlank() } ?: return this
        if (isEmpty()) return listOf(MessageBatch(listOf(MessageContent.Text(value))))
        val result = toMutableList()
        val last = result.last()
        val content = last.content.toMutableList()
        val lastText = content.lastOrNull() as? MessageContent.Text
        if (lastText == null) {
            content += MessageContent.Text(if (content.isEmpty()) value else "\n$value")
        } else {
            content[content.lastIndex] = lastText.copy(
                fallbackText = listOf(lastText.fallbackText, value)
                    .filter { it.isNotBlank() }
                    .joinToString("\n"),
            )
        }
        result[result.lastIndex] = MessageBatch(content)
        return result
    }

    private fun semaphoreFor(config: LinkVideoDownloadConfig): Semaphore {
        val permits = config.maxConcurrentDownloads.coerceAtLeast(1)
        return videoDownloadSemaphores.getOrPut(permits) { Semaphore(permits) }
    }

    private fun videoCacheDirectory(config: LinkVideoDownloadConfig, platformId: String): File {
        return File(config.cacheRoot).resolve(platformId)
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
            "platform" to parsedLink.platformId.value,
            "kind" to parsedLink.kind,
            "id" to parsedLink.targetId,
            "title" to preview.title.ifBlank { parsedLink.targetId },
            "link" to preview.url.ifBlank { parsedLink.normalizedUrl },
            "url" to preview.url.ifBlank { parsedLink.normalizedUrl },
        )
        replacements.putAll(values)
        return replacements.entries.fold(template) { current, (key, value) ->
            current.replace("{$key}", value)
        }.trim()
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
            isSizeExceeded -> {
                "文件超过大小限制 ${config.maxFileBytes.formatMegabytes()}"
            }
            else -> reason
        }.trimEnd('。', '.', '！', '!')
        return if (isSizeExceeded) {
            videoPrompt(
                config.prompts.fileTooLarge,
                preview,
                parsedLink,
                mapOf(
                    "size" to "未知",
                    "maxSize" to config.maxFileBytes.formatMegabytes(),
                    "reason" to normalized,
                ),
            )
        } else {
            videoPrompt(
                config.prompts.failed,
                preview,
                parsedLink,
                mapOf("reason" to normalized),
            )
        }
    }

    private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
        if (seconds <= 0.0 && minimumMillis <= 0) return 0
        return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
    }

    private fun PublisherInfo.needsLookup(): Boolean {
        return name.isBlank() || avatar.uri.isBlank() || banner == null
    }

    private fun mergePublisherInfo(primary: PublisherInfo, fallback: PublisherInfo): PublisherInfo {
        return primary.copy(
            name = primary.name.ifBlank { fallback.name },
            avatarBadgeKey = primary.avatarBadgeKey ?: fallback.avatarBadgeKey,
            avatar = primary.avatar.takeIf { it.uri.isNotBlank() } ?: fallback.avatar,
            banner = primary.banner ?: fallback.banner,
            pendant = primary.pendant ?: fallback.pendant,
        )
    }

    private data class ParsedLinkCandidate(
        val resolver: LinkResolver,
        val parsedLink: ParsedLink,
    )

    private data class VideoDownloadPlan(
        val shouldDownload: Boolean,
        val hint: String,
        val downloader: LinkVideoDownloader? = null,
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
