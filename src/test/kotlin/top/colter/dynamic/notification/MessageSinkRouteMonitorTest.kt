package top.colter.dynamic.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
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
    fun `route monitor should notify when ready route becomes unavailable`() = runBlocking {
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
            eventBus = eventBus,
        )

        monitor.scan()
        sink.state = MessageSinkRouteState.UNAVAILABLE
        monitor.scan()

        val event = withTimeout(3_000) { received.await() }
        assertEquals("message_sink.route_unavailable", event.notification.type)
        assertEquals("onebot:qq:bot-a", event.notification.details["routeId"])
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
        var state: MessageSinkRouteState = MessageSinkRouteState.READY
        var failList: Boolean = false

        override val transportId: String = "onebot"
        override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP)

        override suspend fun listMessageSinkRoutes(target: TargetAddress?): List<MessageSinkRoute> {
            if (failList) error("route list failed")
            return listOf(
                MessageSinkRoute(
                    routeId = "onebot:qq:bot-a",
                    transportId = transportId,
                    targetPlatformId = PlatformId.of("qq"),
                    accountId = "bot-a",
                    accountName = "主 Bot",
                    state = state,
                ),
            )
        }

        override suspend fun sendMessage(request: MessageDeliveryRequest, routeId: String): MessageSendResult {
            return MessageSendResult.sent()
        }
    }
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
