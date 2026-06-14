package top.colter.dynamic.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageReference
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.MessageSinkReceiptRepository
import top.colter.dynamic.repository.SourceUpdateSnapshotRepository
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testTargetAddress

class SentMessageContextResolverTest {
    @Test
    fun shouldResolveSentMessageContextFromIncomingReply() {
        initTestDatabase("dynamic-bot-sent-message-context")
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
        val update = testDynamicUpdate(externalId = "dynamic-context")
        SourceUpdateSnapshotRepository.upsert("bilibili", update)
        val message = Message(
            id = "message-context",
            time = 1L,
            sourceUpdateKey = update.key,
            targets = listOf(target),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )
        val delivery = MessageDeliveryRepository.enqueue(message).newDeliveries.single()
        MessageSinkReceiptRepository.recordSent(
            delivery = delivery,
            message = message,
            result = MessageSendResult.sent(
                sinkMessageId = "receipt-context",
                sinkAccountId = "42",
                sinkTransportId = "onebot",
                sinkMessageIds = listOf("receipt-context"),
            ) as MessageSendResult.Sent,
        )

        val context = assertNotNull(
            SentMessageContextResolver.resolveIncomingReply(
                IncomingMessage(
                    platformId = PlatformId.of("qq"),
                    target = target,
                    senderId = "200",
                    botAccountId = "42",
                    replyTo = IncomingMessageReference("receipt-context"),
                ),
            ),
        )

        assertEquals("message-context", context.message?.id)
        assertEquals(delivery.id, context.delivery?.id)
        assertEquals(update.key, context.sourceUpdate?.key)
    }
}
