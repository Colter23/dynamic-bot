package top.colter.dynamic.notification

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.NotificationConfig
import top.colter.dynamic.NotificationTargetConfig
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.SystemNotificationEvent
import top.colter.dynamic.plugin.PluginHandle

private val routeMonitorLogger = loggerFor<MessageSinkRouteMonitor>()

public class MessageSinkRouteMonitor(
    private val sinkProvider: () -> List<PluginHandle<MessageSinkPlugin>>,
    private val notificationConfigProvider: () -> NotificationConfig = { NotificationConfig() },
    private val eventBus: EventBus,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val previousRoutes: MutableMap<String, RouteSnapshot> = ConcurrentHashMap()
    private val unavailableSinceByRouteId: MutableMap<String, Long> = ConcurrentHashMap()
    private val notifiedUnavailableRouteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val skippedUnavailableRouteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var initialized: Boolean = false

    public suspend fun scan() {
        val now = nowEpochMillis()
        val notificationConfig = notificationConfigProvider()
        val handles = sinkProvider()
        val activePluginIds = handles.map { it.info.descriptor.id }.toSet()
        val failedPluginIds = linkedSetOf<String>()
        val current = linkedMapOf<String, RouteSnapshot>()

        handles.forEach { handle ->
            val sink = handle.instance as? AccountRoutedMessageSinkPlugin ?: return@forEach
            val pluginId = handle.info.descriptor.id
            val routes = runCatching { sink.listMessageSinkRoutes() }
                .onFailure { error ->
                    failedPluginIds += pluginId
                    routeMonitorLogger.warn(error) { "消息出口路线状态读取失败：pluginId=$pluginId" }
                    if (initialized && hadReadyRoute(pluginId)) {
                        publishRouteListFailure(pluginId, error)
                    }
                }
                .getOrNull()
                ?: return@forEach
            routes.forEach { route ->
                current[route.routeId] = RouteSnapshot.from(pluginId, route, present = true)
            }
        }

        val next = linkedMapOf<String, RouteSnapshot>()
        next.putAll(current)
        if (initialized) {
            previousRoutes.values.forEach { previous ->
                if (previous.pluginId !in activePluginIds) return@forEach
                if (previous.pluginId in failedPluginIds) {
                    next.putIfAbsent(previous.routeId, previous)
                    return@forEach
                }

                val latest = current[previous.routeId]
                if (latest == null) {
                    val missing = previous.copy(
                        state = MessageSinkRouteState.UNAVAILABLE,
                        present = false,
                    )
                    next[previous.routeId] = missing
                    handleRouteUnavailable(
                        previous = previous,
                        route = missing,
                        currentRoutes = current.values,
                        notificationConfig = notificationConfig,
                        now = now,
                        title = "消息出口账号路线已消失",
                    )
                    return@forEach
                }

                when (latest.state) {
                    MessageSinkRouteState.READY -> handleRouteReady(previous, latest)
                    MessageSinkRouteState.UNAVAILABLE -> handleRouteUnavailable(
                        previous = previous,
                        route = latest,
                        currentRoutes = current.values,
                        notificationConfig = notificationConfig,
                        now = now,
                        title = "消息出口账号路线不可用",
                    )
                }
            }
        }

        notifiedUnavailableRouteIds.retainAll(next.keys)
        unavailableSinceByRouteId.keys.retainAll(next.keys)
        skippedUnavailableRouteIds.retainAll(next.keys)
        previousRoutes.clear()
        previousRoutes.putAll(next)
        initialized = true
    }

    private fun handleRouteReady(previous: RouteSnapshot, route: RouteSnapshot) {
        unavailableSinceByRouteId.remove(route.routeId)
        skippedUnavailableRouteIds.remove(route.routeId)
        if (previous.state != MessageSinkRouteState.READY && notifiedUnavailableRouteIds.remove(route.routeId)) {
            publishRouteRecovered(route)
        }
    }

    private fun handleRouteUnavailable(
        previous: RouteSnapshot,
        route: RouteSnapshot,
        currentRoutes: Collection<RouteSnapshot>,
        notificationConfig: NotificationConfig,
        now: Long,
        title: String,
    ) {
        val shouldTrack = previous.present && previous.state == MessageSinkRouteState.READY ||
            route.routeId in unavailableSinceByRouteId ||
            route.routeId in notifiedUnavailableRouteIds ||
            route.routeId in skippedUnavailableRouteIds
        if (!shouldTrack) return

        val unavailableSince = unavailableSinceByRouteId.getOrPut(route.routeId) { now }
        val notifyDelayMillis = notificationConfig.routeUnavailableNotifyDelaySeconds.coerceAtLeast(0) * 1_000L
        if (now - unavailableSince < notifyDelayMillis) return
        if (route.routeId in notifiedUnavailableRouteIds) return
        if (!canPublishRouteUnavailable(notificationConfig)) return

        val notificationTargets = reachableNotificationTargets(route, currentRoutes, notificationConfig)
        if (notificationTargets.isNotEmpty()) {
            publishRouteUnavailable(route, title, notificationTargets)
            notifiedUnavailableRouteIds += route.routeId
            skippedUnavailableRouteIds.remove(route.routeId)
            return
        }

        if (skippedUnavailableRouteIds.add(route.routeId)) {
            routeMonitorLogger.warn {
                "消息出口账号路线不可用，但没有其他可用系统通知路线，已跳过系统通知：routeId=${route.routeId}，pluginId=${route.pluginId}，accountId=${route.accountId}"
            }
        }
    }

    private fun canPublishRouteUnavailable(config: NotificationConfig): Boolean {
        return config.enabled && SystemNotificationSeverity.ERROR.satisfies(config.minSeverity)
    }

    private fun reachableNotificationTargets(
        unavailableRoute: RouteSnapshot,
        currentRoutes: Collection<RouteSnapshot>,
        config: NotificationConfig,
    ): List<TargetAddress> {
        val targets = config.adminTargets.filter { it.enabled }
        if (targets.isEmpty()) return emptyList()
        val readyRoutes = currentRoutes.filter { route ->
            route.routeId != unavailableRoute.routeId &&
                route.present &&
                route.enabled &&
                route.state == MessageSinkRouteState.READY
        }
        return targets
            .filter { target -> readyRoutes.any { route -> route.canReach(target) } }
            .map { target -> target.toTargetAddress() }
            .distinctBy { it.stableValue() }
    }

    private fun RouteSnapshot.canReach(target: NotificationTargetConfig): Boolean {
        val platform = target.platformId.trim().lowercase().takeIf { it.isNotBlank() } ?: return false
        if (targetPlatformId != platform) return false
        val accountId = target.accountId?.trim()?.takeIf { it.isNotBlank() }
        return accountId == null || accountId == this.accountId
    }

    private fun NotificationTargetConfig.toTargetAddress(): TargetAddress {
        return TargetAddress.of(
            platformId = platformId,
            kind = targetKind,
            externalId = externalId,
            scopeId = scopeId,
            threadId = threadId,
            accountId = accountId,
        )
    }

    private fun hadReadyRoute(pluginId: String): Boolean {
        return previousRoutes.values.any { route ->
            route.pluginId == pluginId && route.present && route.state == MessageSinkRouteState.READY
        }
    }

    private fun publishRouteListFailure(pluginId: String, error: Throwable) {
        eventBus.broadcast(
            SystemNotificationEvent(
                sourcePlugin = pluginId,
                notification = SystemNotificationPublishRequest(
                    type = "message_sink.route_list_failed",
                    severity = SystemNotificationSeverity.ERROR,
                    title = "消息出口状态读取失败",
                    content = "主项目读取消息出口账号路线状态失败，Bot 掉线监控本轮已跳过该插件。",
                    dedupeKey = "message_sink.route_list_failed:$pluginId",
                    details = mapOf(
                        "pluginId" to pluginId,
                        "error" to (error.message ?: error::class.qualifiedName.orEmpty()),
                    ),
                ),
            ),
        )
    }

    private fun publishRouteUnavailable(route: RouteSnapshot, title: String, targets: List<TargetAddress>) {
        eventBus.broadcast(
            SystemNotificationEvent(
                sourcePlugin = route.pluginId,
                targets = targets,
                notification = SystemNotificationPublishRequest(
                    type = "message_sink.route_unavailable",
                    severity = SystemNotificationSeverity.ERROR,
                    title = title,
                    content = "${route.targetPlatformId} 平台的 ${route.accountName} 已不可用，相关消息会按路由策略尝试切换到其他账号。",
                    dedupeKey = "message_sink.route_unavailable:${route.routeId}",
                    details = route.details(),
                ),
            ),
        )
    }

    private fun publishRouteRecovered(route: RouteSnapshot) {
        eventBus.broadcast(
            SystemNotificationEvent(
                sourcePlugin = route.pluginId,
                notification = SystemNotificationPublishRequest(
                    type = "message_sink.route_recovered",
                    severity = SystemNotificationSeverity.INFO,
                    title = "消息出口账号已恢复",
                    content = "${route.targetPlatformId} 平台的 ${route.accountName} 已恢复可用。",
                    dedupeKey = "message_sink.route_recovered:${route.routeId}",
                    details = route.details(),
                ),
            ),
        )
    }

    private data class RouteSnapshot(
        val routeId: String,
        val pluginId: String,
        val transportId: String,
        val transportName: String,
        val targetPlatformId: String,
        val accountId: String,
        val accountName: String,
        val enabled: Boolean,
        val state: MessageSinkRouteState,
        val present: Boolean,
    ) {
        fun details(): Map<String, String> = mapOf(
            "routeId" to routeId,
            "pluginId" to pluginId,
            "transportId" to transportId,
            "transportName" to transportName,
            "platformId" to targetPlatformId,
            "accountId" to accountId,
            "accountName" to accountName,
            "enabled" to enabled.toString(),
            "state" to state.name,
        )

        companion object {
            fun from(pluginId: String, route: MessageSinkRoute, present: Boolean): RouteSnapshot =
                RouteSnapshot(
                    routeId = route.routeId,
                    pluginId = pluginId,
                    transportId = route.transportId,
                    transportName = route.transportName,
                    targetPlatformId = route.targetPlatformId.value,
                    accountId = route.accountId,
                    accountName = route.accountName,
                    enabled = route.enabled,
                    state = route.state,
                    present = present,
                )
        }
    }
}
