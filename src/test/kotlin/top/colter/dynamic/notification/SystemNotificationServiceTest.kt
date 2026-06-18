package top.colter.dynamic.notification

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.NotificationConfig
import top.colter.dynamic.NotificationTargetConfig
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.event.SystemNotificationEvent
import top.colter.dynamic.message.OutboundMessageService
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PersistenceManager

class SystemNotificationServiceTest {
    @Test
    fun `notification should enqueue admin target and dedupe repeated event`() = runBlocking {
        initDb("notification-dedupe")
        val service = service(
            config = MainDynamicConfig(
                notifications = NotificationConfig(
                    adminTargets = listOf(NotificationTargetConfig("qq", TargetKind.USER, "10001")),
                ),
            ),
        )
        val event = SystemNotificationEvent(
            sourcePlugin = "demo-plugin",
            notification = SystemNotificationPublishRequest(
                type = "demo.failure",
                severity = SystemNotificationSeverity.ERROR,
                title = "测试异常",
                content = "测试内容",
                dedupeKey = "same",
            ),
            createdAtEpochMillis = 1_000,
        )

        service.onMessage(event)
        service.onMessage(event)

        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
        val delivery = MessageDeliveryRepository.findRecent(limit = 1).single()
        val message = MessageDeliveryRepository.findMessage(delivery.messageId)!!
        val text = (message.batches.single().content.single() as MessageContent.Text).fallbackText
        assertTrue(text.contains("测试异常"))
        assertTrue(text.contains("demo-plugin"))
    }

    @Test
    fun `notification below min severity should be ignored`() = runBlocking {
        initDb("notification-min-severity")
        val service = service(
            config = MainDynamicConfig(
                notifications = NotificationConfig(
                    minSeverity = SystemNotificationSeverity.ERROR,
                    adminTargets = listOf(NotificationTargetConfig("qq", TargetKind.USER, "10001")),
                ),
            ),
        )

        service.onMessage(
            SystemNotificationEvent(
                sourcePlugin = "demo-plugin",
                notification = SystemNotificationPublishRequest(
                    type = "demo.warn",
                    severity = SystemNotificationSeverity.WARN,
                    title = "警告",
                    content = "会被忽略",
                ),
            ),
        )

        assertEquals(0, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
    }

    @Test
    fun `notification should use event target override when provided`() = runBlocking {
        initDb("notification-target-override")
        val service = service(
            config = MainDynamicConfig(
                notifications = NotificationConfig(
                    adminTargets = listOf(
                        NotificationTargetConfig("qq", TargetKind.USER, "10001"),
                        NotificationTargetConfig("qq", TargetKind.USER, "10002"),
                    ),
                ),
            ),
        )

        service.onMessage(
            SystemNotificationEvent(
                sourcePlugin = "demo-plugin",
                targets = listOf(TargetAddress.of("qq", TargetKind.USER, "10002")),
                notification = SystemNotificationPublishRequest(
                    type = "demo.failure",
                    severity = SystemNotificationSeverity.ERROR,
                    title = "测试异常",
                    content = "测试内容",
                ),
            ),
        )

        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
        val delivery = MessageDeliveryRepository.findRecent(limit = 1).single()
        assertEquals("10002", delivery.target.externalId)
    }

    private fun service(config: MainDynamicConfig): SystemNotificationService {
        return SystemNotificationService(
            configProvider = { config },
            outboundMessageService = OutboundMessageService(
                nowEpochSeconds = { 1L },
                uuid = { "uuid" },
            ),
            nowEpochMillis = { 1_000L },
        )
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-notification-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }
}
