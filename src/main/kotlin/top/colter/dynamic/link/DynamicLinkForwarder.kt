package top.colter.dynamic.link

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.SourceUpdateEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.link.DynamicLinkResolution
import top.colter.dynamic.core.link.DynamicLinkResolver
import top.colter.dynamic.core.link.ParsedDynamicLink
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository

internal const val LINK_PARSE_EVENT_LABEL: String = "link-parse"
internal const val LINK_PARSE_EVENT_SOURCE: String = "main-link-parser"

public class DynamicLinkForwarder(
    private val resolversProvider: () -> List<DynamicLinkResolver>,
) {
    internal suspend fun forwardFirst(
        text: String,
        context: CommandContext,
        maxLinks: Int = 1,
        dedupe: DynamicLinkDedupe? = null,
        dedupeTtlMs: Long = 0,
    ): DynamicLinkForwardResult {
        if (maxLinks <= 0) return DynamicLinkForwardResult.Failed("link parsing is disabled")

        val urls = DynamicUrlExtractor.extract(text)
        if (urls.isEmpty()) return DynamicLinkForwardResult.NoSupportedLink

        val resolvers = resolversProvider()
        if (resolvers.isEmpty()) return DynamicLinkForwardResult.Failed("no link resolver plugins are active")

        urls.forEach { url ->
            resolvers.forEach { resolver ->
                val parsedLink = runCatching { resolver.parseDynamicLink(url) }
                    .getOrElse { return DynamicLinkForwardResult.Failed(it.message ?: "failed to parse dynamic link") }
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

    private fun forward(
        update: SourceUpdate,
        parsedLink: ParsedDynamicLink,
        context: CommandContext,
    ): DynamicLinkForwardResult {
        val sourcePublisher = update.publisher
        if (sourcePublisher.externalId.isBlank()) {
            return DynamicLinkForwardResult.Failed("dynamic publisher id is missing")
        }

        val publisher = PublisherRepository.upsertProfile(sourcePublisher.toProfile()).value
        val subscriber = SubscriberRepository.ensure(
            address = TargetAddress.of(
                platformId = context.platform,
                kind = context.chatType.toTargetKind(),
                externalId = context.chatId,
            ),
            name = context.chatId,
        )
        val normalizedUpdate = update.copy(publisher = publisher.toSnapshot())

        SourceUpdateEvent(
            source = LINK_PARSE_EVENT_SOURCE,
            targetOverride = subscriber,
            label = LINK_PARSE_EVENT_LABEL,
            update = normalizedUpdate,
        ).broadcast()

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
            parsedLink.platformId,
            parsedLink.updateId,
        ).joinToString(":")
    }

    private fun ChatType.toTargetKind(): TargetKind {
        return when (this) {
            ChatType.GROUP -> TargetKind.GROUP
            ChatType.PRIVATE -> TargetKind.USER
            ChatType.CHANNEL -> TargetKind.CHANNEL
        }
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
