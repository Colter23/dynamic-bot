package top.colter.dynamic.link

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.event.EventBus
import top.colter.dynamic.core.event.EventBusSourceUpdatePublisher
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.link.DynamicLinkResolution
import top.colter.dynamic.core.link.DynamicLinkResolver
import top.colter.dynamic.core.link.ParsedDynamicLink
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository

internal const val LINK_PARSE_EVENT_LABEL: String = "link-parse"
internal const val LINK_PARSE_EVENT_SOURCE: String = "main-link-parser"

public class DynamicLinkForwarder(
    private val resolversProvider: () -> List<DynamicLinkResolver>,
    private val sourceUpdatePublisher: SourceUpdatePublisher = EventBusSourceUpdatePublisher(EventBus()),
) {
    public constructor(
        resolversProvider: () -> List<DynamicLinkResolver>,
        eventBus: EventBus,
    ) : this(resolversProvider, EventBusSourceUpdatePublisher(eventBus))

    internal suspend fun forwardFirst(
        text: String,
        context: CommandContext,
        maxLinks: Int = 1,
        dedupe: DynamicLinkDedupe? = null,
        dedupeTtlMs: Long = 0,
    ): DynamicLinkForwardResult {
        if (maxLinks <= 0) return DynamicLinkForwardResult.Failed("链接解析已禁用")

        val urls = DynamicUrlExtractor.extract(text)
        if (urls.isEmpty()) return DynamicLinkForwardResult.NoSupportedLink

        val resolvers = resolversProvider()
        if (resolvers.isEmpty()) return DynamicLinkForwardResult.Failed("没有可用的链接解析插件")

        urls.forEach { url ->
            resolvers.forEach { resolver ->
                val parsedLink = runCatching { resolver.parseDynamicLink(url) }
                    .getOrElse { return DynamicLinkForwardResult.Failed(it.message ?: "动态链接解析失败") }
                    ?: return@forEach

                if (dedupe != null && dedupeTtlMs > 0 && !dedupe.markIfNew(dedupeKey(context, parsedLink), dedupeTtlMs)) {
                    return DynamicLinkForwardResult.Duplicate(parsedLink)
                }

                return when (val resolution = resolver.resolveDynamicLink(parsedLink)) {
                    is DynamicLinkResolution.Success -> forward(resolution.update, parsedLink, context)
                    is DynamicLinkResolution.Failed -> DynamicLinkForwardResult.Failed(resolution.reason)
                }
            }
        }

        return DynamicLinkForwardResult.NoSupportedLink
    }

    private suspend fun forward(
        update: SourceUpdate,
        parsedLink: ParsedDynamicLink,
        context: CommandContext,
    ): DynamicLinkForwardResult {
        val sourcePublisher = update.publisher
        if (sourcePublisher.externalId.isBlank()) {
            return DynamicLinkForwardResult.Failed("动态发布者 ID 缺失")
        }

        val publisher = PublisherRepository.upsertInfo(sourcePublisher).value
        val subscriber = SubscriberRepository.ensure(
            address = context.target,
            name = context.chatId,
        )
        val normalizedUpdate = update.copy(publisher = publisher.toInfo())

        val result = sourceUpdatePublisher.publish(
            SourceUpdatePublishRequest(
            sourcePlugin = LINK_PARSE_EVENT_SOURCE,
            deliveryTarget = subscriber,
            deliveryTag = LINK_PARSE_EVENT_LABEL,
            update = normalizedUpdate,
            ),
        )
        if (!result.accepted) {
            return DynamicLinkForwardResult.Failed(result.message.ifBlank { "动态链接转发失败" })
        }

        return DynamicLinkForwardResult.Forwarded(
            parsedLink = parsedLink,
            update = normalizedUpdate,
            subscriber = subscriber,
        )
    }

    private fun dedupeKey(context: CommandContext, parsedLink: ParsedDynamicLink): String {
        return listOf(
            context.platform,
            context.chatType.name,
            context.chatId,
            parsedLink.platformId.value,
            parsedLink.updateId,
        ).joinToString(":")
    }
}

internal sealed interface DynamicLinkForwardResult {
    data class Forwarded(
        val parsedLink: ParsedDynamicLink,
        val update: SourceUpdate,
        val subscriber: Subscriber,
    ) : DynamicLinkForwardResult

    data class Failed(val reason: String) : DynamicLinkForwardResult

    data class Duplicate(val parsedLink: ParsedDynamicLink) : DynamicLinkForwardResult

    data object NoSupportedLink : DynamicLinkForwardResult
}

internal class DynamicLinkDedupe {
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

private object DynamicUrlExtractor {
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
