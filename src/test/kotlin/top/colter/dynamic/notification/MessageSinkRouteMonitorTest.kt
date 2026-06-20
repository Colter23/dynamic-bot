package top.colter.dynamic.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.NotificationConfig
import top.colter.dynamic.NotificationTargetConfig
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.event.SystemNotificationEvent
import top.colter.dynamic.plugin.PluginCapability
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginState

class MessageSinkRouteMonitorTest {
    @Test
    fun `route monitor should notify when ready route stays unavailable after delay and backup route can notify`() = runBlocking {
        val eventBus = EventBus()
        eventBus.configureScope(this)
        val received = CompletableDeferred<SystemNotificationEvent>()
        eventBus.subscribe(
            object : Listener<SystemNotificationEvent> {
                override suspend fun onMessage(event: SystemNotificationEvent) {
                    received.complete(event)
                }
            },
        )
        var now = 0L
        val sink = MutableRouteSink().apply {
            routes["bot-b"] = MessageSinkRouteState.READY
        }
        val monitor = MessageSinkRouteMonitor(
            sinkProvider = { listOf(sink.handleForTest()) },
            notificationConfigProvider = { notificationConfig(routeUnavailableNotifyDelaySeconds = 120) },
            eventBus = eventBus,
            nowEpochMillis = { now },
        )

        monitor.scan()
        sink.state = MessageSinkRouteState.UNAVAILABLE
        monitor.scan()
        delay(100)
        assertFalse(received.isCompleted)
        now = 119_000
        monitor.scan()
        delay(100)
        assertFalse(received.isCompleted)
        now = 120_000
        monitor.scan()

        val event = withTimeout(3_000) { received.await() }
        assertEquals("message_sink.route_unavailable", event.notification.type)
        assertEquals("onebot:qq:bot-a", event.notification.details["routeId"])
        eventBus.shutdown()
    }

    @Test
    fun `route monitor should not enqueue unavailable notification when no other route can notify`() = runBlocking {
        val eventBus = EventBus()
        eventBus.configureScope(this)
        val received = CompletableDeferred<SystemNotificationEvent>()
        eventBus.subscribe(
            object : Listener<SystemNotificationEvent> {
                override suspend fun onMessage(event: SystemNotificationEvent) {
                    received.complete(event)
                }
            },
        )
        val sink = MutableRouteSink()
        val monitor = MessageSinkRouteMonitor(
            sinkProvider = { listOf(sink.handleForTest()) },
            notificationConfigProvider = { notificationConfig(routeUnavailableNotifyDelaySeconds = 0) },
            eventBus = eventBus,
            nowEpochMillis = { 0L },
        )

        monitor.scan()
        sink.state = MessageSinkRouteState.UNAVAILABLE
        monitor.scan()
        delay(100)

        assertFalse(received.isCompleted)
        eventBus.shutdown()
    }

    @Test
    fun `route monitor should restrict unavailable notification to reachable admin targets`() = runBlocking {
        val eventBus = EventBus()
        eventBus.configureScope(this)
        val received = CompletableDeferred<SystemNotificationEvent>()
        eventBus.subscribe(
            object : Listener<SystemNotificationEvent> {
                override suspend fun onMessage(event: SystemNotificationEvent) {
                    received.complete(event)
                }
            },
        )
        val sink = MutableRouteSink().apply {
            routes["bot-b"] = MessageSinkRouteState.READY
        }
        val monitor = MessageSinkRouteMonitor(
            sinkProvider = { listOf(sink.handleForTest()) },
            notificationConfigProvider = {
                notificationConfig(
                    routeUnavailableNotifyDelaySeconds = 0,
                    adminTargets = listOf(
                        NotificationTargetConfig("qq", TargetKind.USER, "10001", accountId = "bot-a"),
                        NotificationTargetConfig("qq", TargetKind.USER, "10002", accountId = "bot-b"),
                        NotificationTargetConfig("qq", TargetKind.USER, "10003"),
                        NotificationTargetConfig("telegram", TargetKind.USER, "10004"),
                    ),
                )
            },
            eventBus = eventBus,
            nowEpochMillis = { 0L },
        )

        monitor.scan()
        sink.state = MessageSinkRouteState.UNAVAILABLE
        monitor.scan()

        val event = withTimeout(3_000) { received.await() }
        assertEquals(
            listOf(
                TargetAddress.of("qq", TargetKind.USER, "10002", accountId = "bot-b").stableValue(),
                TargetAddress.of("qq", TargetKind.USER, "10003").stableValue(),
            ),
            event.targets.orEmpty().map { it.stableValue() },
        )
        eventBus.shutdown()
    }

