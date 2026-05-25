package top.colter.dynamic

import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
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
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.draw.DynamicImageCache
import top.colter.dynamic.listener.DynamicListener
import top.colter.dynamic.listener.ImageFileCleaner

public object DynamicApplication : CoroutineScope {
    private val job: Job = Job()
    override val coroutineContext = Dispatchers.Default + job

    private val pluginManager: PluginManager = PluginManager(pluginDirPath = "plugins")
    private val listenerTokens: MutableList<ListenerToken> = mutableListOf()
    private val taskScheduler: TaskScheduler = TaskScheduler(scope = this)

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
        val config = DefaultConfigService.loadOrCreate(MainDynamicConfig.CONFIG_ID) { MainDynamicConfig() }
        DynamicImageCache.configure(Paths.get(config.imageCache.sourceRoot))
        registerImageCleanupTask(config)

        listenerTokens += DynamicListener(config = config).register<DynamicEvent>()
        listenerTokens += CommandListener(
            config = config,
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

    private fun registerImageCleanupTask(config: MainDynamicConfig) {
        val imageCacheConfig = config.imageCache
        if (!imageCacheConfig.sourceCleanup.enabled && !imageCacheConfig.renderedCleanup.enabled) return

        taskScheduler.start(
            TaskDefinition(
                id = "main-image-cleanup",
                schedule = TaskSchedule.Cron(imageCacheConfig.cleanupCron),
                action = {
                    val cleaner = ImageFileCleaner()
                    if (imageCacheConfig.sourceCleanup.enabled) {
                        val result = cleaner.clean(
                            Paths.get(imageCacheConfig.sourceRoot),
                            imageCacheConfig.sourceCleanup.maxIdleDays,
                        )
                        println("image cleanup source: deletedFiles=${result.deletedFiles}, deletedBytes=${result.deletedBytes}")
                    }
                    if (imageCacheConfig.renderedCleanup.enabled) {
                        val result = cleaner.clean(
                            Paths.get(imageCacheConfig.renderedRoot),
                            imageCacheConfig.renderedCleanup.maxIdleDays,
                        )
                        println("image cleanup rendered: deletedFiles=${result.deletedFiles}, deletedBytes=${result.deletedBytes}")
                    }
                },
            )
        )
    }

    public fun shutdown() {
        listenerTokens.forEach { EventManger.removeListener(it) }
        listenerTokens.clear()
        runBlocking {
            taskScheduler.stopAll()
        }
        pluginManager.shutdown()
        EventManger.shutdown()
        job.cancel()
    }
}
