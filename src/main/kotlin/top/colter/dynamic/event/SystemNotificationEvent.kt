package top.colter.dynamic.event

import top.colter.dynamic.core.event.SystemNotificationPublishRequest

public data class SystemNotificationEvent(
    val sourcePlugin: String,
    val notification: SystemNotificationPublishRequest,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
) : Event