    @Test
    fun `route monitor should publish recovery only after unavailable notification was sent`() = runBlocking {
        val eventBus = EventBus()
        eventBus.configureScope(this)
        val events = mutableListOf<SystemNotificationEvent>()
        eventBus.subscribe(
            object : Listener<SystemNotificationEvent> {
                override suspend fun onMessage(event: SystemNotificationEvent) {
                    events += event
                }
            },
        )
        var now = 0L
        val sink = MutableRouteSink().apply {
            routes["bot-b"] = MessageSinkRouteState.READY
        }
        val monitor = MessageSinkRouteMonitor(
            sinkProvider = { listOf(sink.handleForTest()) },
            notificationConfigProvider = { notificationConfig(routeUnavailableNotifyDelaySeconds = 10) },
            eventBus = eventBus,
            nowEpochMillis = { now },
        )

        monitor.scan()
        sink.state = MessageSinkRouteState.UNAVAILABLE
        monitor.scan()
        sink.state = MessageSinkRouteState.READY
        monitor.scan()
        delay(100)
        assertEquals(emptyList(), events.map { it.notification.type })

        sink.state = MessageSinkRouteState.UNAVAILABLE
        monitor.scan()
        now = 10_000
        monitor.scan()
        sink.state = MessageSinkRouteState.READY
        monitor.scan()
        withTimeout(3_000) {
            while (events.size < 2) delay(10)
        }

        assertEquals(
            listOf("message_sink.route_unavailable", "message_sink.route_recovered"),
            events.map { it.notification.type },
        )
        eventBus.shutdown()
    }

    @Test
    fun `route monitor should not notify for routes unavailable before first ready state`() = runBlocking {
        val eventBus = EventBus()
        eventBus.configureScope(this)
        val received = CompletableDeferred<SystemNotificationEvent>()
        eventBus.subscribe(
            object : Listener<SystemNotificationEvent> {
                override suspend fun onMessage(event: SystemNotificationEvent) {
                    received.complete(event)
                }
            },
        )
        val sink = MutableRouteSink().apply {
            state = MessageSinkRouteState.UNAVAILABLE
        }
        val monitor = MessageSinkRouteMonitor(
            sinkProvider = { listOf(sink.handleForTest()) },
            notificationConfigProvider = { notificationConfig(routeUnavailableNotifyDelaySeconds = 0) },
            eventBus = eventBus,
        )

        monitor.scan()
        sink.state = MessageSinkRouteState.READY
        monitor.scan()
        delay(100)

        assertFalse(received.isCompleted)
        eventBus.shutdown()
    }

    @Test
    fun `route monitor should not notify repeated route list failures before first ready state`() = runBlocking {
        val eventBus = EventBus()
        eventBus.configureScope(this)
        val received = CompletableDeferred<SystemNotificationEvent>()
        eventBus.subscribe(
            object : Listener<SystemNotificationEvent> {
                override suspend fun onMessage(event: SystemNotificationEvent) {
                    received.complete(event)
                }
            },
        )
        val sink = MutableRouteSink().apply {
            failList = true
        }
        val monitor = MessageSinkRouteMonitor(
            sinkProvider = { listOf(sink.handleForTest()) },
            eventBus = eventBus,
        )

        monitor.scan()
        monitor.scan()
        delay(100)

        assertFalse(received.isCompleted)
        eventBus.shutdown()
    }

    private class MutableRouteSink : AccountRoutedMessageSinkPlugin {
        val routes: MutableMap<String, MessageSinkRouteState> = linkedMapOf("bot-a" to MessageSinkRouteState.READY)
        var state: MessageSinkRouteState
            get() = routes.getValue("bot-a")
            set(value) {
                routes["bot-a"] = value
            }
        var failList: Boolean = false

        override val transportId: String = "onebot"
        override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP)

        override suspend fun listMessageSinkRoutes(target: TargetAddress?): List<MessageSinkRoute> {
            if (failList) error("route list failed")
            return routes.map { (accountId, state) ->
                MessageSinkRoute(
                    routeId = "onebot:qq:$accountId",
                    transportId = transportId,
                    targetPlatformId = PlatformId.of("qq"),
                    accountId = accountId,
                    accountName = if (accountId == "bot-a") "主 Bot" else "备用 Bot",
                    state = state,
                )
            }
        }

        override suspend fun sendMessage(request: MessageSendRequest, routeId: String): MessageSendResult {
            return MessageSendResult.sent()
        }
    }
}

private fun notificationConfig(
    routeUnavailableNotifyDelaySeconds: Int,
    adminTargets: List<NotificationTargetConfig> = listOf(NotificationTargetConfig("qq", TargetKind.USER, "10001")),
): NotificationConfig {
    return NotificationConfig(
        routeUnavailableNotifyDelaySeconds = routeUnavailableNotifyDelaySeconds,
        adminTargets = adminTargets,
    )
}

private fun MessageSinkPlugin.handleForTest(): PluginHandle<MessageSinkPlugin> {
    return PluginHandle(
        info = PluginInfo(
            descriptor = PluginDescriptor(
                id = transportId,
                name = transportId,
                version = "test",
                mainClass = this::class.qualifiedName.orEmpty(),
            ),
            capabilities = setOf(PluginCapability.MESSAGE_SINK),
            state = PluginState.ACTIVE,
            sourceJarPath = "test.jar",
        ),
        instance = this,
    )
}
