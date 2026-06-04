package top.colter.dynamic.link

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.MediaKind
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
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository

internal const val LINK_PARSE_EVENT_LABEL: String = "link-parse"
internal const val LINK_PARSE_EVENT_SOURCE: String = "main-link-parser"

private val linkParseLogger = loggerFor<LinkParseService>()

public class LinkParseService(
    private val resolversProvider: () -> List<LinkResolver>,
    private val sourceUpdatePublisher: SourceUpdatePublisher = SourceUpdatePublisher {
        SourceUpdatePublishResult.failed("来源更新发布器未配置")
    },
    private val publisherLookupResolver: (String) -> PublisherLookupPlugin? = { null },
    private val previewRenderer: LinkPreviewRenderer = DefaultLinkPreviewRenderer(),
    private val onMessagesQueued: suspend () -> Unit = {},
) {
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

    private suspend fun parseUrl(url: String, resolvers: List<LinkResolver>): ParsedLinkCandidate? {
        resolvers.forEach { resolver ->
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
        val batches = runCatching {
            val image = previewRenderer.render(preview)
            listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Image(
                            fallbackText = preview.fallbackText(),
                            image = image.copy(kind = MediaKind.IMAGE),
                            altText = preview.title.takeIf { it.isNotBlank() },
                        )
                    )
                )
            )
        }.getOrElse { error ->
            linkParseLogger.warn(error) {
                "链接卡片绘图失败，回退为文本：platform=${parsedLink.platformId.value}，kind=${parsedLink.kind}，target=${parsedLink.targetId}"
            }
            listOf(MessageBatch(listOf(MessageContent.Text(preview.fallbackText()))))
        }
        return forwardMessage(batches, parsedLink, context)
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

    private fun LinkPreview.fallbackText(): String {
        return buildString {
            append(title.ifBlank { url })
            description.trim().takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(it)
            }
            metrics
                .mapNotNull { metric -> metric.display?.takeIf { it.isNotBlank() }?.let { "${metric.key}: $it" } }
                .takeIf { it.isNotEmpty() }
                ?.let {
                    appendLine()
                    append(it.joinToString(" / "))
                }
            url.takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(it)
            }
        }
    }

    private data class ParsedLinkCandidate(
        val resolver: LinkResolver,
        val parsedLink: ParsedLink,
    )
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
