package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testTargetAddress

class MessageDeliveryRepositoryTest {
    @Test
    fun shouldCreatePendingDeliveriesAndUpdateStatusPerStableTarget() {
        initTestDatabase("dynamic-bot-core-delivery-db")

        val group = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        val user = testTargetAddress("onebot", TargetKind.USER, "20001")
        val message = Message(
            id = "message-123",
            time = 1L,
            targets = listOf(group, user),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )

        val deliveries = MessageDeliveryRepository.enqueue(message)
        val repeated = MessageDeliveryRepository.enqueue(message)
        assertEquals(2, deliveries.newDeliveries.size)
        assertEquals(0, repeated.newDeliveries.size)
        assertEquals(2, repeated.existingDeliveries.size)
        assertEquals(2, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))

        assertTrue(MessageDeliveryRepository.markSent(message.id, group))
        assertTrue(MessageDeliveryRepository.markFailed(message.id, user, "network"))

        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.SENT))
        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.FAILED))
        assertEquals(listOf(1, 1), MessageDeliveryRepository.findByMessageId(message.id).map { it.attempts }.sorted())
    }

    @Test
    fun shouldClaimDueDeliveriesAndRecoverExpiredSendingRows() {
        initTestDatabase("dynamic-bot-core-delivery-claim-db")

        val target = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        val message = Message(
            id = "message-claim",
            time = 1L,
            targets = listOf(target),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )

        MessageDeliveryRepository.enqueue(message)

        val now = System.currentTimeMillis() / 1000
        val claimed = MessageDeliveryRepository.claimDue(nowEpochSeconds = now, limit = 10, lockTtlMs = 5_000)
        assertEquals(1, claimed.size)
        assertEquals(DeliveryStatus.SENDING, MessageDeliveryRepository.findByMessageId(message.id).single().status)
        assertEquals(0, MessageDeliveryRepository.claimDue(nowEpochSeconds = now, limit = 10, lockTtlMs = 5_000).size)

        assertEquals(1, MessageDeliveryRepository.markSendingExpired(nowEpochSeconds = now + 10))
        assertEquals(DeliveryStatus.PENDING, MessageDeliveryRepository.findByMessageId(message.id).single().status)
    }
}
