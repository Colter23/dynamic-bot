package top.colter.dynamic.link

import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandParser
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.repository.LinkParseTargetConfigRepository
import kotlin.math.roundToLong

internal class DynamicLinkAutoParseListener(
    private val configProvider: () -> MainDynamicConfig,
    private val forwarder: DynamicLinkForwarder,
    private val eventBus: EventBus = EventBus(),
    private val dedupe: DynamicLinkDedupe = DynamicLinkDedupe(),
    private val progressMessenger: LinkParseProgressMessenger = NoopLinkParseProgressMessenger,
) : Listener<CommandEvent> {
    override suspend fun onMessage(event: CommandEvent) {
        val config = configProvider()
        val linkParsing = config.linkParsing
        if (!linkParsing.autoParseEnabled) return
        if (CommandParser.parse(event.rawText, config.command.prefix) != null) return

        val triggerMode = LinkParseTargetConfigRepository.findByAddress(event.context.target)?.triggerMode
            ?: linkParsing.fallbackTriggerMode
        if (!triggerMode.allows(event)) return

        var progressReceipt: LinkParseProgressReceipt? = null
        val result = try {
            forwarder.forwardFirst(
                text = event.rawText,
                context = event.context,
                maxLinks = linkParsing.maxLinksPerMessage,
                dedupe = dedupe,
                dedupeTtlMs = secondsToMillis(linkParsing.autoDedupeTtlSeconds, minimumMillis = 0),
                onForwardingStarted = {
                    if (progressReceipt == null) {
                        progressReceipt = progressMessenger.send(event, linkParsing.progressReply)
                    }
                },
            )
        } finally {
            if (linkParsing.progressReply.recallOnComplete) {
                progressReceipt?.let { progressMessenger.recall(it) }
            }
        }

        if (linkParsing.replyOnFailure) {
            when (result) {
                is DynamicLinkForwardResult.Failed -> reply(event, "链接解析失败：${result.reason}", CommandStatus.FAILED)
                DynamicLinkForwardResult.NoSupportedLink -> reply(event, "未找到支持的动态链接", CommandStatus.FAILED)
                else -> Unit
            }
        }
    }

    private fun reply(event: CommandEvent, message: String, status: CommandStatus) {
        CommandResultEvent(
            sourcePlugin = LINK_PARSE_EVENT_SOURCE,
            target = CommandTarget(
                address = event.context.target,
                senderId = event.context.senderId,
            ),
            chain = listOf(MessageBatch(listOf(MessageContent.Text(message)))),
            inReplyTo = event.traceId,
            status = status,
            errorMessage = if (status == CommandStatus.FAILED) message else null,
        ).let { eventBus.broadcast(it) }
    }

    private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
        if (seconds <= 0.0 && minimumMillis <= 0) return 0
        return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
    }

    private fun LinkParseTriggerMode.allows(event: CommandEvent): Boolean {
        return when (this) {
            LinkParseTriggerMode.DISABLED -> false
            LinkParseTriggerMode.ALWAYS -> true
            LinkParseTriggerMode.MENTION_ONLY -> event.context.mentionsBot()
        }
    }

    private fun top.colter.dynamic.core.data.CommandContext.mentionsBot(): Boolean {
        val botId = botAccountId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return mentionedAccountIds.any { it == botId }
    }
}
