package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageReference
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSinkSendReceipt
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testTargetAddress

class MessageSinkReceiptRepositoryTest {
    @Test
    fun shouldRecordEachActualSinkMessageIdAndResolveIncomingReply() {
        initTestDatabase("dynamic-bot-message-sink-receipt")
        val target = testTargetAddress(
            platformId = "qq",
            kind = TargetKind.GROUP,
            externalId = "10001",
        )
        val update = testDynamicUpdate()
        val message = Message(
            id = "message-1",
            time = 1L,
            sourceUpdateKey = update.key,
            renderVariant = "default",
            targets = listOf(target),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )
        val delivery = MessageDeliveryRepository.enqueue(message).newDeliveries.single()

        val count = MessageSinkReceiptRepository.recordSent(
            delivery = delivery,
            message = message,
            receipts = listOf(
                MessageSendResult.receipt(
                    sinkMessageId = "100",
                    sinkRouteId = "onebot:qq:42",
                    sinkAccountId = "42",
                    sinkTransportId = "onebot",
                ),
                MessageSendResult.receipt(
                    sinkMessageId = "101",
                    sinkRouteId = "onebot:qq:42",
                    sinkAccountId = "42",
                    sinkTransportId = "onebot",
                ),
            ),
        )

        assertEquals(2, count)
        val incoming = IncomingMessage(
            platformId = PlatformId.of("qq"),
            target = target,
            senderId = "200",
            botAccountId = "42",
            messageId = "reply-1",
            replyTo = IncomingMessageReference("101"),
        )
        val receipt = assertNotNull(MessageSinkReceiptRepository.findByIncomingReply(incoming))
        assertEquals("101", receipt.sinkMessageId)
        assertEquals("message-1", receipt.messageId)
        assertEquals(update.key, receipt.sourceUpdateKey)
        assertEquals("default", receipt.renderVariant)
        assertEquals("onebot", receipt.transportId)
        assertEquals("42", receipt.sinkAccountId)
        assertEquals(true, receipt.recallable)
    }

    @Test
    fun shouldPersistRecallableFlag() {
        initTestDatabase("dynamic-bot-message-sink-receipt-recallable")
        val target = testTargetAddress("qq", TargetKind.GROUP, "10001")
        val (delivery, message) = enqueueMessage("recallable-message", target)

        MessageSinkReceiptRepository.recordSent(
            delivery = delivery,
            message = message,
            receipts = listOf(
                MessageSinkSendReceipt(
                    sinkMessageId = "not-recallable",
                    sinkTransportId = "onebot",
                    recallable = false,
                ),
            ),
        )

        val receipt = MessageSinkReceiptRepository.findByMessageId(message.id).single()
        assertEquals(false, receipt.recallable)
    }

    @Test
    fun shouldRespectTargetAndAccountWhenResolvingReply() {
        initTestDatabase("dynamic-bot-message-sink-receipt-target")
        val firstTarget = testTargetAddress("qq", TargetKind.GROUP, "10001")
        val secondTarget = testTargetAddress("qq", TargetKind.GROUP, "10002")
        val first = enqueueMessage("first", firstTarget)
        val second = enqueueMessage("second", secondTarget)

        MessageSinkReceiptRepository.recordSent(
            delivery = first.first,
            message = first.second,
            receipts = listOf(
                MessageSendResult.receipt(
                    sinkMessageId = "same-id",
                    sinkAccountId = "bot-a",
                    sinkTransportId = "onebot",
                ),
            ),
        )
        MessageSinkReceiptRepository.recordSent(
            delivery = second.first,
            message = second.second,
            receipts = listOf(
                MessageSendResult.receipt(
                    sinkMessageId = "same-id",
                    sinkAccountId = "bot-b",
                    sinkTransportId = "onebot",
                ),
            ),
        )

        val matched = MessageSinkReceiptRepository.findByIncomingReply(
            IncomingMessage(
                platformId = PlatformId.of("qq"),
                target = secondTarget,
                senderId = "200",
                botAccountId = "bot-b",
                replyTo = IncomingMessageReference("same-id"),
            ),
        )
        assertEquals("second", matched?.messageId)
        assertNull(
            MessageSinkReceiptRepository.findByIncomingReply(
                IncomingMessage(
                    platformId = PlatformId.of("qq"),
                    target = secondTarget,
                    senderId = "200",
                    botAccountId = "missing",
                    replyTo = IncomingMessageReference("same-id"),
                ),
            ),
        )
    }

    private fun enqueueMessage(
        id: String,
        target: top.colter.dynamic.core.data.TargetAddress,
    ): Pair<top.colter.dynamic.core.data.MessageDelivery, Message> {
        val message = Message(
            id = id,
            time = 1L,
            targets = listOf(target),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )
        val delivery = MessageDeliveryRepository.enqueue(message).newDeliveries.single()
        return delivery to message
    }
}
