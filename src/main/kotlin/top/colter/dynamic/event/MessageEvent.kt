package top.colter.dynamic.event

import top.colter.dynamic.core.data.Message

public data class MessageEvent(
    val sourcePlugin: String,
    val message: Message,
) : Event
