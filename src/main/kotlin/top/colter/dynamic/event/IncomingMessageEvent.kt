package top.colter.dynamic.event

import top.colter.dynamic.core.data.IncomingMessage

public data class IncomingMessageEvent(
    val sourcePlugin: String,
    val message: IncomingMessage,
    val traceId: String,
    val replyToMessageId: String = "",
) : Event
