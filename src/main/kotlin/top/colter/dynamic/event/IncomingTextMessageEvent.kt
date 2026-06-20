package top.colter.dynamic.event

import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.IncomingMessage

public data class IncomingTextMessageEvent(
    val sourcePlugin: String,
    val message: IncomingMessage,
    val context: CommandContext,
    val rawText: String,
    val traceId: String,
    val hasSupportedLinks: Boolean,
    val replyToMessageId: String = "",
) : Event
