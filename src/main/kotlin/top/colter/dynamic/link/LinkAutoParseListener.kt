package top.colter.dynamic.link

import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandParser
import top.colter.dynamic.core.data.CommandContext
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

private val autoParseLogger = loggerFor<LinkAutoParseListener>()

internal class LinkAutoParseListener(
    private val configProvider: () -> MainDynamicConfig,
    private val linkParseService: LinkParseService,
    private val eventBus: EventBus = EventBus(),
    private val dedupe: LinkParseDedupe = LinkParseDedupe(),
    private val progressMessenger: LinkParseProgressMessenger = NoopLinkParseProgressMessenger,
    private val primaryBotAccountResolver: suspend (CommandContext) -> String? = { null },
) : Listener<CommandEvent> {
    override suspend fun onMessage(event: CommandEvent) {
        val config = configProvider()
        val linkParsing = config.linkParsing
        if (!linkParsing.autoParseEnabled) return
        if (CommandParser.parse(event.rawText, config.command.prefix) != null) return
        if (!shouldAcceptAutoParse(event)) {
            logBotBlocked(event)
            return
        }

        val triggerMode = LinkParseTargetConfigRepository.findEffectiveByAddress(event.context.target)?.triggerMode
            ?: linkParsing.fallbackTriggerMode
        if (!triggerMode.allows(event)) {
            logTriggerBlocked(event, triggerMode)
            return
        }

        var progressReceipt: LinkParseProgressReceipt? = null
        var result: LinkParseBatchResult? = null
        suspend fun sendProgressOnce() {
            if (progressReceipt == null && linkParsing.progressReply.text.isNotBlank()) {
                progressReceipt = progressMessenger.send(event, linkParsing.progressReply)
            }
        }
        val autoDedupe = dedupe.takeIf { triggerMode == LinkParseTriggerMode.ALWAYS }
        val hasSupportedCandidate = linkParseService.hasSupportedLinkCandidate(
            event.rawText,
            linkParsing.maxLinksPerMessage,
        )
        val finalResult = try {
            if (hasSupportedCandidate) {
                sendProgressOnce()
            }
            linkParseService.parseAndDispatch(
                text = event.rawText,
                context = event.context,
                maxLinks = linkParsing.maxLinksPerMessage,
                dedupe = autoDedupe,
                dedupeTtlMs = if (autoDedupe == null) {
                    0
                } else {
                    secondsToMillis(linkParsing.autoDedupeTtlSeconds, minimumMillis = 0)
                },
                inReplyTo = event.traceId,
                onForwardingStarted = {
                    sendProgressOnce()
                },
            ).also { result = it }
        } finally {
            if (linkParsing.progressReply.recallOnComplete || result?.hasForwarded != true) {
                progressReceipt?.let { progressMessenger.recall(it) }
            }
        }

        val hasFailedSupportedLink = (hasSupportedCandidate && !finalResult.hasSupportedLinks) ||
            finalResult.failures.isNotEmpty()
        val shouldReplyFailure = hasFailedSupportedLink
        if (shouldReplyFailure) {
            when {
                finalResult.disabledReason != null -> reply(event, "链接解析失败：${finalResult.disabledReason}", CommandStatus.FAILED)
                hasSupportedCandidate && !finalResult.hasSupportedLinks -> {
                    reply(event, "链接解析失败：无法获取链接信息，请检查链接是否正确或稍后再试。", CommandStatus.FAILED)
                }
                !finalResult.hasSupportedLinks -> reply(event, "未找到支持的链接", CommandStatus.FAILED)
                finalResult.failures.isNotEmpty() && !finalResult.hasForwarded -> {
                    reply(event, "链接解析失败：${finalResult.failureSummary.ifBlank { "没有链接成功解析" }}", CommandStatus.FAILED)
                }
                finalResult.failures.isNotEmpty() -> {
                    reply(event, "部分链接解析失败：${finalResult.failureSummary}", CommandStatus.SUCCESS)
                }
            }
        }
        logForwardResult(event, finalResult)
    }

    private fun reply(event: CommandEvent, message: String, status: CommandStatus) {
        CommandResultEvent(
            sourcePlugin = LINK_PARSE_EVENT_SOURCE,
            target = CommandTarget(
                address = event.context.target.withPreferredAccount(event.context.botAccountId),
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

    private suspend fun shouldAcceptAutoParse(event: CommandEvent): Boolean {
        val botAccountId = event.context.botAccountId?.trim()?.takeIf { it.isNotBlank() } ?: return true
        if (event.context.mentionedAccountIds.any { it == botAccountId }) return true
        val primaryAccountId = primaryBotAccountResolver(event.context)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return true
        return botAccountId == primaryAccountId
    }

    private fun logBotBlocked(event: CommandEvent) {
        if (!event.rawText.contains("http://") && !event.rawText.contains("https://")) return
        val context = event.context
        autoParseLogger.debug {
            "链接自动解析未触发：当前 Bot 不是主 Bot 且未被 @ target=${context.target.stableValue()} botAccountId=${context.botAccountId ?: "未知"} mentionedAccountIds=${context.mentionedAccountIds.joinToString(",").ifBlank { "无" }}"
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

    private fun logForwardResult(event: CommandEvent, result: LinkParseBatchResult) {
        val target = event.context.target.stableValue()
        result.items.forEach { item ->
            when (item) {
                is LinkParseItemResult.Forwarded -> autoParseLogger.info {
                    "链接自动解析已提交：target=$target，platform=${item.parsedLink.platformId.value}，kind=${item.parsedLink.kind}，id=${item.parsedLink.targetId}，deliveryCount=${item.deliveryCount}"
                }
                is LinkParseItemResult.Duplicate -> autoParseLogger.debug {
                    "链接自动解析跳过重复链接：target=$target，platform=${item.parsedLink.platformId.value}，kind=${item.parsedLink.kind}，id=${item.parsedLink.targetId}"
                }
                is LinkParseItemResult.Failed -> autoParseLogger.warn {
                    "链接自动解析失败：target=$target，platform=${item.parsedLink.platformId.value}，kind=${item.parsedLink.kind}，id=${item.parsedLink.targetId}，原因=${item.reason}"
                }
            }
        }
        if (result.disabledReason != null) {
            autoParseLogger.warn {
                "链接自动解析失败：target=$target，原因=${result.disabledReason}"
            }
        } else if (!result.hasSupportedLinks) {
            autoParseLogger.debug {
                "链接自动解析未找到支持的链接：target=$target"
            }
        }
    }

    private fun top.colter.dynamic.core.data.CommandContext.mentionsBot(): Boolean {
        val botId = botAccountId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return mentionedAccountIds.any { it == botId }
    }

    private fun top.colter.dynamic.core.data.TargetAddress.withPreferredAccount(
        accountId: String?,
    ): top.colter.dynamic.core.data.TargetAddress {
        val normalized = accountId?.trim()?.takeIf { it.isNotBlank() } ?: return this
        return copy(accountId = normalized)
    }
}
