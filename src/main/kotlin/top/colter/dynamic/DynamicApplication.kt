package top.colter.dynamic

import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import top.colter.dynamic.admin.AdminLogging
import top.colter.dynamic.admin.AdminServer
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.command.CommandPublisher
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.event.ListenerToken
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.plugin.PluginManager
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.task.DefaultTaskScheduler
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.draw.image.ImageFileCleaner
import top.colter.dynamic.draw.DefaultDynamicDrawService
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry
import top.colter.dynamic.listener.DeliveryDispatcher
import top.colter.dynamic.link.LinkAutoParseListener
import top.colter.dynamic.link.DeliveryLinkParseProgressMessenger
import top.colter.dynamic.link.DefaultLinkPreviewRenderer
import top.colter.dynamic.link.LinkParseService
import top.colter.dynamic.listener.SourceUpdateProcessor
import top.colter.dynamic.repository.MessageDeliveryRepository

private val logger = loggerFor<DynamicApplication>()

public object DynamicApplication : CoroutineScope {
    private val job: Job = Job()
    override val coroutineContext = Dispatchers.Default + job

    private val eventBus: EventBus = EventBus()
    private val configService: ConfigService = YamlConfigService()
    private val commandRegistry: CommandRegistry = CommandRegistry()
    private val drawAssetRegistry: PlatformDrawAssetRegistry = PlatformDrawAssetRegistry()
    private lateinit var sourceUpdateProcessor: SourceUpdateProcessor
    private lateinit var deliveryDispatcher: DeliveryDispatcher
    private val commandPublisher: CommandPublisher = CommandPublisher { request ->
        eventBus.broadcast(
            CommandEvent(
                sourcePlugin = request.sourcePlugin,
                context = request.context,
                rawText = request.rawText,
                traceId = request.traceId,
            ),
        )
    }
    private val sourceUpdatePublisher: SourceUpdatePublisher = SourceUpdatePublisher { request ->
        if (::sourceUpdateProcessor.isInitialized) {
            sourceUpdateProcessor.process(request)
        } else {
            SourceUpdatePublishResult.failed("主项目来源更新处理器尚未初始化")
        }
    }
    private val pluginManager: PluginManager = PluginManager(
        pluginDirPath = "plugins",
        scope = this,
        eventBus = eventBus,
        configService = configService,
        commandRegistry = commandRegistry,
        commandPublisher = commandPublisher,
        sourceUpdatePublisher = sourceUpdatePublisher,
        drawAssetRegistry = drawAssetRegistry,
    )
    private val listenerTokens: MutableList<ListenerToken> = mutableListOf()
    private val taskScheduler: DefaultTaskScheduler = DefaultTaskScheduler(scope = this)
    private val configStore: MainConfigStore = MainConfigStore(configService)
    private val shutdownStarted: AtomicBoolean = AtomicBoolean(false)
    private val startedAtEpochMillis: Long = System.currentTimeMillis()

    @Volatile
    private var shutdownCallback: (() -> Unit)? = null
    private var adminServer: AdminServer? = null

    public fun onShutdown(callback: () -> Unit) {
        shutdownCallback = callback
    }

    public fun run() {
        AdminLogging.install()
        val dbPath = "data/dynamic.db"
        try {
            PersistenceManager.init(dbPath)
            logger.info { "数据库已初始化：$dbPath" }
        } catch (e: Exception) {
            logger.error(e) { "数据库初始化失败：$dbPath" }
            throw e
        }

        eventBus.configureScope(this)
        var generatedAdminToken: String? = null
        val config = configStore.loadOrCreate {
            generateAdminToken().also { generatedAdminToken = it }
        }
        generatedAdminToken?.let { token ->
            logger.info {
                "Web 后台 Token 已自动生成：$token，请妥善保存；也可在主配置 webAdmin.token 中查看或修改"
            }
        }
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

        runBlocking { deliveryDispatcher.dispatchDue() }
        startAdminServer(config)

        logger.info { "主项目已启动，已加载插件=${loadResult.loadedPlugins}" }
    }

