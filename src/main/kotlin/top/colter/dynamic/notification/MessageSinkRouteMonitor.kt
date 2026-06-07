package top.colter.dynamic.notification

import java.util.concurrent.ConcurrentHashMap
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
    private val eventBus: EventBus,
) {
    private val previousRoutes: MutableMap<String, RouteSnapshot> = ConcurrentHashMap()
    private val notifiedUnavailableRouteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var initialized: Boolean = false

    public suspend fun scan() {
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
                    if (previous.present && previous.state == MessageSinkRouteState.READY) {
                        publishRouteUnavailable(missing, "消息出口账号路线已消失")
                        notifiedUnavailableRouteIds += previous.routeId
                    }
                    return@forEach
                }

                if (previous.state != latest.state) {
                    when (latest.state) {
                        MessageSinkRouteState.READY -> {
                            if (notifiedUnavailableRouteIds.remove(latest.routeId)) {
                                publishRouteRecovered(latest)
                            }
                        }
                        MessageSinkRouteState.UNAVAILABLE -> {
                            publishRouteUnavailable(latest, "消息出口账号路线不可用")
                            notifiedUnavailableRouteIds += latest.routeId
                        }
                    }
                }
            }
        }

        notifiedUnavailableRouteIds.retainAll(next.keys)
        previousRoutes.clear()
        previousRoutes.putAll(next)
        initialized = true
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

    private fun publishRouteUnavailable(route: RouteSnapshot, title: String) {
        eventBus.broadcast(
            SystemNotificationEvent(
                sourcePlugin = route.pluginId,
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
                    state = route.state,
                    present = present,
                )
        }
    }
}
