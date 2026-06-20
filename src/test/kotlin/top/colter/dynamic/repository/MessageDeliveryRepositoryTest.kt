package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.MessageImportance
import top.colter.dynamic.core.data.MessageRecordPolicy
import top.colter.dynamic.core.data.MessageVisibility
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.OutboundMessageKind
import top.colter.dynamic.core.data.MessageRecordPolicyType
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSendResult
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

    @Test
    fun cleanupHistoryShouldRemoveOnlyTerminalDeliveriesAndOrphanMessages() {
        initTestDatabase("dynamic-bot-core-delivery-cleanup-db")

        val group = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        val user = testTargetAddress("onebot", TargetKind.USER, "20001")
        val message = Message(
            id = "message-cleanup",
            time = 1L,
            targets = listOf(group, user),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )

        MessageDeliveryRepository.enqueue(message)
        assertTrue(MessageDeliveryRepository.markSent(message.id, group))

        val cutoff = System.currentTimeMillis() / 1000 + 1
        val partial = MessageDeliveryRepository.cleanupHistory(cutoffEpochSeconds = cutoff)

        assertEquals(1, partial.deletedDeliveries)
        assertEquals(0, partial.deletedMessages)
        assertEquals(listOf(DeliveryStatus.PENDING), MessageDeliveryRepository.findByMessageId(message.id).map { it.status })
        assertNotNull(MessageDeliveryRepository.findMessage(message.id))

        assertTrue(MessageDeliveryRepository.markFailed(message.id, user, "network"))
        val terminal = MessageDeliveryRepository.cleanupHistory(cutoffEpochSeconds = cutoff)

        assertEquals(1, terminal.deletedDeliveries)
        assertEquals(1, terminal.deletedMessages)
        assertTrue(MessageDeliveryRepository.findByMessageId(message.id).isEmpty())
        assertNull(MessageDeliveryRepository.findMessage(message.id))
    }

    @Test
    fun cleanupHistoryShouldRemoveReceiptsForExpandedDeliverySet() {
        initTestDatabase("dynamic-bot-core-delivery-cleanup-receipts-db")

        val group = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        val user = testTargetAddress("onebot", TargetKind.USER, "20001")
        val message = Message(
            id = "message-cleanup-receipts",
            time = 1L,
            targets = listOf(group, user),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )

        val deliveries = MessageDeliveryRepository.enqueue(message).newDeliveries
        deliveries.forEachIndexed { index, delivery ->
            MessageSinkReceiptRepository.recordSent(
                delivery = delivery,
                message = message,
                receipts = listOf(
                    MessageSendResult.receipt(
                        sinkMessageId = "sink-$index",
                        sinkTransportId = "onebot",
                    ),
                ),
            )
            assertTrue(MessageDeliveryRepository.markSent(delivery.id, sinkMessageId = "sink-$index"))
        }
        assertEquals(2, MessageSinkReceiptRepository.findByMessageId(message.id).size)

        val result = MessageDeliveryRepository.cleanupHistory(
            cutoffEpochSeconds = System.currentTimeMillis() / 1000 + 1,
            batchSize = 1,
        )

        assertEquals(2, result.deletedDeliveries)
        assertEquals(1, result.deletedMessages)
        assertTrue(MessageDeliveryRepository.findByMessageId(message.id).isEmpty())
        assertTrue(MessageSinkReceiptRepository.findByMessageId(message.id).isEmpty())
    }

    @Test
    fun findRecentShouldHideInternalTransientRecordsWhenRequested() {
        initTestDatabase("dynamic-bot-delivery-default-visible-db")

        val target = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        val durable = testMessage("message-durable", target)
        val transient = testMessage("message-transient", target).copy(
            kind = OutboundMessageKind.PROGRESS,
            importance = MessageImportance.LOW,
            visibility = MessageVisibility.INTERNAL,
            recordPolicy = MessageRecordPolicy.Transient(),
        )

        MessageDeliveryRepository.enqueue(durable)
        MessageDeliveryRepository.createMessageOnly(transient)
        val transientDelivery = MessageDeliveryRepository.createDeliveryRecord(transient, target, DeliveryStatus.SENT, attempts = 1)

        assertEquals(
            listOf("message-transient", "message-durable"),
            MessageDeliveryRepository.findRecent(limit = 10).map { it.messageId },
        )
        assertEquals(
            listOf("message-durable"),
            MessageDeliveryRepository.findRecent(limit = 10, includeInternalRecords = false).map { it.messageId },
        )
        assertEquals(OutboundMessageKind.PROGRESS, transientDelivery.messageKind)
        assertEquals(MessageRecordPolicyType.TRANSIENT, transientDelivery.messageRecordPolicyType)
    }

    @Test
    fun countsByStatusShouldExcludeInternalTransientRecordsWhenRequested() {
        initTestDatabase("dynamic-bot-delivery-default-visible-count-db")

        val target = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        val durable = testMessage("message-count-durable", target)
        val transient = testMessage("message-count-transient", target).copy(
            kind = OutboundMessageKind.PROGRESS,
            importance = MessageImportance.LOW,
            visibility = MessageVisibility.INTERNAL,
            recordPolicy = MessageRecordPolicy.Transient(),
        )

        MessageDeliveryRepository.enqueue(durable)
        MessageDeliveryRepository.createMessageOnly(transient)
        MessageDeliveryRepository.createDeliveryRecord(transient, target, DeliveryStatus.SENT, attempts = 1)

        assertEquals(1, MessageDeliveryRepository.countsByStatus(includeInternalRecords = false)[DeliveryStatus.PENDING])
        assertEquals(0, MessageDeliveryRepository.countsByStatus(includeInternalRecords = false)[DeliveryStatus.SENT])
        assertEquals(1, MessageDeliveryRepository.countsByStatus(includeInternalRecords = true)[DeliveryStatus.SENT])
    }

    @Test
    fun findRecentShouldFillLimitAfterHidingInternalTransientRecords() {
        initTestDatabase("dynamic-bot-delivery-default-visible-limit-db")

        val target = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        repeat(40) { index ->
            val transient = testMessage("message-transient-$index", target).copy(
                kind = OutboundMessageKind.PROGRESS,
                importance = MessageImportance.LOW,
                visibility = MessageVisibility.INTERNAL,
                recordPolicy = MessageRecordPolicy.Transient(),
            )
            MessageDeliveryRepository.createMessageOnly(transient)
            MessageDeliveryRepository.createDeliveryRecord(transient, target, DeliveryStatus.SENT, attempts = 1)
        }
        repeat(3) { index ->
            MessageDeliveryRepository.enqueue(testMessage("message-durable-$index", target))
        }

        val rows = MessageDeliveryRepository.findRecent(limit = 3, includeInternalRecords = false)

        assertEquals(
            listOf("message-durable-2", "message-durable-1", "message-durable-0"),
            rows.map { it.messageId },
        )
    }

    @Test
    fun cleanupHistoryShouldTreatPartiallySentAsTerminal() {
        initTestDatabase("dynamic-bot-core-delivery-cleanup-partial-db")

        val target = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        val message = testMessage("message-cleanup-partial", target)
        MessageDeliveryRepository.enqueue(message)
        val delivery = MessageDeliveryRepository.findByMessageId(message.id).single()
        assertTrue(MessageDeliveryRepository.markPartiallySent(delivery.id, "第二段消息失败", sinkMessageId = "partial"))

        val result = MessageDeliveryRepository.cleanupHistory(
            cutoffEpochSeconds = System.currentTimeMillis() / 1000 + 1,
        )

        assertEquals(1, result.deletedDeliveries)
        assertEquals(1, result.deletedMessages)
        assertTrue(MessageDeliveryRepository.findByMessageId(message.id).isEmpty())
        assertNull(MessageDeliveryRepository.findMessage(message.id))
    }

    @Test
    fun cleanupTransientMessagesShouldScanPastDurableMessages() {
        initTestDatabase("dynamic-bot-delivery-transient-cleanup-db")

        val target = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        val durable = testMessage("message-old-durable", target)
        val transient = testMessage("message-expired-transient", target).copy(
            time = 10L,
            recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = 5),
        )

        MessageDeliveryRepository.enqueue(durable)
        MessageDeliveryRepository.createMessageOnly(transient)
        MessageDeliveryRepository.createDeliveryRecord(transient, target, DeliveryStatus.SENT, attempts = 1)

        val result = MessageDeliveryRepository.cleanupTransientMessages(
            nowEpochSeconds = 20,
            batchSize = 1,
        )

        assertEquals(1, result.deletedDeliveries)
        assertEquals(1, result.deletedMessages)
        assertNotNull(MessageDeliveryRepository.findMessage(durable.id))
        assertNull(MessageDeliveryRepository.findMessage(transient.id))
    }

    @Test
    fun createDeliveryRecordShouldStoreTransientExpiryColumn() {
        initTestDatabase("dynamic-bot-delivery-transient-expiry-column-db")

        val target = testTargetAddress("onebot", TargetKind.GROUP, "10001")
        val transient = testMessage("message-transient-expiry-column", target).copy(
            time = 100L,
            recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = 30),
        )

        MessageDeliveryRepository.createMessageOnly(transient)
        val delivery = MessageDeliveryRepository.createDeliveryRecord(transient, target, DeliveryStatus.SENT, attempts = 1)

        assertEquals(MessageRecordPolicyType.TRANSIENT, delivery.messageRecordPolicyType)
        assertEquals(130L, delivery.transientExpiresAtEpochSeconds)
    }

    private fun testMessage(id: String, target: top.colter.dynamic.core.data.TargetAddress): Message {
        return Message(
            id = id,
            time = 1L,
            targets = listOf(target),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )
    }
}