    private fun registerCoreListeners(config: MainDynamicConfig) {
        DynamicImageCache.configure(
            sourceRoot = Paths.get(config.imageCache.sourceRoot),
            maxMemoryBytes = config.imageCache.memoryMaxBytes,
            maxMemoryEntries = config.imageCache.memoryMaxEntries,
            maxReadBytes = config.imageCache.maxImageBytes,
        )
        registerImageCleanupTask(config)
        deliveryDispatcher = DeliveryDispatcher(
            sinkProvider = { pluginManager.getMessageSinkPlugins() },
            configProvider = { configStore.current().delivery },
            routingConfigProvider = { configStore.current().messageRouting },
        )
        registerDeliveryDispatchTask(config)
        registerDeliveryCleanupTask(config)
        sourceUpdateProcessor = SourceUpdateProcessor(
            configProvider = configStore::current,
            configService = configService,
            eventBus = eventBus,
            drawService = DefaultDynamicDrawService(
                configProvider = configStore::current,
                assetResolver = drawAssetRegistry,
            ),
            broadcastMessages = false,
            onDeliveriesQueued = {
                deliveryDispatcher.dispatchDue()
            },
        )

        val linkParseService = LinkParseService(
            resolversProvider = { pluginManager.getLinkResolvers() },
            sourceUpdatePublisher = sourceUpdatePublisher,
            publisherLookupResolver = { platformId -> pluginManager.findPublisherLookupPlugin(platformId) },
            previewRenderer = DefaultLinkPreviewRenderer(
                configProvider = configStore::current,
                drawService = DefaultDynamicDrawService(
                    configProvider = configStore::current,
                    assetResolver = drawAssetRegistry,
                ),
            ),
            onMessagesQueued = {
                deliveryDispatcher.dispatchDue()
            },
        )

        listenerTokens += eventBus.subscribe(
            CommandListener(
                configProvider = configStore::current,
                linkParseService = linkParseService,
                publisherLookupResolver = { platformId -> pluginManager.findPublisherLookupPlugin(platformId) },
                publisherFollowResolver = { platformId -> pluginManager.findPublisherFollowPlugin(platformId) },
                publisherLoginResolver = { platformId -> pluginManager.findPublisherLoginProvider(platformId) },
                stopRequester = { reason -> requestStop(reason) },
                configService = configService,
                commandRegistry = commandRegistry,
                eventBus = eventBus,
                primaryBotAccountResolver = ::resolvePrimaryCommandBotAccount,
            ),
        )
        listenerTokens += eventBus.subscribe(
            LinkAutoParseListener(
                configProvider = configStore::current,
                linkParseService = linkParseService,
                eventBus = eventBus,
                progressMessenger = DeliveryLinkParseProgressMessenger(
                    sendCommandResult = deliveryDispatcher::sendCommandResult,
                    recallMessage = deliveryDispatcher::recallMessage,
                ),
                primaryBotAccountResolver = ::resolvePrimaryCommandBotAccount,
            ),
        )

        listenerTokens += eventBus.subscribe(
            object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) {
                    deliveryDispatcher.dispatchCommandResult(event)
                }
            },
        )
    }

    private fun startAdminServer(config: MainDynamicConfig) {
        if (!config.webAdmin.enabled) return
        val server = AdminServer(
            config = config.webAdmin,
            pluginManager = pluginManager,
            configProvider = configStore::current,
            mainConfigUpdater = configStore::save,
            configService = configService,
            commandRegistry = commandRegistry,
            eventBus = eventBus,
            drawAssetRegistry = drawAssetRegistry,
            stopRequester = { reason -> requestStop(reason) },
            startedAtEpochMillis = startedAtEpochMillis,
        )
        server.start()
        adminServer = server
        logger.info { "管理后台已启动：http://${config.webAdmin.host}:${config.webAdmin.port}" }
    }

    private suspend fun resolvePrimaryCommandBotAccount(context: CommandContext): String? {
        val target = context.target
        val routes = pluginManager.getMessageSinkPlugins()
            .flatMap { handle ->
                val sink = handle.instance as? AccountRoutedMessageSinkPlugin ?: return@flatMap emptyList()
                if (!sink.supportsTarget(target)) return@flatMap emptyList()
                runCatching { sink.listMessageSinkRoutes(target) }
                    .getOrElse { error ->
                        logger.debug(error) {
                            "命令主账号解析跳过消息出口：pluginId=${handle.info.descriptor.id}，target=${target.stableValue()}"
                        }
                        emptyList()
                    }
            }
            .filter { it.enabled && it.state == MessageSinkRouteState.READY }
            .filter { it.targetPlatformId == target.platformId }
            .sortedWith(compareBy({ it.transportId }, { it.accountId }, { it.routeId }))
        if (routes.isEmpty()) return null

        val configuredPrimary = configStore.current()
            .messageRouting
            .policyFor(target.platformId.value)
            .primaryAccountId
            .trim()
            .takeIf { it.isNotBlank() }
        return configuredPrimary
            ?.takeIf { accountId -> routes.any { it.accountId == accountId } }
            ?: routes.first().accountId
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
                    DynamicImageCache.clearMemory()
                },
            ),
        )
    }

    private fun registerDeliveryDispatchTask(config: MainDynamicConfig) {
        taskScheduler.start(
            TaskDefinition(
                id = "main-delivery-dispatch",
                schedule = TaskSchedule.FixedDelay(
                    delay = config.delivery.retryDelaySeconds.seconds,
                    runImmediately = true,
                ),
                action = {
                    deliveryDispatcher.dispatchDue()
                },
            ),
        )
    }

    private fun registerDeliveryCleanupTask(config: MainDynamicConfig) {
        taskScheduler.start(
            TaskDefinition(
                id = "main-delivery-cleanup",
                schedule = TaskSchedule.Cron(config.delivery.cleanupCron),
                action = {
                    val cutoff = System.currentTimeMillis() / 1000 -
                        config.delivery.historyRetentionDays.coerceAtLeast(0) * 24 * 60 * 60
                    val result = MessageDeliveryRepository.cleanupHistory(cutoffEpochSeconds = cutoff)
                    if (result.deletedDeliveries > 0 || result.deletedMessages > 0) {
                        logger.info {
                            "消息记录已清理：投递=${result.deletedDeliveries}，消息=${result.deletedMessages}"
                        }
                    } else {
                        logger.debug { "消息记录无需清理" }
                    }
                },
            ),
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
            listenerTokens.forEach { eventBus.unsubscribe(it) }
            listenerTokens.clear()
            runBlocking {
                taskScheduler.shutdown()
            }
            pluginManager.shutdown()
            eventBus.shutdown()
            job.cancel("应用关闭")
            logger.info { "应用已关闭" }
        } finally {
            shutdownCallback?.invoke()
        }
    }
}
