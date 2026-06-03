package top.colter.dynamic.event

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventBusTest {

    class TestEvent(val value: String) : Event

    @Test
    fun registerBroadcastAndRemoveShouldWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventBus = EventBus()
        eventBus.configureScope(scope)

        val received = CompletableDeferred<String>()
        val listener = object : Listener<TestEvent> {
            override suspend fun onMessage(event: TestEvent) {
                received.complete(event.value)
            }
        }

        val token = eventBus.subscribe(listener)
        eventBus.broadcast(TestEvent("ok"))
        assertEquals("ok", received.await())

        assertTrue(eventBus.unsubscribe(token))
        assertFalse(eventBus.unsubscribe(token))

        scope.cancel()
        eventBus.shutdown()
    }

    @Test
    fun concurrentRegisterAndBroadcastShouldNotLoseAllEvents() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventBus = EventBus()
        eventBus.configureScope(scope)

        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val jobs = (1..20).map {
            launch {
                val listener = object : Listener<TestEvent> {
                    override suspend fun onMessage(event: TestEvent) {
                        count.incrementAndGet()
                    }
                }
                eventBus.subscribe(listener)
            }
        }
        jobs.forEach { it.join() }

        eventBus.broadcast(TestEvent("batch"))
        delay(200)

        assertTrue(count.get() >= 20)

        scope.cancel()
        eventBus.shutdown()
    }

    @Test
    fun broadcastShouldDropEventsWhenInFlightLimitIsReached() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventBus = EventBus(maxInFlightEvents = 1)
        eventBus.configureScope(scope)

        val release = CompletableDeferred<Unit>()
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        eventBus.subscribe(
            object : Listener<TestEvent> {
                override suspend fun onMessage(event: TestEvent) {
                    count.incrementAndGet()
                    release.await()
                }
            }
        )

        eventBus.broadcast(TestEvent("first"))
        withTimeout(1_000) {
            while (count.get() == 0) delay(10)
        }
        eventBus.broadcast(TestEvent("second"))
        delay(100)

        assertEquals(1, count.get())

        release.complete(Unit)
        scope.cancel()
        eventBus.shutdown()
    }
}
