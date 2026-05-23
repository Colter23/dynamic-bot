package top.colter.dynamic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.EventManger
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.ListenerToken
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.plugin.PluginManager
import top.colter.dynamic.core.plugin.PluginState
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.listener.DynamicListener

public object DynamicApplication : CoroutineScope {
    private val job: Job = Job()
    override val coroutineContext = Dispatchers.Default + job

    private val pluginManager: PluginManager = PluginManager(pluginDirPath = "plugins")
    private val listenerTokens: MutableList<ListenerToken> = mutableListOf()

    public fun run() {
        val dbPath = "data/dynamic.db"
        try {
            PersistenceManager.init(dbPath)
            println("Database initialized: $dbPath")
        } catch (e: Exception) {
            println("Database initialization failed: path=$dbPath, error=${e.message}")
            throw e
        }

        EventManger.configureScope(this)
        registerCoreListeners()

        val loadResult = pluginManager.loadAllPlugins()
        if (loadResult.failedPlugins.isNotEmpty()) {
            loadResult.failedPlugins.forEach { (id, error) ->
                println("Plugin load failed: $id -> $error")
            }
        }
        pluginManager.initAndStartAllPlugins()

        pluginManager.getAllPlugins()
            .filter { it.state == PluginState.FAILED }
            .forEach { info ->
                val error = info.error?.message ?: info.error?.javaClass?.name ?: "Unknown error"
                println("Plugin start failed: ${info.descriptor.id} -> $error")
            }

        println("DynamicApplication started. Loaded plugins: ${loadResult.loadedPlugins}")
    }

    private fun registerCoreListeners() {
        listenerTokens += DynamicListener().register<DynamicEvent>()
        listenerTokens += CommandListener(
            platformPluginResolver = { platformId -> pluginManager.findPlatformPublisherPlugin(platformId) }
        ).register<CommandEvent>()

        listenerTokens += object : Listener<MessageEvent> {
            override suspend fun onMessage(event: MessageEvent) {
                println("message event received: ${event.message.id}")
                pluginManager.dispatchMessageToSinks(event)
            }
        }.register<MessageEvent>()

        listenerTokens += object : Listener<CommandResultEvent> {
            override suspend fun onMessage(event: CommandResultEvent) {
                pluginManager.dispatchCommandResultToSinks(event)
            }
        }.register<CommandResultEvent>()
    }

    public fun shutdown() {
        listenerTokens.forEach { EventManger.removeListener(it) }
        listenerTokens.clear()
        pluginManager.shutdown()
        EventManger.shutdown()
        job.cancel()
    }
}
