package top.colter.dynamic.message

import java.util.UUID
import kotlinx.coroutines.CancellationException
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.MessageDelivery
import top.colter.dynamic.core.data.MessageDeliveryPolicy
import top.colter.dynamic.core.data.MessageImportance
import top.colter.dynamic.core.data.MessageRecordPolicy
import top.colter.dynamic.core.data.MessageVisibility
import top.colter.dynamic.core.data.OutboundMessageKind
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.plugin.MessageSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.OutboundMessagePublishRequest
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.MessageEnqueueResult
import top.colter.dynamic.repository.MessageSinkReceiptRepository

private val outboundMessageLogger = loggerFor<OutboundMessageService>()

public const val RENDER_VARIANT_MANUAL_FORWARD: String = "manual-forward"
public const val RENDER_VARIANT_SYSTEM_NOTIFICATION: String = "system-notification"
public const val RENDER_VARIANT_COMMAND_RESULT: String = "command-result"
public const val RENDER_VARIANT_LINK_PROGRESS: String = "link-progress"

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

public data class OutboundMessagePublishResult(
    val message: Message,
    val recordPolicy: MessageRecordPolicy,
    val accepted: Boolean,
    val createdMessage: Boolean = false,
    val newDeliveries: List<MessageDelivery> = emptyList(),
    val existingDeliveries: List<MessageDelivery> = emptyList(),
    val sendResults: List<MessageSendResult> = emptyList(),
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
    private val sendNow: suspend (MessageSendRequest) -> MessageSendResult = {
        MessageSendResult.failed("消息发送器未配置", retryable = false)
    },
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val uuid: () -> String = { UUID.randomUUID().toString() },
) {
    public suspend fun enqueueText(
        source: String,
        targets: List<TargetAddress>,
        text: String,
        renderVariant: String,
        deliveryPolicy: MessageDeliveryPolicy = MessageDeliveryPolicy(),
    ): OutboundMessageEnqueueResult {
        val normalizedText = text.trim().also {
            require(it.isNotBlank()) { "消息内容不能为空" }
        }
        return enqueueBatches(
            source = source,
            targets = targets,
            batches = listOf(MessageBatch(listOf(MessageContent.Text(normalizedText)))),
            renderVariant = renderVariant,
            deliveryPolicy = deliveryPolicy,
        )
    }

    public suspend fun enqueueBatches(
        source: String,
        targets: List<TargetAddress>,
        batches: List<MessageBatch>,
        renderVariant: String,
        deliveryPolicy: MessageDeliveryPolicy = MessageDeliveryPolicy(),
    ): OutboundMessageEnqueueResult {
        val result = publish(
            OutboundMessagePublishRequest(
                sourcePlugin = source,
                targets = targets,
                batches = batches,
                renderVariant = renderVariant,
                kind = kindForRenderVariant(renderVariant),
                recordPolicy = MessageRecordPolicy.Durable,
                deliveryPolicy = deliveryPolicy,
            ),
        )
        return OutboundMessageEnqueueResult(
            message = result.message,
            createdMessage = result.createdMessage,
            newDeliveries = result.newDeliveries,
            existingDeliveries = result.existingDeliveries,
        )
    }

    public suspend fun publish(request: OutboundMessagePublishRequest): OutboundMessagePublishResult {
        val message = buildMessage(request)
        return when (message.recordPolicy) {
            MessageRecordPolicy.Durable -> publishDurable(message)
            is MessageRecordPolicy.Transient -> publishTransient(message)
            MessageRecordPolicy.Ephemeral -> publishEphemeral(message)
        }
    }

    private suspend fun publishDurable(message: Message): OutboundMessagePublishResult {
        val enqueue = MessageDeliveryRepository.enqueue(message)
        if (enqueue.newDeliveries.isNotEmpty()) {
            runCatching { onMessagesQueued() }
                .onFailure {
                    outboundMessageLogger.warn(it) {
                        "出站消息已入队，但触发立即投递失败：messageId=${message.id}"
                    }
                }
        }
        return enqueue.toPublishResult()
    }

    private suspend fun publishTransient(message: Message): OutboundMessagePublishResult {
        val createdMessage = MessageDeliveryRepository.createMessageOnly(message)
        val deliveries = mutableListOf<MessageDelivery>()
        val results = mutableListOf<MessageSendResult>()
        message.targets.forEach { target ->
            val result = sendSynchronously(message, target)
            results += result
            val delivery = createSynchronousDelivery(message, target, result)
            deliveries += delivery
            recordReceipts(delivery, message, result)
        }
        return OutboundMessagePublishResult(
            message = message,
            recordPolicy = message.recordPolicy,
            accepted = results.all { it is MessageSendResult.Sent },
            createdMessage = createdMessage,
            newDeliveries = deliveries,
            sendResults = results,
        )
    }

    private suspend fun publishEphemeral(message: Message): OutboundMessagePublishResult {
        val results = message.targets.map { target ->
            sendSynchronously(message, target)
        }
        return OutboundMessagePublishResult(
            message = message,
            recordPolicy = message.recordPolicy,
            accepted = results.all { it is MessageSendResult.Sent },
            sendResults = results,
        )
    }

    private suspend fun sendSynchronously(
        message: Message,
        target: TargetAddress,
    ): MessageSendResult {
        val request = MessageSendRequest(
            message = message.copy(targets = listOf(target)),
            target = target,
        )
        return try {
            sendNow(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            outboundMessageLogger.warn(error) {
                "同步出站消息发送异常：messageId=${message.id}，target=${target.stableValue()}"
            }
            MessageSendResult.failed(error.message ?: "同步出站消息发送异常", retryable = true)
        }
    }

    private fun createSynchronousDelivery(
        message: Message,
        target: TargetAddress,
        result: MessageSendResult,
    ): MessageDelivery {
        return when (result) {
            is MessageSendResult.Sent -> MessageDeliveryRepository.createDeliveryRecord(
                message = message,
                target = target,
                status = DeliveryStatus.SENT,
                attempts = 1,
                sinkMessageId = result.sinkMessageId,
                sinkRouteId = result.sinkRouteId,
                sinkAccountId = result.sinkAccountId,
            )
            is MessageSendResult.PartiallySent -> MessageDeliveryRepository.createDeliveryRecord(
                message = message,
                target = target,
                status = DeliveryStatus.PARTIALLY_SENT,
                attempts = 1,
                sinkMessageId = result.receipts.firstOrNull()?.sinkMessageId,
                sinkRouteId = result.receipts.firstOrNull()?.sinkRouteId,
                sinkAccountId = result.receipts.firstOrNull()?.sinkAccountId,
                lastError = result.reason,
            )
            is MessageSendResult.Failed -> MessageDeliveryRepository.createDeliveryRecord(
                message = message,
                target = target,
                status = DeliveryStatus.FAILED,
                attempts = 1,
                lastError = result.reason,
            )
        }
    }

    private fun recordReceipts(
        delivery: MessageDelivery,
        message: Message,
        result: MessageSendResult,
    ) {
        val receipts = when (result) {
            is MessageSendResult.Sent -> result.receipts
            is MessageSendResult.PartiallySent -> result.receipts
            is MessageSendResult.Failed -> emptyList()
        }
        if (receipts.isEmpty()) return
        runCatching {
            MessageSinkReceiptRepository.recordSent(
                delivery = delivery,
                message = message,
                receipts = receipts,
            )
        }.onFailure { error ->
            outboundMessageLogger.warn(error) {
                "同步出站消息回执记录失败：messageId=${message.id}，deliveryId=${delivery.id}"
            }
        }
    }

    private fun buildMessage(request: OutboundMessagePublishRequest): Message {
        val normalizedSource = request.sourcePlugin.trim().takeIf { it.isNotBlank() } ?: "main"
        val normalizedRenderVariant = request.renderVariant.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("消息来源类型不能为空")
        val normalizedMessageId = request.messageId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedTargets = request.targets
            .distinctBy { it.stableValue() }
            .also { require(it.isNotEmpty()) { "消息目标不能为空" } }
        require(request.batches.isNotEmpty()) { "消息内容不能为空" }
        require(request.batches.any { batch -> batch.content.isNotEmpty() }) { "消息内容不能为空" }

        val now = nowEpochSeconds()
        return Message(
            id = normalizedMessageId ?: buildMessageId(normalizedSource, normalizedRenderVariant, request.correlationId, now),
            time = now,
            sourceUpdateKey = request.sourceUpdateKey,
            sourcePlugin = normalizedSource,
            renderVariant = normalizedRenderVariant,
            kind = request.kind,
            importance = request.importance,
            visibility = request.visibility,
            recordPolicy = request.recordPolicy,
            replyToMessageId = request.replyToMessageId?.trim()?.takeIf { it.isNotBlank() },
            correlationId = request.correlationId?.trim()?.takeIf { it.isNotBlank() },
            deliveryPolicy = request.deliveryPolicy ?: request.recordPolicy.toDeliveryPolicy(now),
            targets = normalizedTargets,
            batches = request.batches,
        )
    }

    private fun MessageRecordPolicy.toDeliveryPolicy(now: Long): MessageDeliveryPolicy {
        return when (this) {
            MessageRecordPolicy.Durable -> MessageDeliveryPolicy(retry = true)
            is MessageRecordPolicy.Transient -> MessageDeliveryPolicy(
                retry = false,
                expiresAtEpochSeconds = now + retentionSeconds,
            )
            MessageRecordPolicy.Ephemeral -> MessageDeliveryPolicy(retry = false)
        }
    }

    private fun buildMessageId(
        source: String,
        renderVariant: String,
        correlationId: String?,
        now: Long,
    ): String {
        val prefix = when (renderVariant) {
            RENDER_VARIANT_SYSTEM_NOTIFICATION -> RENDER_VARIANT_SYSTEM_NOTIFICATION
            RENDER_VARIANT_MANUAL_FORWARD -> RENDER_VARIANT_MANUAL_FORWARD
            RENDER_VARIANT_COMMAND_RESULT -> RENDER_VARIANT_COMMAND_RESULT
            RENDER_VARIANT_LINK_PROGRESS -> RENDER_VARIANT_LINK_PROGRESS
            else -> renderVariant
        }
        return listOf(
            prefix,
            safeIdPart(source),
            correlationId?.let(::safeIdPart)?.takeIf { it.isNotBlank() } ?: now.toString(),
            uuid(),
        ).joinToString(":")
    }

    private fun kindForRenderVariant(renderVariant: String): OutboundMessageKind {
        return when (renderVariant) {
            RENDER_VARIANT_SYSTEM_NOTIFICATION -> OutboundMessageKind.SYSTEM_NOTIFICATION
            RENDER_VARIANT_COMMAND_RESULT -> OutboundMessageKind.COMMAND_RESULT
            RENDER_VARIANT_LINK_PROGRESS -> OutboundMessageKind.PROGRESS
            else -> OutboundMessageKind.NORMAL
        }
    }

    private fun safeIdPart(value: String): String {
        return value
            .replace(Regex("[^a-zA-Z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "main" }
            .take(80)
    }

    private fun MessageEnqueueResult.toPublishResult(): OutboundMessagePublishResult =
        OutboundMessagePublishResult(
            message = message,
            recordPolicy = message.recordPolicy,
            accepted = true,
            createdMessage = createdMessage,
            newDeliveries = newDeliveries,
            existingDeliveries = existingDeliveries,
        )
}
