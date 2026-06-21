package top.colter.dynamic.message

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageDeliveryPolicy
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.OutboundMessageKind
import top.colter.dynamic.core.data.MessageRecordPolicy
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.plugin.MessageSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.OutboundMessagePublishRequest
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.MessageSinkReceiptRepository
import top.colter.dynamic.repository.PersistenceManager

class OutboundMessageServiceTest {
    @Test
    fun `enqueue text should create outbox message and trigger dispatch callback`() = runBlocking {
        initDb("outbound-text")
        var triggered = 0
        val service = OutboundMessageService(
            onMessagesQueued = { triggered += 1 },
            nowEpochSeconds = { 1L },
            uuid = { "uuid" },
        )

        val result = service.enqueueText(
            source = "web-admin",
            targets = listOf(TargetAddress.of("qq", TargetKind.GROUP, "10001")),
            text = " hello ",
            renderVariant = RENDER_VARIANT_MANUAL_FORWARD,
            deliveryPolicy = MessageDeliveryPolicy(retry = false, expiresAtEpochSeconds = 60),
        )

        assertEquals("manual-forward:web-admin:1:uuid", result.message.id)
        assertEquals(1, result.newDeliveryCount)
        assertEquals(1, triggered)
        val stored = assertNotNull(MessageDeliveryRepository.findMessage(result.message.id))
        assertEquals(RENDER_VARIANT_MANUAL_FORWARD, stored.renderVariant)
        assertEquals(OutboundMessageKind.MANUAL, stored.kind)
        assertEquals(MessageDeliveryPolicy(retry = false, expiresAtEpochSeconds = 60), stored.deliveryPolicy)
        assertEquals("hello", (stored.batches.single().content.single() as MessageContent.Text).fallbackText)
    }

    @Test
    fun `publish durable should preserve explicit ids and source update key`() = runBlocking {
        initDb("outbound-durable-explicit-id")
        val updateKey = UpdateKey.of(
            PublisherKey.of("bilibili", PublisherKind.USER, "42"),
            SourceEventType.DYNAMIC_CREATED,
            "dynamic-1",
        )
        val service = OutboundMessageService(
            nowEpochSeconds = { 1L },
            uuid = { "uuid" },
        )

        val result = service.publish(
            publishRequest(
                messageId = "custom-message-id",
                sourceUpdateKey = updateKey,
            ),
        )

        assertEquals(true, result.accepted)
        assertEquals("custom-message-id", result.message.id)
        assertEquals(updateKey, result.message.sourceUpdateKey)
        assertEquals(1, result.newDeliveryCount)
        assertEquals(updateKey, MessageDeliveryRepository.findByMessageId(result.message.id).single().sourceUpdateKey)
    }

    @Test
    fun `publish transient should send synchronously and keep short lived receipts`() = runBlocking {
        initDb("outbound-transient")
        val sentRequests = mutableListOf<MessageSendRequest>()
        val service = OutboundMessageService(
            sendNow = { request ->
                sentRequests += request
                MessageSendResult.sent("sink-${request.target.externalId}", sinkTransportId = "onebot")
            },
            nowEpochSeconds = { 10L },
            uuid = { "uuid" },
        )

        val result = service.publish(
            publishRequest(
                recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = 60),
            ),
        )

