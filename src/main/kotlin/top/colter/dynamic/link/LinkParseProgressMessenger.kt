package top.colter.dynamic.link

import top.colter.dynamic.LinkParseProgressReplyConfig
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.event.CommandEvent

private val progressLogger = loggerFor<LinkParseProgressMessenger>()

internal data class LinkParseProgressReceipt(
    val target: TargetAddress,
    val sinkMessageId: String,
    val sinkAccountId: String? = null,
)

internal interface LinkParseProgressMessenger {
    suspend fun send(event: CommandEvent, config: LinkParseProgressReplyConfig): LinkParseProgressReceipt?

    suspend fun recall(receipt: LinkParseProgressReceipt)
}

internal object NoopLinkParseProgressMessenger : LinkParseProgressMessenger {
    override suspend fun send(event: CommandEvent, config: LinkParseProgressReplyConfig): LinkParseProgressReceipt? = null

    override suspend fun recall(receipt: LinkParseProgressReceipt) {
    }
}

internal class DeliveryLinkParseProgressMessenger(
    private val sendCommandResult: suspend (CommandResultSendRequest) -> MessageSendResult,
    private val recallMessage: suspend (TargetAddress, String, String?) -> MessageRecallResult,
) : LinkParseProgressMessenger {
    override suspend fun send(event: CommandEvent, config: LinkParseProgressReplyConfig): LinkParseProgressReceipt? {
        if (!config.enabled) return null
        val text = config.text.trim().takeIf { it.isNotBlank() } ?: return null
        val result = sendCommandResult(
            CommandResultSendRequest(
                target = CommandTarget(
                    address = event.context.target.withPreferredAccount(event.context.botAccountId),
                    senderId = event.context.senderId,
                ),
                chain = listOf(MessageBatch(listOf(MessageContent.Text(text)))),
                inReplyTo = event.traceId,
            ),
        )
        return (result as? MessageSendResult.Sent)
            ?.sinkMessageId
            ?.takeIf { it.isNotBlank() }
            ?.let { LinkParseProgressReceipt(event.context.target, it, result.sinkAccountId) }
    }

    override suspend fun recall(receipt: LinkParseProgressReceipt) {
        when (val result = recallMessage(receipt.target, receipt.sinkMessageId, receipt.sinkAccountId)) {
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
