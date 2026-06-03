package top.colter.dynamic.link

import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandParser
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.repository.LinkParseTargetConfigRepository
import kotlin.math.roundToLong

private val autoParseLogger = loggerFor<DynamicLinkAutoParseListener>()

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
        if (!triggerMode.allows(event)) {
            logTriggerBlocked(event, triggerMode)
            return
        }

        var progressReceipt: LinkParseProgressReceipt? = null
        val autoDedupe = dedupe.takeIf { triggerMode == LinkParseTriggerMode.ALWAYS }
        val result = try {
            forwarder.forwardFirst(
                text = event.rawText,
                context = event.context,
                maxLinks = linkParsing.maxLinksPerMessage,
                dedupe = autoDedupe,
                dedupeTtlMs = if (autoDedupe == null) {
                    0
                } else {
                    secondsToMillis(linkParsing.autoDedupeTtlSeconds, minimumMillis = 0)
                },
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
        logForwardResult(event, result)
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

    private fun logTriggerBlocked(event: CommandEvent, triggerMode: LinkParseTriggerMode) {
        if (!event.rawText.contains("http://") && !event.rawText.contains("https://")) return
        val context = event.context
        when (triggerMode) {
            LinkParseTriggerMode.DISABLED -> autoParseLogger.debug {
                "链接自动解析未触发：当前消息目标已关闭链接解析 target=${context.target.stableValue()}"
            }
            LinkParseTriggerMode.MENTION_ONLY -> autoParseLogger.debug {
                "链接自动解析未触发：需要在同一条消息中 @ 当前 bot target=${context.target.stableValue()} botAccountId=${context.botAccountId ?: "未知"} mentionedAccountIds=${context.mentionedAccountIds.joinToString(",").ifBlank { "无" }}"
            }
            LinkParseTriggerMode.ALWAYS -> Unit
        }
    }

    private fun logForwardResult(event: CommandEvent, result: DynamicLinkForwardResult) {
        val target = event.context.target.stableValue()
        when (result) {
            is DynamicLinkForwardResult.Forwarded -> autoParseLogger.info {
                "链接自动解析已提交：target=$target，platform=${result.parsedLink.platformId.value}，update=${result.parsedLink.updateId}"
            }
            is DynamicLinkForwardResult.Duplicate -> autoParseLogger.debug {
                "链接自动解析跳过重复动态：target=$target，platform=${result.parsedLink.platformId.value}，update=${result.parsedLink.updateId}"
            }
            is DynamicLinkForwardResult.Failed -> autoParseLogger.warn {
                "链接自动解析失败：target=$target，原因=${result.reason}"
            }
            DynamicLinkForwardResult.NoSupportedLink -> autoParseLogger.debug {
                "链接自动解析未找到支持的链接：target=$target"
            }
        }
    }

    private fun top.colter.dynamic.core.data.CommandContext.mentionsBot(): Boolean {
        val botId = botAccountId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return mentionedAccountIds.any { it == botId }
    }
}