        assertEquals(true, result.accepted)
        assertEquals(1, sentRequests.size)
        assertEquals(MessageDeliveryPolicy(retry = false, expiresAtEpochSeconds = 70), result.message.deliveryPolicy)
        assertNotNull(MessageDeliveryRepository.findMessage(result.message.id))
        val delivery = MessageDeliveryRepository.findByMessageId(result.message.id).single()
        assertEquals("sink-10001", delivery.sinkMessageId)
        assertEquals(1, MessageSinkReceiptRepository.findByMessageId(result.message.id).size)
    }

    @Test
    fun `publish transient should force non retrying expiry when explicit delivery policy is passed`() = runBlocking {
        initDb("outbound-transient-policy-merge")
        val service = OutboundMessageService(
            sendNow = { MessageSendResult.sent("sink-${it.target.externalId}") },
            nowEpochSeconds = { 10L },
            uuid = { "uuid" },
        )

        val result = service.publish(
            publishRequest(
                recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = 60),
            ).copy(
                deliveryPolicy = MessageDeliveryPolicy(
                    retry = true,
                    expiresAtEpochSeconds = 120,
                    requireActiveTarget = false,
                ),
            ),
        )

        assertEquals(true, result.accepted)
        assertEquals(
            MessageDeliveryPolicy(retry = false, expiresAtEpochSeconds = 70, requireActiveTarget = false),
            result.message.deliveryPolicy,
        )
    }

    @Test
    fun `publish ephemeral should send without persistent records`() = runBlocking {
        initDb("outbound-ephemeral")
        var sent = 0
        val service = OutboundMessageService(
            sendNow = {
                sent += 1
                MessageSendResult.sent("sink-ephemeral")
            },
            nowEpochSeconds = { 1L },
            uuid = { "uuid" },
        )

        val result = service.publish(
            publishRequest(recordPolicy = MessageRecordPolicy.Ephemeral),
        )

        assertEquals(true, result.accepted)
        assertEquals(1, sent)
        assertNull(MessageDeliveryRepository.findMessage(result.message.id))
        assertEquals(emptyList(), MessageDeliveryRepository.findByMessageId(result.message.id))
        assertEquals(emptyList(), MessageSinkReceiptRepository.findByMessageId(result.message.id))
    }

    @Test
    fun `publish transient should reject partial sends but keep receipts`() = runBlocking {
        initDb("outbound-transient-partial")
        val service = OutboundMessageService(
            sendNow = {
                MessageSendResult.partiallySent(
                    receipts = listOf(MessageSendResult.receipt("partial-sink", sinkTransportId = "onebot")),
                    reason = "第二段消息失败",
                )
            },
            nowEpochSeconds = { 10L },
            uuid = { "uuid" },
        )

        val result = service.publish(
            publishRequest(recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = 60)),
        )

        assertEquals(false, result.accepted)
        val delivery = MessageDeliveryRepository.findByMessageId(result.message.id).single()
        assertEquals(DeliveryStatus.PARTIALLY_SENT, delivery.status)
        assertEquals("partial-sink", delivery.sinkMessageId)
        assertEquals(1, MessageSinkReceiptRepository.findByMessageId(result.message.id).size)
    }

    @Test
    fun `publish transient should record uncertain delivery without receipts`() = runBlocking {
        initDb("outbound-transient-uncertain")
        val service = OutboundMessageService(
            sendNow = {
                MessageSendResult.uncertain(
                    reason = "OneBot 发送响应超时",
                    sinkRouteId = "onebot:qq:42",
                    sinkAccountId = "42",
                    sinkTransportId = "onebot",
                )
            },
            nowEpochSeconds = { 10L },
            uuid = { "uuid" },
        )

        val result = service.publish(
            publishRequest(recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = 60)),
        )

        assertEquals(false, result.accepted)
        val delivery = MessageDeliveryRepository.findByMessageId(result.message.id).single()
        assertEquals(DeliveryStatus.SEND_UNKNOWN, delivery.status)
        assertEquals("onebot:qq:42", delivery.sinkRouteId)
        assertEquals("42", delivery.sinkAccountId)
        assertEquals("OneBot 发送响应超时", delivery.lastError)
        assertEquals(emptyList(), MessageSinkReceiptRepository.findByMessageId(result.message.id))
    }

    @Test
    fun `publish transient should record failed delivery when synchronous send throws`() = runBlocking {
        initDb("outbound-transient-send-throws")
        val service = OutboundMessageService(
            sendNow = { throw IllegalStateException("发送通道异常") },
            nowEpochSeconds = { 10L },
            uuid = { "uuid" },
        )

        val result = service.publish(
            publishRequest(recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = 60)),
        )

        assertEquals(false, result.accepted)
        assertIs<MessageSendResult.Failed>(result.sendResults.single())
        assertNotNull(MessageDeliveryRepository.findMessage(result.message.id))
        val delivery = MessageDeliveryRepository.findByMessageId(result.message.id).single()
        assertEquals(DeliveryStatus.FAILED, delivery.status)
        assertEquals("发送通道异常", delivery.lastError)
    }

    @Test
    fun `publish ephemeral should convert synchronous send exception to failed result`() = runBlocking {
        initDb("outbound-ephemeral-send-throws")
        val service = OutboundMessageService(
            sendNow = { throw IllegalStateException("发送通道异常") },
            nowEpochSeconds = { 1L },
            uuid = { "uuid" },
        )

        val result = service.publish(
            publishRequest(recordPolicy = MessageRecordPolicy.Ephemeral),
        )

        assertEquals(false, result.accepted)
        assertIs<MessageSendResult.Failed>(result.sendResults.single())
        assertNull(MessageDeliveryRepository.findMessage(result.message.id))
        assertEquals(emptyList(), MessageDeliveryRepository.findByMessageId(result.message.id))
        assertEquals(emptyList(), MessageSinkReceiptRepository.findByMessageId(result.message.id))
    }

    private fun publishRequest(
        messageId: String? = null,
        sourceUpdateKey: UpdateKey? = null,
        recordPolicy: MessageRecordPolicy = MessageRecordPolicy.Durable,
    ): OutboundMessagePublishRequest {
        return OutboundMessagePublishRequest(
            sourcePlugin = "test",
            messageId = messageId,
            sourceUpdateKey = sourceUpdateKey,
            targets = listOf(TargetAddress.of("qq", TargetKind.GROUP, "10001")),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
            renderVariant = "default",
            recordPolicy = recordPolicy,
        )
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-outbound-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }
}
