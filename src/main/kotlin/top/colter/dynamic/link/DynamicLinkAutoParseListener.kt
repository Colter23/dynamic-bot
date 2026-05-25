package top.colter.dynamic.link

import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandParser
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.broadcast

internal class DynamicLinkAutoParseListener(
    private val configProvider: () -> MainDynamicConfig,
    private val forwarder: DynamicLinkForwarder,
    private val dedupe: DynamicLinkDedupe = DynamicLinkDedupe(),
) : Listener<CommandEvent> {
    override suspend fun onMessage(event: CommandEvent) {
        val config = configProvider()
        val linkParsing = config.linkParsing
        if (!linkParsing.autoParseEnabled) return
        if (CommandParser.parse(event.rawText, config.command.prefix) != null) return

        val result = forwarder.forwardFirst(
            text = event.rawText,
            context = event.context,
            maxLinks = linkParsing.maxLinksPerMessage,
            dedupe = dedupe,
            dedupeTtlMs = linkParsing.autoDedupeTtlMs,
        )

        if (linkParsing.autoReplyOnFailure) {
            when (result) {
                is DynamicLinkForwardResult.Failed -> reply(event, "链接解析失败: ${result.reason}", CommandStatus.FAILED)
                DynamicLinkForwardResult.NoSupportedLink -> reply(event, "未找到支持的动态链接", CommandStatus.FAILED)
                else -> Unit
            }
        }
    }

    private fun reply(event: CommandEvent, message: String, status: CommandStatus) {
        CommandResultEvent(
            sourcePlugin = LINK_PARSE_EVENT_SOURCE,
            target = CommandTarget(
                platform = event.context.platform,
                chatType = event.context.chatType,
                chatId = event.context.chatId,
                senderId = event.context.senderId,
            ),
            chain = listOf(MessageChain(listOf(MessageContent.Text(message)))),
            inReplyTo = event.traceId,
            status = status,
            errorMessage = if (status == CommandStatus.FAILED) message else null,
        ).broadcast()
    }
}
