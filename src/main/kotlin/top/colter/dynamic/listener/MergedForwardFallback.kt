package top.colter.dynamic.listener

import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.plugin.MessageSendRequest

internal fun MessageSendRequest.withMergedForwardFallback(): MessageSendRequest {
    if (!message.batches.containsMergedForward()) return this
    return copy(message = message.withMergedForwardFallback())
}

internal fun Message.withMergedForwardFallback(): Message {
    val rewritten = batches.withMergedForwardFallback()
    return if (rewritten == batches) this else copy(batches = rewritten)
}

internal fun List<MessageBatch>.containsMergedForward(): Boolean {
    return any { batch -> batch.content.any { it is MessageContent.Forward } }
}

internal fun List<MessageBatch>.withMergedForwardFallback(): List<MessageBatch> {
    if (!containsMergedForward()) return this

    val result = mutableListOf<MessageBatch>()
    val current = mutableListOf<MessageContent>()

    fun flushCurrent() {
        if (current.isNotEmpty()) {
            result += MessageBatch(current.toList())
            current.clear()
        }
    }

    forEach { batch ->
        batch.content.forEach { content ->
            when (content) {
                is MessageContent.Forward -> {
                    flushCurrent()
                    result += content.toFallbackBatches()
                }
                else -> current += content
            }
        }
        flushCurrent()
    }

    return result.ifEmpty {
        listOf(MessageBatch(listOf(MessageContent.Text("（合并转发内容为空）"))))
    }
}

private fun MessageContent.Forward.toFallbackBatches(): List<MessageBatch> {
    val batches = mutableListOf<MessageBatch>()
    nodes.forEach { node ->
        val nodeBatches = node.batches.filter { it.content.isNotEmpty() }
        nodeBatches.forEachIndexed { index, batch ->
            val content = buildList {
                if (index == 0) {
                    add(MessageContent.Text("${node.senderName}:\n"))
                }
                addAll(batch.content)
            }
            if (content.isNotEmpty()) {
                batches += MessageBatch(content)
            }
        }
    }
    return batches.ifEmpty {
        listOf(MessageBatch(listOf(MessageContent.Text(fallbackText))))
    }
}
