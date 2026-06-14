package top.colter.dynamic.message

import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageDelivery
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.MessageSinkReceipt
import top.colter.dynamic.repository.MessageSinkReceiptRepository
import top.colter.dynamic.repository.SourceUpdateSnapshotRepository

public data class SentMessageContext(
    val receipt: MessageSinkReceipt,
    val delivery: MessageDelivery?,
    val message: Message?,
    val sourceUpdate: SourceUpdate?,
)

public object SentMessageContextResolver {
    public fun resolveIncomingReply(message: IncomingMessage): SentMessageContext? {
        val receipt = MessageSinkReceiptRepository.findByIncomingReply(message) ?: return null
        val delivery = MessageDeliveryRepository.findById(receipt.deliveryId)
        val sentMessage = MessageDeliveryRepository.findMessage(receipt.messageId)
        val sourceUpdate = receipt.sourceUpdateKey?.let { SourceUpdateSnapshotRepository.findUpdate(it) }
        return SentMessageContext(
            receipt = receipt,
            delivery = delivery,
            message = sentMessage,
            sourceUpdate = sourceUpdate,
        )
    }
}
