package top.colter.dynamic.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberState
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testTargetAddress

class PluginManagerSubscriptionEventTest {
    private val eventBus = EventBus()

    @AfterTest
    fun cleanup() {
        eventBus.shutdown()
    }

    @Test
    fun shouldDispatchSubscriptionChangesOnlyToActiveSourcePlugins() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        eventBus.configureScope(scope)
        val manager = PluginManager(
            pluginDirPath = kotlin.io.path.createTempDirectory("plugin-manager-events").toString(),
            scope = scope,
            eventBus = eventBus,
        )
        val activeSource = FakeSourcePlugin()
        val loadedSource = FakeSourcePlugin()

        manager.registerPluginForTest(descriptor("active-source", activeSource), activeSource, state = PluginState.ACTIVE)
        manager.registerPluginForTest(descriptor("loaded-source", loadedSource), loadedSource, state = PluginState.LOADED)
        manager.startPlugin("active-source")

        val event = demoEvent()
        eventBus.broadcast(event)

        assertEquals(event, withTimeout(3_000) { activeSource.events.receive() })
        assertEquals(null, withTimeoutOrNull(300) { loadedSource.events.receive() })

        manager.shutdown()
        eventBus.broadcast(event)

        assertEquals(null, withTimeoutOrNull(300) { activeSource.events.receive() })
        scope.cancel()
    }

    private fun descriptor(id: String, plugin: Plugin): PluginDescriptor {
        return PluginDescriptor(
            id = id,
            name = id,
            version = "test",
            mainClass = plugin::class.java.name,
        )
    }

    private fun demoEvent(): SubscriptionChangedEvent {
        val publisher = testPublisher(id = 1)
        val subscriber = Subscriber(
            id = 2,
            address = testTargetAddress(kind = TargetKind.GROUP, externalId = "100"),
            name = "group",
            state = SubscriberState.ACTIVE,
            createTime = 1,
            createUser = 1,
        )
        return SubscriptionChangedEvent(
            changeType = SubscriptionChangeType.SUBSCRIBED,
            subscription = Subscription(
                id = 3,
                subscriberId = subscriber.id,
                publisherId = publisher.id,
                createdAtEpochSeconds = 100,
                updatedAtEpochSeconds = 100,
            ),
            publisher = publisher,
            subscriber = subscriber,
            changedAtEpochSeconds = 100,
        )
    }

    private class FakeSourcePlugin : PublisherSourcePlugin {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        val events: Channel<SubscriptionChangedEvent> = Channel(Channel.UNLIMITED)

        override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
            events.send(event)
        }
    }
}
