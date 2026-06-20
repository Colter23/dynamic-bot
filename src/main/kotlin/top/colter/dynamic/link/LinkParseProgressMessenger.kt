package top.colter.dynamic.link

import top.colter.dynamic.LinkParseProgressReplyConfig
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.MessageImportance
import top.colter.dynamic.core.data.MessageRecordPolicy
import top.colter.dynamic.core.data.MessageVisibility
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.OutboundMessageKind
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.OutboundMessagePublishRequest
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.event.IncomingTextMessageEvent
import top.colter.dynamic.message.OutboundMessagePublishResult
import top.colter.dynamic.message.RENDER_VARIANT_LINK_PROGRESS

private val progressLogger = loggerFor<LinkParseProgressMessenger>()

public data class LinkParseProgressReceipt(
    val target: TargetAddress,
    val sinkMessageId: String,
    val sinkRouteId: String? = null,
    val sinkAccountId: String? = null,
)

public interface LinkParseProgressMessenger {
    public suspend fun send(event: IncomingTextMessageEvent, config: LinkParseProgressReplyConfig): LinkParseProgressReceipt? {
        return send(event.context, event.replyToMessageId, config.text)
    }

    public suspend fun send(context: CommandContext, inReplyTo: String, text: String): LinkParseProgressReceipt?

    public suspend fun recall(receipt: LinkParseProgressReceipt)
}

public object NoopLinkParseProgressMessenger : LinkParseProgressMessenger {
    override suspend fun send(
        context: CommandContext,
        inReplyTo: String,
        text: String,
    ): LinkParseProgressReceipt? = null

    override suspend fun recall(receipt: LinkParseProgressReceipt) {
    }
}

public class DeliveryLinkParseProgressMessenger(
    private val publishMessage: suspend (OutboundMessagePublishRequest) -> OutboundMessagePublishResult,
    private val recallMessage: suspend (TargetAddress, String, String?, String?) -> MessageRecallResult,
) : LinkParseProgressMessenger {
    override suspend fun send(
        context: CommandContext,
        inReplyTo: String,
        text: String,
    ): LinkParseProgressReceipt? {
        val value = text.trim().takeIf { it.isNotBlank() } ?: return null
        val targetAddress = context.target.withPreferredAccount(context.botAccountId)
        val result = publishMessage(
            OutboundMessagePublishRequest(
                sourcePlugin = "main-link-parser",
                targets = listOf(targetAddress),
                batches = listOf(MessageBatch(listOf(MessageContent.Text(value)))),
                renderVariant = RENDER_VARIANT_LINK_PROGRESS,
                kind = OutboundMessageKind.PROGRESS,
                importance = MessageImportance.LOW,
                visibility = MessageVisibility.INTERNAL,
                recordPolicy = MessageRecordPolicy.Transient(),
                replyToMessageId = inReplyTo,
                correlationId = inReplyTo,
            ),
        )
        val receipt = result.sendResults
            .asSequence()
            .filterIsInstance<MessageSendResult.Sent>()
            .flatMap { it.receipts.asSequence() }
            .firstOrNull()
            ?: return null
        return receipt.sinkMessageId
            .takeIf { it.isNotBlank() }
            ?.let { LinkParseProgressReceipt(targetAddress, it, receipt.sinkRouteId, receipt.sinkAccountId) }
    }

    override suspend fun recall(receipt: LinkParseProgressReceipt) {
        when (val result = recallMessage(
            receipt.target,
            receipt.sinkMessageId,
            receipt.sinkRouteId,
            receipt.sinkAccountId,
        )) {
            MessageRecallResult.Recalled,
            MessageRecallResult.Unsupported -> Unit
            is MessageRecallResult.Failed -> {
                progressLogger.debug {
                    "解析中提示撤回失败：target=${receipt.target.stableValue()}，sinkMessageId=${receipt.sinkMessageId}，原因=${result.reason}"
                }
            }
        }
    }

    private fun TargetAddress.withPreferredAccount(accountId: String?): TargetAddress {
        val normalized = accountId?.trim()?.takeIf { it.isNotBlank() } ?: return this
        return copy(accountId = normalized)
    }
}
