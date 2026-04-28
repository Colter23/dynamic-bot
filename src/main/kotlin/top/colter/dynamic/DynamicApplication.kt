package top.colter.dynamic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.EventManger
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.plugin.PluginManager
import top.colter.dynamic.listener.DynamicListener

public object DynamicApplication : CoroutineScope {
    private val job: Job = Job()
    override val coroutineContext = Dispatchers.Default + job

    private val pluginManager: PluginManager = PluginManager(pluginDirPath = "plugins")

    public fun run() {
        EventManger.configureScope(this)
        registerCoreListeners()

        val loadResult = pluginManager.loadAllPlugins()
        if (loadResult.failedPlugins.isNotEmpty()) {
            loadResult.failedPlugins.forEach { (id, error) ->
                println("Plugin load failed: $id -> $error")
            }
        }
        pluginManager.initAndStartAllPlugins()

        println("DynamicApplication started. Loaded plugins: ${loadResult.loadedPlugins}")
    }

    private fun registerCoreListeners() {
        DynamicListener().register<DynamicEvent>()

        object : Listener<MessageEvent> {
            override suspend fun onMessage(event: MessageEvent) {
                println("message event received: ${event.message.did}")
                pluginManager.dispatchMessageToSubscribers(event)
            }
        }.register<MessageEvent>()
    }

    public fun shutdown() {
        pluginManager.shutdown()
        EventManger.shutdown()
        job.cancel()
    }
}
