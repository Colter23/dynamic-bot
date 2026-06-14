package top.colter.dynamic.event

import top.colter.dynamic.core.data.IncomingMessage

public data class IncomingMessageEvent(
    val message: IncomingMessage,
) : Event
