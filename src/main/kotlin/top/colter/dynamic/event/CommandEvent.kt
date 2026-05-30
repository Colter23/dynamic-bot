package top.colter.dynamic.event

import top.colter.dynamic.core.data.CommandContext

public data class CommandEvent(
    val sourcePlugin: String,
    val context: CommandContext,
    val rawText: String,
    val traceId: String,
) : Event
