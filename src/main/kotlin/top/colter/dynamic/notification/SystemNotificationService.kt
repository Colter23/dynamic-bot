package top.colter.dynamic.notification

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.event.Listener
import top.colter.dynamic.event.SystemNotificationEvent
import top.colter.dynamic.message.OutboundMessageService
import top.colter.dynamic.message.RENDER_VARIANT_SYSTEM_NOTIFICATION

private val notificationLogger = loggerFor<SystemNotificationService>()

public class SystemNotificationService(
    private val configProvider: () -> MainDynamicConfig,
    private val outboundMessageService: OutboundMessageService,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : Listener<SystemNotificationEvent> {
    private val dedupeUntilByKey: MutableMap<String, Long> = ConcurrentHashMap()

    override suspend fun onMessage(event: SystemNotificationEvent) {
        val config = configProvider().notifications
        if (!config.enabled) return
        val notification = event.notification
        if (!notification.severity.satisfies(config.minSeverity)) return

        val targets = config.adminTargets
            .filter { it.enabled }
            .map { target ->
                TargetAddress.of(
                    platformId = target.platformId,
                    kind = target.targetKind,
                    externalId = target.externalId,
                    scopeId = target.scopeId,
                    threadId = target.threadId,
                    accountId = target.accountId,
                )
            }
            .distinctBy { it.stableValue() }
        if (targets.isEmpty()) {
            notificationLogger.warn {
                "系统通知没有可用管理员目标：source=${event.sourcePlugin}，type=${notification.type}，severity=${notification.severity}"
            }
            return
        }

        if (isDuplicated(event, config.dedupeSeconds)) return

        val result = outboundMessageService.enqueueText(
            source = "system-notification:${event.sourcePlugin}",
            targets = targets,
            text = formatNotification(event),
            renderVariant = RENDER_VARIANT_SYSTEM_NOTIFICATION,
        )
        notificationLogger.info {
            "系统通知已入队：source=${event.sourcePlugin}，type=${notification.type}，severity=${notification.severity}，目标=${result.targetCount}，新增投递=${result.newDeliveryCount}"
        }
    }

    private fun isDuplicated(event: SystemNotificationEvent, dedupeSeconds: Int): Boolean {
        if (dedupeSeconds <= 0) return false
        val now = nowEpochMillis()
        dedupeUntilByKey.entries.removeIf { it.value <= now }

        val key = buildDedupeKey(event)
        val existed = dedupeUntilByKey[key]
        if (existed != null && existed > now) return true

        dedupeUntilByKey[key] = now + dedupeSeconds * 1_000L
        return false
    }

    private fun buildDedupeKey(event: SystemNotificationEvent): String {
        val notification = event.notification
        val raw = notification.dedupeKey.trim().takeIf { it.isNotBlank() }
            ?: listOf(event.sourcePlugin, notification.type, notification.title).joinToString(":")
        return "${notification.severity}:$raw"
    }

    private fun formatNotification(event: SystemNotificationEvent): String {
        val notification = event.notification
        return buildString {
            appendLine("【Dynamic Bot 系统通知】")
            appendLine("级别：${notification.severity}")
            appendLine("标题：${notification.title}")
            appendLine("来源：${event.sourcePlugin}")
            appendLine("类型：${notification.type}")
            appendLine("时间：${formatTime(event.createdAtEpochMillis)}")
            appendLine()
            append(notification.content)
            appendDetails(notification)
        }.trim()
    }

    private fun StringBuilder.appendDetails(notification: SystemNotificationPublishRequest) {
        val details = notification.details
            .mapKeys { it.key.trim() }
            .mapValues { it.value.trim() }
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        if (details.isEmpty()) return

        appendLine()
        appendLine()
        appendLine("详情：")
        details.toSortedMap().forEach { (key, value) ->
            appendLine("$key：$value")
        }
    }

    private fun formatTime(epochMillis: Long): String {
        return FORMATTER.format(Instant.ofEpochMilli(epochMillis))
    }

    private companion object {
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }
}
