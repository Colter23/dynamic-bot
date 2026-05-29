package top.colter.dynamic

import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.admin.AdminServer
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.EventBus
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.ListenerToken
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.SourceUpdateEvent
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.plugin.PluginManager
import top.colter.dynamic.core.plugin.PluginState
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.link.DynamicLinkAutoParseListener
import top.colter.dynamic.link.DynamicLinkForwarder
import top.colter.dynamic.listener.ImageFileCleaner
import top.colter.dynamic.listener.SourceUpdateListener

private val logger = loggerFor<DynamicApplication>()

public object DynamicApplication : CoroutineScope {
    private val job: Job = Job()
    override val coroutineContext = Dispatchers.Default + job

    private val eventBus: EventBus = EventBus.global
    private val pluginManager: PluginManager = PluginManager(pluginDirPath = "plugins", eventBus = eventBus)
    private val listenerTokens: MutableList<ListenerToken> = mutableListOf()
    private val taskScheduler: TaskScheduler = TaskScheduler(scope = this)
    private val configStore: MainConfigStore = MainConfigStore()
    private val shutdownStarted: AtomicBoolean = AtomicBoolean(false)
    @Volatile
    private var shutdownCallback: (() -> Unit)? = null
    private var adminServer: AdminServer? = null

    public fun onShutdown(callback: () -> Unit) {
        shutdownCallback = callback
    }

    public fun run() {
        val dbPath = "data/dynamic.db"
        try {
            PersistenceManager.init(dbPath)
            logger.info { "数据库已初始化：$dbPath" }
        } catch (e: Exception) {
            logger.error(e) { "数据库初始化失败：$dbPath" }
            throw e
        }

        eventBus.configureScope(this)
        val config = configStore.loadOrCreate { generateAdminToken() }
        registerCoreListeners(config)

        val loadResult = pluginManager.loadAllPlugins()
        if (loadResult.failedPlugins.isNotEmpty()) {
            logger.warn { "部分插件加载失败：pluginIds=${loadResult.failedPlugins.keys.sorted()}" }
        }
        pluginManager.startAllPlugins()

        val failedPluginIds = pluginManager.getAllPlugins()
            .filter { it.state == PluginState.FAILED }
            .map { it.descriptor.id }
            .sorted()
        if (failedPluginIds.isNotEmpty()) {
            logger.warn { "部分插件启动失败：pluginIds=$failedPluginIds" }
        }

        startAdminServer(config)

        logger.info { "主项目已启动，已加载插件=${loadResult.loadedPlugins}" }
    }

    private fun registerCoreListeners(config: MainDynamicConfig) {
        DynamicImageCache.configure(Paths.get(config.imageCache.sourceRoot))
        registerImageCleanupTask(config)

        val dynamicLinkForwarder = DynamicLinkForwarder {
            pluginManager.getDynamicLinkResolvers()
        }

        listenerTokens += SourceUpdateListener(configProvider = configStore::current).register<SourceUpdateEvent>(eventBus)
        listenerTokens += CommandListener(
            configProvider = configStore::current,
            dynamicLinkForwarder = dynamicLinkForwarder,
            publisherLookupResolver = { platformId -> pluginManager.findPublisherLookupPlugin(platformId) },
            publisherFollowResolver = { platformId -> pluginManager.findPublisherFollowPlugin(platformId) },
            publisherLoginResolver = { platformId -> pluginManager.findPublisherLoginProvider(platformId) },
            stopRequester = { reason -> requestStop(reason) },
        ).register<CommandEvent>(eventBus)
        listenerTokens += DynamicLinkAutoParseListener(
            configProvider = configStore::current,
            forwarder = dynamicLinkForwarder,
        ).register<CommandEvent>(eventBus)

        listenerTokens += object : Listener<MessageEvent> {
            override suspend fun onMessage(event: MessageEvent) {
                logger.debug { "分发推送消息：messageId=${event.message.id}，目标数=${event.message.targets.size}" }
                pluginManager.dispatchMessageToSinks(event)
            }
        }.register<MessageEvent>(eventBus)

        listenerTokens += object : Listener<CommandResultEvent> {
            override suspend fun onMessage(event: CommandResultEvent) {
                pluginManager.dispatchCommandResultToSinks(event)
            }
        }.register<CommandResultEvent>(eventBus)
    }

    private fun startAdminServer(config: MainDynamicConfig) {
        if (!config.webAdmin.enabled) return
        val server = AdminServer(
            config = config.webAdmin,
            pluginManager = pluginManager,
            configProvider = configStore::current,
            mainConfigUpdater = configStore::save,
            stopRequester = { reason -> requestStop(reason) },
        )
        server.start()
        adminServer = server
        logger.info { "管理后台已启动：http://${config.webAdmin.host}:${config.webAdmin.port}/admin" }
    }

    private fun generateAdminToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
                        if (result.deletedFiles > 0) {
                            logger.info { "原图缓存已清理：文件=${result.deletedFiles}，字节=${result.deletedBytes}" }
                        } else {
                            logger.debug { "原图缓存无需清理" }
                        }
                    }
                    if (imageCacheConfig.renderedCleanup.enabled) {
                        val result = cleaner.clean(
                            Paths.get(imageCacheConfig.renderedRoot),
                            imageCacheConfig.renderedCleanup.maxIdleDays,
                        )
                        if (result.deletedFiles > 0) {
                            logger.info { "渲染图缓存已清理：文件=${result.deletedFiles}，字节=${result.deletedBytes}" }
                        } else {
                            logger.debug { "渲染图缓存无需清理" }
                        }
                    }
                },
            )
        )
    }

    public fun requestStop(reason: String) {
        Thread(
            {
                logger.info { "收到停止请求：$reason" }
                runCatching { Thread.sleep(500) }
                shutdown()
            },
            "dynamic-application-stop",
        ).start()
    }

    public fun shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) return
        try {
            adminServer?.stop()
            adminServer = null
            listenerTokens.forEach { eventBus.removeListener(it) }
            listenerTokens.clear()
            runBlocking {
                taskScheduler.stopAll()
            }
            pluginManager.shutdown()
            eventBus.shutdown()
            job.cancel()
            logger.info { "应用已关闭" }
        } finally {
            shutdownCallback?.invoke()
        }
    }
}
