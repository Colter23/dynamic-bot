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
import top.colter.dynamic.core.data.MessageDeliveryPolicy
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.IncomingMessageEvent
import top.colter.dynamic.event.Listener
import top.colter.dynamic.event.ListenerToken
import top.colter.dynamic.core.plugin.IncomingMessagePublisher
import top.colter.dynamic.core.plugin.PluginMessagePublishOptions
import top.colter.dynamic.core.plugin.PluginMessagePublishResult
import top.colter.dynamic.core.plugin.PluginMessagePublishSink
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
import top.colter.dynamic.draw.DefaultLinkPreviewRenderer
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry
import top.colter.dynamic.listener.DeliveryDispatcher
import top.colter.dynamic.link.LinkAutoParseListener
import top.colter.dynamic.link.DeliveryLinkParseProgressMessenger
import top.colter.dynamic.link.LinkParseService
import top.colter.dynamic.listener.SourceUpdateProcessor
import top.colter.dynamic.media.OutboundMediaService
import top.colter.dynamic.message.OutboundMessageService
import top.colter.dynamic.notification.MessageSinkRouteMonitor
import top.colter.dynamic.notification.SystemNotificationService
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.SourceUpdateSnapshotRepository

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
    private lateinit var outboundMessageService: OutboundMessageService
    private lateinit var outboundMediaService: OutboundMediaService
    private lateinit var messageSinkRouteMonitor: MessageSinkRouteMonitor
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
    private val incomingMessagePublisher: IncomingMessagePublisher = IncomingMessagePublisher { message ->
        eventBus.broadcast(IncomingMessageEvent(message))
    }
    private val pluginMessagePublishSink: PluginMessagePublishSink = PluginMessagePublishSink { request ->
        if (!::outboundMessageService.isInitialized) {
            return@PluginMessagePublishSink PluginMessagePublishResult.failed("主项目消息发布器尚未初始化")
        }
        runCatching {
            val result = outboundMessageService.enqueueBatches(
                source = request.sourcePlugin,
                targets = request.targets,
                batches = request.batches,
                renderVariant = request.renderVariant,
                deliveryPolicy = request.options.toDeliveryPolicy(),
            )
            PluginMessagePublishResult.accepted(
                messageId = result.message.id,
                targetCount = result.targetCount,
                newDeliveryCount = result.newDeliveryCount,
                existingDeliveryCount = result.existingDeliveryCount,
            )
        }.getOrElse { error ->
            PluginMessagePublishResult.failed(error.message ?: error::class.qualifiedName ?: "消息发布失败")
        }
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
        incomingMessagePublisher = incomingMessagePublisher,
        pluginMessagePublishSink = pluginMessagePublishSink,
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
        val config = configStore.loadOrCreate(
            adminTokenProvider = { generateAdminToken().also { generatedAdminToken = it } },
            secretProvider = { generateAdminToken() },
            defaultConfigProvider = ::defaultMainConfig,
        )
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

        pluginManager.enableSystemNotifications()
        logger.info { "主项目已启动，已加载插件=${loadResult.loadedPlugins}" }
    }

    private fun registerCoreListeners(config: MainDynamicConfig) {
        DynamicImageCache.configure(
            sourceRoot = Paths.get(config.imageCache.sourceRoot),
            maxMemoryBytes = config.imageCache.memoryMaxBytes,
            maxMemoryEntries = config.imageCache.memoryMaxEntries,
            maxReadBytes = config.imageCache.maxImageBytes,
        )
        outboundMediaService = OutboundMediaService(configProvider = configStore::current)
        registerImageCleanupTask(config)
        deliveryDispatcher = DeliveryDispatcher(
            sinkProvider = { pluginManager.getMessageSinkPlugins() },
            configProvider = { configStore.current().delivery },
            routingConfigProvider = { configStore.current().messageRouting },
            outboundMediaService = outboundMediaService,
        )
        outboundMessageService = OutboundMessageService(
            onMessagesQueued = {
                deliveryDispatcher.dispatchDue()
            },
        )
        messageSinkRouteMonitor = MessageSinkRouteMonitor(
            sinkProvider = { pluginManager.getMessageSinkPlugins() },
            notificationConfigProvider = { configStore.current().notifications },
            eventBus = eventBus,
        )
        registerDeliveryDispatchTask(config)
        registerDeliveryCleanupTask(config)
        registerMessageSinkRouteMonitorTask(config)
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

        val linkParseProgressMessenger = DeliveryLinkParseProgressMessenger(
            sendCommandResult = deliveryDispatcher::sendCommandResult,
            recallMessage = deliveryDispatcher::recallMessage,
        )
        val linkParseService = LinkParseService(
            resolversProvider = { pluginManager.getLinkResolvers() },
            configProvider = configStore::current,
            sourceUpdatePublisher = sourceUpdatePublisher,
            videoDownloadersProvider = { pluginManager.getLinkVideoDownloaders() },
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
            progressMessenger = linkParseProgressMessenger,
            backgroundScope = this,
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
                outboundMessageService = outboundMessageService,
            ),
        )
        listenerTokens += eventBus.subscribe(
            LinkAutoParseListener(
                configProvider = configStore::current,
                linkParseService = linkParseService,
                eventBus = eventBus,
                progressMessenger = linkParseProgressMessenger,
                primaryBotAccountResolver = ::resolvePrimaryCommandBotAccount,
            ),
        )

        listenerTokens += eventBus.subscribe(
            object : Listener<IncomingMessageEvent> {
                override suspend fun onMessage(event: IncomingMessageEvent) {
                    pluginManager.dispatchIncomingMessageToConsumers(event.message)
                }
            },
        )

        listenerTokens += eventBus.subscribe(
            SystemNotificationService(
                configProvider = configStore::current,
                outboundMessageService = outboundMessageService,
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
            outboundMessageService = outboundMessageService,
            outboundMediaService = outboundMediaService,
            taskScheduler = taskScheduler,
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

    private fun defaultMainConfig(): MainDynamicConfig {
        val webAdminHost = System.getenv("DYNAMIC_BOT_WEB_ADMIN_HOST")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return if (webAdminHost == null) {
            MainDynamicConfig()
        } else {
            MainDynamicConfig(webAdmin = WebAdminConfig(host = webAdminHost))
        }
    }

    private fun PluginMessagePublishOptions.toDeliveryPolicy(): MessageDeliveryPolicy {
        val now = System.currentTimeMillis() / 1000
        val expiresAt = expiresInSeconds?.let { seconds ->
            if (seconds > Long.MAX_VALUE - now) Long.MAX_VALUE else now + seconds
        }
        return MessageDeliveryPolicy(
            retry = retry,
            expiresAtEpochSeconds = expiresAt,
        )
    }

    private fun registerImageCleanupTask(config: MainDynamicConfig) {
        taskScheduler.start(
            TaskDefinition(
                id = "main-image-cleanup",
                name = "缓存清理",
                description = "按配置清理原图、渲染图和视频缓存，并清空内存图片缓存。",
                schedule = TaskSchedule.Cron(config.imageCache.cleanupCron),
                action = {
                    val current = configStore.current()
                    val imageCacheConfig = current.imageCache
                    val videoDownloadConfig = current.linkParsing.videoDownload
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
                    if (videoDownloadConfig.cacheRoot.isNotBlank()) {
                        val result = cleaner.clean(
                            Paths.get(videoDownloadConfig.cacheRoot),
                            videoDownloadConfig.cleanupMaxIdleDays,
                        )
                        if (result.deletedFiles > 0) {
                            logger.info { "视频缓存已清理：文件=${result.deletedFiles}，字节=${result.deletedBytes}" }
                        } else {
                            logger.debug { "视频缓存无需清理" }
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
                name = "消息投递调度",
                description = "扫描待投递消息，执行发送与失败重试。",
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
                name = "消息记录清理",
                description = "按保留天数清理历史投递记录和消息内容。",
                schedule = TaskSchedule.Cron(config.delivery.cleanupCron),
                action = {
                    val cutoff = System.currentTimeMillis() / 1000 -
                        config.delivery.historyRetentionDays.coerceAtLeast(0) * 24 * 60 * 60
                    val result = MessageDeliveryRepository.cleanupHistory(cutoffEpochSeconds = cutoff)
                    val deletedSnapshots = SourceUpdateSnapshotRepository.cleanupOrphaned(cutoffEpochSeconds = cutoff)
                    if (result.deletedDeliveries > 0 || result.deletedMessages > 0) {
                        logger.info {
                            "消息记录已清理：投递=${result.deletedDeliveries}，消息=${result.deletedMessages}，来源快照=$deletedSnapshots"
                        }
                    } else if (deletedSnapshots > 0) {
                        logger.info { "来源更新快照已清理：数量=$deletedSnapshots" }
                    } else {
                        logger.debug { "消息记录无需清理" }
                    }
                },
            ),
        )
    }

    private fun registerMessageSinkRouteMonitorTask(config: MainDynamicConfig) {
        taskScheduler.start(
            TaskDefinition(
                id = "main-message-sink-route-monitor",
                name = "消息出口监控",
                description = "定期检查消息出口账号路线状态，发现运行期异常时触发系统通知。",
                schedule = TaskSchedule.FixedDelay(
                    delay = config.notifications.routeMonitorIntervalSeconds.seconds,
                    runImmediately = false,
                ),
                action = {
                    messageSinkRouteMonitor.scan()
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
            val currentAdminServer = adminServer
            adminServer = null
            shutdownStep("管理后台") {
                currentAdminServer?.stop()
            }

            shutdownStep("事件监听器") {
                listenerTokens.forEach { token ->
                    runCatching {
                        eventBus.unsubscribe(token)
                    }.onFailure { error ->
                        logger.warn(error) { "事件监听器取消订阅失败，继续关闭：token=$token" }
                    }
                }
                listenerTokens.clear()
            }

            shutdownStep("任务调度器") {
                runBlocking {
                    taskScheduler.shutdown()
                }
            }
            shutdownStep("插件管理器") {
                pluginManager.shutdown()
            }
            shutdownStep("事件总线") {
                eventBus.shutdown()
            }
            shutdownStep("主协程") {
                job.cancel("应用关闭")
            }
            logger.info { "应用已关闭" }
        } finally {
            runCatching {
                shutdownCallback?.invoke()
            }.onFailure { error ->
                logger.warn(error) { "应用关闭回调执行失败" }
            }
        }
    }

    private fun shutdownStep(name: String, action: () -> Unit) {
        runCatching(action).onFailure { error ->
            logger.warn(error) { "应用关闭步骤失败，继续关闭：$name" }
        }
    }
}
