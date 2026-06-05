package top.colter.dynamic.message

import java.util.UUID
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.MessageDelivery
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.MessageEnqueueResult

private val outboundMessageLogger = loggerFor<OutboundMessageService>()

public const val RENDER_VARIANT_MANUAL_FORWARD: String = "manual-forward"
public const val RENDER_VARIANT_SYSTEM_NOTIFICATION: String = "system-notification"

public data class OutboundMessageEnqueueResult(
    val message: Message,
    val createdMessage: Boolean,
    val newDeliveries: List<MessageDelivery>,
    val existingDeliveries: List<MessageDelivery>,
) {
    public val targetCount: Int
        get() = message.targets.size

    public val newDeliveryCount: Int
        get() = newDeliveries.size

    public val existingDeliveryCount: Int
        get() = existingDeliveries.size
}

public class OutboundMessageService(
    private val onMessagesQueued: suspend () -> Unit = {},
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val uuid: () -> String = { UUID.randomUUID().toString() },
) {
    public suspend fun enqueueText(
        source: String,
        targets: List<TargetAddress>,
        text: String,
        renderVariant: String,
    ): OutboundMessageEnqueueResult {
        val normalizedText = text.trim().also {
            require(it.isNotBlank()) { "消息内容不能为空" }
        }
        return enqueueBatches(
            source = source,
            targets = targets,
            batches = listOf(MessageBatch(listOf(MessageContent.Text(normalizedText)))),
            renderVariant = renderVariant,
        )
    }

    public suspend fun enqueueBatches(
        source: String,
        targets: List<TargetAddress>,
        batches: List<MessageBatch>,
        renderVariant: String,
    ): OutboundMessageEnqueueResult {
        val normalizedSource = source.trim().takeIf { it.isNotBlank() } ?: "main"
        val normalizedRenderVariant = renderVariant.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("消息来源类型不能为空")
        val normalizedTargets = targets
            .distinctBy { it.stableValue() }
            .also { require(it.isNotEmpty()) { "消息目标不能为空" } }
        require(batches.isNotEmpty()) { "消息内容不能为空" }
        require(batches.any { batch -> batch.content.isNotEmpty() }) { "消息内容不能为空" }

        val message = Message(
            id = buildMessageId(normalizedSource, normalizedRenderVariant),
            time = nowEpochSeconds(),
            sourceUpdateKey = null,
            renderVariant = normalizedRenderVariant,
            targets = normalizedTargets,
            batches = batches,
        )
        val enqueue = MessageDeliveryRepository.enqueue(message)
        if (enqueue.newDeliveries.isNotEmpty()) {
            runCatching { onMessagesQueued() }
                .onFailure {
                    outboundMessageLogger.warn(it) {
                        "出站消息已入队，但触发立即投递失败：messageId=${message.id}"
                    }
                }
        }
        return enqueue.toOutboundResult()
    }

    private fun buildMessageId(source: String, renderVariant: String): String {
        val prefix = when (renderVariant) {
            RENDER_VARIANT_SYSTEM_NOTIFICATION -> RENDER_VARIANT_SYSTEM_NOTIFICATION
            RENDER_VARIANT_MANUAL_FORWARD -> RENDER_VARIANT_MANUAL_FORWARD
            else -> renderVariant
        }
        return listOf(
            prefix,
            safeIdPart(source),
            nowEpochSeconds().toString(),
            uuid(),
        ).joinToString(":")
    }

    private fun safeIdPart(value: String): String {
        return value
            .replace(Regex("[^a-zA-Z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "main" }
            .take(80)
    }

    private fun MessageEnqueueResult.toOutboundResult(): OutboundMessageEnqueueResult =
        OutboundMessageEnqueueResult(
            message = message,
            createdMessage = createdMessage,
            newDeliveries = newDeliveries,
            existingDeliveries = existingDeliveries,
        )
}
