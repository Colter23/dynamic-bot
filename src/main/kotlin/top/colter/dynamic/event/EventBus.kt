package top.colter.dynamic.event

import io.github.oshai.kotlinlogging.KLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.colter.dynamic.core.tools.loggerFor

@PublishedApi
internal val eventBusLogger: KLogger = loggerFor<EventBus>()

public class EventBus(
    parentContext: CoroutineContext = Dispatchers.Default,
) {
    public data class ListenerEntry(
        val token: ListenerToken,
        val listener: Listener<Any>,
    )

    @PublishedApi
    internal val eventMap: MutableMap<String, CopyOnWriteArrayList<ListenerEntry>> = ConcurrentHashMap()
    private val scopeLock: Any = Any()
    private var parentContext: CoroutineContext = parentContext

    @PublishedApi
    internal var eventScope: CoroutineScope = createScope(parentContext)

    @Suppress("UNCHECKED_CAST")
    public inline fun <reified E : Any> subscribe(listener: Listener<E>): ListenerToken {
        val eventName = eventKey<E>()
        val token = ListenerToken(UUID.randomUUID().toString(), eventName)
        eventMap
            .computeIfAbsent(eventName) { CopyOnWriteArrayList() }
            .add(ListenerEntry(token, listener as Listener<Any>))
        return token
    }

    public fun unsubscribe(token: ListenerToken): Boolean {
        val listeners = eventMap[token.eventName] ?: return false
        val removed = listeners.removeIf { it.token == token }
        if (listeners.isEmpty()) {
            eventMap.remove(token.eventName)
        }
        return removed
    }

    public fun configureScope(scope: CoroutineScope) {
        synchronized(scopeLock) {
            parentContext = scope.coroutineContext
            eventScope.cancel("事件总线 scope 已替换")
            eventScope = createScope(parentContext)
        }
    }

    public fun shutdown() {
        synchronized(scopeLock) {
            eventScope.cancel("事件总线已关闭")
            eventMap.clear()
            parentContext = Dispatchers.Default
            eventScope = createScope(parentContext)
        }
    }

    public inline fun <reified E : Any> broadcast(event: E) {
        val eventName = eventKey<E>()
        eventMap[eventName]?.forEach { entry ->
            eventScope.launch {
                runCatching { entry.listener.onMessage(event) }
                    .onFailure {
                        eventBusLogger.error(it) {
                            "事件分发失败：event=$eventName，listener=${entry.token.id}"
                        }
                    }
            }
        }
    }

    public inline fun <reified E : Any> listenerCount(): Int {
        return eventMap[eventKey<E>()]?.size ?: 0
    }

    @PublishedApi
    internal inline fun <reified E : Any> eventKey(): String {
        return E::class.qualifiedName ?: E::class.java.name
    }

    private fun createScope(baseContext: CoroutineContext): CoroutineScope {
        val parentJob = baseContext[Job]
        val job = if (parentJob != null) SupervisorJob(parentJob) else SupervisorJob()
        return CoroutineScope(baseContext + job)
    }
}

public data class ListenerToken(
    val id: String,
    val eventName: String,
)

public interface Listener<in E : Any> {
    public suspend fun onMessage(event: E)
}
