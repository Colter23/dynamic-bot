package top.colter.dynamic.event

import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.MessageBatch

public data class CommandResultEvent(
    val sourcePlugin: String,
    val target: CommandTarget,
    val chain: List<MessageBatch>,
    val inReplyTo: String,
    val status: CommandStatus,
    val errorMessage: String? = null,
) : Event
