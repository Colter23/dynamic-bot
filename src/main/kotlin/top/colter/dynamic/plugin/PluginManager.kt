package top.colter.dynamic.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.core.command.CommandPublisher
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.config.YamlPluginDataStore
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.event.ListenerToken
import top.colter.dynamic.event.SystemNotificationEvent
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.LinkVideoDownloader
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.core.plugin.CommandContributor
import top.colter.dynamic.core.plugin.IncomingMessageConsumerPlugin
import top.colter.dynamic.core.plugin.IncomingMessagePublisher
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.PluginAdminApiProvider
import top.colter.dynamic.core.plugin.PluginAdminApiRequest
import top.colter.dynamic.core.plugin.PluginAdminApiResponse
import top.colter.dynamic.core.plugin.PluginAdminPageProvider
import top.colter.dynamic.core.plugin.PluginMessagePublishRequest
import top.colter.dynamic.core.plugin.PluginMessagePublishOptions
import top.colter.dynamic.core.plugin.PluginMessagePublishResult
import top.colter.dynamic.core.plugin.PluginMessagePublishSink
import top.colter.dynamic.core.plugin.PluginMessagePublisher
import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskSnapshot
import top.colter.dynamic.task.DefaultTaskScheduler
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry
import java.io.File
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val logger = loggerFor<PluginManager>()

public class PluginManager(
    public val pluginDirPath: String = "plugins",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val eventBus: EventBus = EventBus(),
    private val configService: ConfigService = YamlConfigService(),
    private val commandRegistry: CommandRegistry = CommandRegistry(),
    private val commandPublisher: CommandPublisher = CommandPublisher { },
    private val incomingMessagePublisher: IncomingMessagePublisher = IncomingMessagePublisher { },
    private val pluginMessagePublishSink: PluginMessagePublishSink = PluginMessagePublishSink {
        PluginMessagePublishResult.failed("主项目消息发布器未配置")
    },
    private val sourceUpdatePublisher: SourceUpdatePublisher = SourceUpdatePublisher {
        SourceUpdatePublishResult.failed("来源更新发布器未配置")
    },
    private val pluginDataDirPath: String = "data/plugins",
    private val sourceStateStore: SourceStateStore = RepositorySourceStateStore,
    private val subscriptionQueryService: SubscriptionQueryService = RepositorySubscriptionQueryService,
    private val drawAssetRegistry: PlatformDrawAssetRegistry = PlatformDrawAssetRegistry(),
    private val shutdownDrainTimeoutMs: Long = 5000,
    private val pluginHookTimeoutMs: Long = DEFAULT_PLUGIN_HOOK_TIMEOUT_MS,
    private val incomingMessagePendingLimit: Int = DEFAULT_INCOMING_MESSAGE_PENDING_LIMIT,
) {
    private val pluginDir: File = File(pluginDirPath)
    private val objectMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val scanner: PluginScanner = PluginScanner(pluginDir, objectMapper)
    private val loader: PluginLoader = PluginLoader()
    private val hookRunner: PluginHookRunner = PluginHookRunner(pluginHookTimeoutMs)

    private val plugins = ConcurrentHashMap<String, PluginRuntime>()
    private val inFlightDispatchJobs = ConcurrentHashMap<String, MutableSet<Job>>()
    private val incomingDispatchStates = ConcurrentHashMap<String, IncomingDispatchState>()
    private val lifecycleLock: Any = Any()

    @Volatile
    private var subscriptionChangedListenerToken: ListenerToken? = null

    @Volatile
    private var isShuttingDown: Boolean = false

    @Volatile
    private var systemNotificationsEnabled: Boolean = false

    public fun enableSystemNotifications() {
        if (systemNotificationsEnabled) return
        systemNotificationsEnabled = true
        logger.info { "系统通知已启用，运行期异常将通知管理员" }
    }

    public fun loadAllPlugins(): LoadResult {
        synchronized(lifecycleLock) {
            val result = LoadResult()
            val scanResults = scanner.scanForPlugins().sortedBy { it.descriptor.id }
            val duplicatedIds = scanResults
                .groupBy { it.descriptor.id }
                .filterValues { it.size > 1 }
                .keys

            duplicatedIds.forEach { id ->
                result.failedPlugins[id] = "插件 ID 重复：$id"
            }

            scanResults
                .filterNot { it.descriptor.id in duplicatedIds }
                .forEach { scanResult ->
                    val descriptor = scanResult.descriptor
                    runCatching {
                        loadPlugin(scanResult)
                        result.loadedPlugins.add(descriptor.id)
                    }.onFailure { e ->
                        result.failedPlugins[descriptor.id] = e.message ?: e::class.simpleName ?: "未知错误"
                        logger.error(e) { "插件加载失败：pluginId=${descriptor.id}" }
                        publishPluginFailure(descriptor.id, "load", e, "插件加载失败")
                    }
                }

            return result
        }
    }

    public fun startAllPlugins() {
        synchronized(lifecycleLock) {
            ensureSubscriptionListenerRegistered()
            orderedPluginsForStart().forEach { runtime ->
                if (runtime.state == PluginState.LOADED) {
                    startPlugin(runtime.descriptor.id)
                }
            }
        }
    }

    public fun startPlugin(pluginId: String) {
        synchronized(lifecycleLock) {
            ensureSubscriptionListenerRegistered()
            val runtime = plugins[pluginId] ?: return

            if (runtime.state != PluginState.LOADED) {
                logger.warn { "跳过插件启动：pluginId=$pluginId，state=${runtime.state}" }
                return
            }

            runCatching {
                runPluginHook(pluginId, "start") { runtime.instance.onStart() }
                registerCommands(runtime)
                runtime.state = PluginState.ACTIVE
                runtime.error = null
                logger.info { "插件已启动：pluginId=$pluginId" }
            }.onFailure { e ->
                commandRegistry.unregisterByOwner(pluginId)
                drainDispatchJobs(pluginId)
                runCatching { runPluginHook(pluginId, "stop") { runtime.instance.onStop() } }
                    .onFailure { stopError ->
                        logger.warn(stopError) { "插件启动回滚停止失败：pluginId=$pluginId" }
                    }
                shutdownTaskScheduler(runtime)
                runtime.state = PluginState.FAILED
                runtime.error = e
                logger.error(e) { "插件启动失败：pluginId=$pluginId" }
                publishPluginFailure(pluginId, "start", e, "插件启动失败")
            }
        }
    }

    public fun stopAllPlugins() {
        synchronized(lifecycleLock) {
            orderedPluginsForStop().forEach { runtime ->
                stopPlugin(runtime.descriptor.id)
            }
        }
    }

    public fun stopPlugin(pluginId: String) {
        synchronized(lifecycleLock) {
            plugins[pluginId]?.let { stopPlugin(it) }
        }
    }

    public fun unloadPlugin(pluginId: String): Boolean {
        synchronized(lifecycleLock) {
            val runtime = plugins[pluginId] ?: return false
            stopPlugin(runtime)

            runCatching { runPluginHook(pluginId, "unload") { runtime.instance.onUnload() } }
                .onFailure {
                    runtime.state = PluginState.FAILED
                    runtime.error = it
                    logger.warn(it) { "插件卸载钩子执行失败：pluginId=$pluginId" }
                }
            commandRegistry.unregisterByOwner(pluginId)
            drainDispatchJobs(pluginId)
            shutdownTaskScheduler(runtime)
            runtime.scope.cancel("插件已卸载：$pluginId")
            runCatching { (runtime.classLoader as? URLClassLoader)?.close() }
                .onFailure { logger.warn(it) { "插件类加载器关闭失败：pluginId=$pluginId" } }
            drawAssetRegistry.unregisterPluginAssets(pluginId)
            plugins.remove(pluginId)
            inFlightDispatchJobs.remove(pluginId)
            incomingDispatchStates.remove(pluginId)
            logger.info { "插件已卸载：pluginId=$pluginId" }
            return true
        }
    }

    public fun reloadPlugin(pluginId: String): PluginReloadResult {
        synchronized(lifecycleLock) {
            if (!plugins.containsKey(pluginId)) {
                val message = "未找到插件：$pluginId"
                logger.warn { "插件重载失败：pluginId=$pluginId，原因=未找到" }
                return PluginReloadResult(
                    pluginId = pluginId,
                    success = false,
                    changed = false,
                    message = message,
                    error = message,
                )
            }

            val scanResult = scanner.scanForPlugins().firstOrNull { it.descriptor.id == pluginId }
            if (scanResult == null) {
                val message = "插件文件不存在或描述无效：$pluginId"
                logger.warn { "插件重载失败：pluginId=$pluginId，原因=文件不存在或描述无效" }
                publishPluginFailure(pluginId, "reload", message, "插件重载失败")
                return PluginReloadResult(
                    pluginId = pluginId,
                    success = false,
                    changed = false,
                    message = message,
                    error = message,
                )
            }

            return runCatching {
                unloadPlugin(pluginId)
                loadPlugin(scanResult)
                startPlugin(pluginId)
                val info = plugins[pluginId]?.toInfo()
                if (info?.state == PluginState.ACTIVE) {
                    PluginReloadResult(
                        pluginId = pluginId,
                        success = true,
                        changed = true,
                        pluginState = info.state,
                        message = "插件已重载：$pluginId",
                    )
                } else {
                    val message = info?.error?.message ?: "插件重载后状态异常：${info?.state ?: "未知状态"}"
                    publishPluginFailure(pluginId, "reload", message, "插件重载失败")
                    PluginReloadResult(
                        pluginId = pluginId,
                        success = false,
                        changed = true,
                        pluginState = info?.state,
                        message = message,
                        error = message,
                    )
                }
            }.getOrElse {
                logger.error(it) { "插件重载失败：pluginId=$pluginId" }
                publishPluginFailure(pluginId, "reload", it, "插件重载失败")
                PluginReloadResult(
                    pluginId = pluginId,
                    success = false,
                    changed = true,
                    pluginState = plugins[pluginId]?.state,
                    message = it.message ?: it::class.simpleName ?: "插件重载失败",
                    error = it.message ?: it::class.qualifiedName ?: "插件重载失败",
                )
            }
        }
    }

    public fun installOrUpdatePluginJar(
        downloadedJar: File,
        expectedPluginId: String,
        expectedVersion: String,
        startAfterInstall: Boolean = true,
        requireInstalled: Boolean = false,
    ): PluginInstallResult {
        synchronized(lifecycleLock) {
            require(downloadedJar.isFile) { "插件 Jar 不存在：${downloadedJar.absolutePath}" }
            val pluginId = expectedPluginId.trim()
            require(pluginId.isNotBlank()) { "插件 ID 不能为空" }
            require(expectedVersion.isNotBlank()) { "插件版本不能为空" }

            val descriptor = scanner.parsePluginDescriptor(downloadedJar)
            require(descriptor.id == pluginId) {
                "插件 ID 与目录不一致：catalog=$pluginId，jar=${descriptor.id}"
            }
            require(descriptor.version == expectedVersion) {
                "插件版本与目录不一致：catalog=$expectedVersion，jar=${descriptor.version}"
            }
            validateDescriptor(descriptor)
            validateApiVersion(descriptor.apiVersion)

            try {
                Files.createDirectories(pluginDir.toPath())
            } catch (e: Exception) {
                throw IllegalStateException("无法创建插件目录：path=${pluginDir.absolutePath}", e)
            }
            val existing = plugins[pluginId]
            if (requireInstalled && existing == null) {
                throw NoSuchElementException("插件未安装：$pluginId")
            }
            if (!requireInstalled && existing != null) {
                throw IllegalStateException("插件已安装，请使用更新操作：$pluginId")
            }

            val targetFile = resolveInstallTarget(existing, pluginId)
            ensureNoConflictingJar(pluginId, targetFile)

            val oldVersion = existing?.descriptor?.version
            val oldState = existing?.state
            val shouldStartAfterLoad = oldState == PluginState.ACTIVE || (existing == null && startAfterInstall)
            val backupFile = targetFile.resolveSibling("${targetFile.name}.${System.currentTimeMillis()}.bak")
            var backupCreated = false
            var movedNewJar = false
            var existingUnloaded = false

            try {
                ensureTargetFileReusable(pluginId, targetFile)
                if (existing != null) {
                    unloadPlugin(pluginId)
                    existingUnloaded = true
                }
                if (targetFile.exists()) {
                    moveFile(targetFile, backupFile)
                    backupCreated = true
                }

                moveFile(downloadedJar, targetFile)
                movedNewJar = true

                loadPlugin(PluginScanner.ScanResult(descriptor, targetFile))
                if (shouldStartAfterLoad) {
                    startPlugin(pluginId)
                    val afterStart = plugins[pluginId]
                        ?: throw IllegalStateException("插件启动后未找到：$pluginId")
                    if (afterStart.state != PluginState.ACTIVE) {
                        throw IllegalStateException(
                            afterStart.error?.message ?: "插件启动后状态异常：${afterStart.state}",
                        )
                    }
                }

                val info = plugins[pluginId]?.toInfo()
                    ?: throw IllegalStateException("插件安装后未找到：$pluginId")
                backupFile.delete()
                logger.info {
                    "插件安装更新完成：pluginId=$pluginId，oldVersion=${oldVersion ?: "-"}，newVersion=${descriptor.version}，state=${info.state}"
                }
                return PluginInstallResult(
                    pluginId = pluginId,
                    success = true,
                    changed = true,
                    pluginState = info.state,
                    oldVersion = oldVersion,
                    newVersion = descriptor.version,
                    message = if (oldVersion == null) {
                        "插件已安装：$pluginId"
                    } else {
                        "插件已更新：$pluginId，$oldVersion -> ${descriptor.version}"
                    },
                )
            } catch (e: Throwable) {
                logger.error(e) { "插件安装更新失败，准备回滚：pluginId=$pluginId" }
                publishPluginFailure(pluginId, "install_or_update", e, "插件安装或更新失败")
                rollbackInstallOrUpdate(
                    pluginId = pluginId,
                    targetFile = targetFile,
                    backupFile = backupFile,
                    backupCreated = backupCreated,
                    movedNewJar = movedNewJar,
                    shouldUnloadCurrent = existingUnloaded || movedNewJar,
                    shouldRestoreExisting = existingUnloaded,
                    oldState = oldState,
                )
                throw e
            }
        }
    }

    public fun dispatchSubscriptionChangedToSources(event: SubscriptionChangedEvent) {
        if (isShuttingDown) {
            logger.debug {
                "应用关闭中，跳过订阅事件分发：publisherId=${event.publisher.id}，subscriptionId=${event.subscription.id}"
            }
            return
        }

        activePublisherSourcePlugins()
            .filter { runtime ->
                val source = runtime.instance as? PublisherSourcePlugin ?: return@filter false
                source.platformId == event.publisher.platformId
            }
            .forEach { runtime ->
                val source = runtime.instance as? PublisherSourcePlugin ?: return@forEach
                launchForPlugin(runtime.descriptor.id) {
                    if (isShuttingDown) return@launchForPlugin
                    runCatching { source.onSubscriptionChanged(event) }
                        .onFailure {
                            logger.error(it) {
                                "订阅事件分发失败：pluginId=${runtime.descriptor.id}，publisherId=${event.publisher.id}"
                            }
                        }
                }
            }
    }

    public fun dispatchIncomingMessageToConsumers(message: IncomingMessage) {
        if (isShuttingDown) {
            logger.debug {
                "应用关闭中，跳过入站消息分发：platform=${message.platformId.value} target=${message.target.stableValue()}"
            }
            return
        }

        activeIncomingMessageConsumerPlugins()
            .forEach { runtime ->
                val consumer = runtime.instance as? IncomingMessageConsumerPlugin ?: return@forEach
                if (!consumer.incomingMessageFilter.matches(message)) return@forEach
                launchIncomingMessageForPlugin(runtime, consumer, message)
            }
    }

    public fun shutdown() {
        synchronized(lifecycleLock) {
            if (isShuttingDown) return
            isShuttingDown = true
            systemNotificationsEnabled = false
        }
        stopAllPlugins()
        subscriptionChangedListenerToken?.let { eventBus.unsubscribe(it) }
        subscriptionChangedListenerToken = null
        drainAllDispatchJobs()
        incomingDispatchStates.clear()
        scope.cancel("插件管理器关闭")
    }

    public fun getAllPlugins(): List<PluginInfo> {
        return plugins.values.map { it.toInfo() }.sortedBy { it.descriptor.id }
    }

    public fun getMessageSinkPlugins(): List<PluginHandle<MessageSinkPlugin>> {
        return activeExtensionHandles()
    }

    public fun getConfigurablePlugins(): List<PluginHandle<ConfigurablePlugin<*>>> {
        return extensionHandles(activeOnly = false)
    }

    public fun getPluginAdminPages(): List<PluginAdminPageInfo> {
        return plugins.values
            .flatMap { runtime ->
                val provider = runtime.instance as? PluginAdminPageProvider ?: return@flatMap emptyList()
                provider.adminPages
                    .filter { page -> !page.enabledWhenPluginActive || runtime.state == PluginState.ACTIVE }
                    .map { page ->
                        PluginAdminPageInfo(
                            pluginId = runtime.descriptor.id,
                            pluginName = runtime.descriptor.name,
                            pluginVersion = runtime.descriptor.version,
                            loadTime = runtime.loadTime,
                            pluginState = runtime.state,
                            page = page,
                        )
                    }
            }
            .sortedWith(compareBy<PluginAdminPageInfo> { it.page.navGroup }.thenBy { it.pluginName }.thenBy { it.page.title })
    }

    public fun readPluginAdminResource(pluginId: String, pageId: String, resourcePath: String): PluginAdminResource {
        val runtime = plugins[pluginId] ?: throw NoSuchElementException("未找到插件：$pluginId")
        val provider = runtime.instance as? PluginAdminPageProvider
            ?: throw NoSuchElementException("插件未提供后台页面：$pluginId")
        val page = provider.adminPages.firstOrNull { it.id == pageId }
            ?: throw NoSuchElementException("插件后台页面不存在：pluginId=$pluginId，pageId=$pageId")
        if (page.enabledWhenPluginActive && runtime.state != PluginState.ACTIVE) {
            throw IllegalStateException("插件后台页面不可用：pluginId=$pluginId，state=${runtime.state}")
        }
        val classLoader = runtime.classLoader ?: runtime.instance::class.java.classLoader
        val normalized = normalizePluginAdminResourcePath(resourcePath)
        val fullPath = "${normalizePluginAdminResourcePath(page.resourceRoot)}/$normalized"
        val bytes = classLoader.getResourceAsStream(fullPath)?.use { it.readBytes() }
            ?: throw NoSuchElementException("插件后台资源不存在：pluginId=$pluginId，path=$normalized")
        return PluginAdminResource(bytes = bytes, resourcePath = normalized)
    }

    public suspend fun handlePluginAdminApi(
        pluginId: String,
        request: PluginAdminApiRequest,
    ): PluginAdminApiResponse {
        val runtime = plugins[pluginId] ?: throw NoSuchElementException("未找到插件：$pluginId")
        if (runtime.state != PluginState.ACTIVE) {
            throw IllegalStateException("插件后台接口不可用：pluginId=$pluginId，state=${runtime.state}")
        }
        val provider = runtime.instance as? PluginAdminApiProvider
            ?: throw NoSuchElementException("插件未提供后台接口：$pluginId")
        return provider.handleAdminApi(request)
    }

    public fun getPluginTasks(): List<PluginTaskInfo> {
        return plugins.values
            .flatMap { runtime ->
                runtime.taskScheduler.snapshots().map { snapshot ->
                    runtime.toPluginTaskInfo(snapshot)
                }
            }
            .sortedWith(compareBy<PluginTaskInfo> { it.pluginId }.thenBy { it.task.id })
    }

    public fun getPluginTask(pluginId: String, taskId: String): PluginTaskInfo? {
        val runtime = plugins[pluginId] ?: return null
        val snapshot = runtime.taskScheduler.snapshot(taskId) ?: return null
        return runtime.toPluginTaskInfo(snapshot)
    }

    public fun startPluginTask(pluginId: String, taskId: String): Boolean {
        val scheduler = synchronized(lifecycleLock) {
            plugins[pluginId]?.taskScheduler
        } ?: return false
        return scheduler.start(taskId)
    }

    public suspend fun stopPluginTask(pluginId: String, taskId: String): Boolean {
        val scheduler = synchronized(lifecycleLock) {
            plugins[pluginId]?.taskScheduler
        } ?: return false
        return scheduler.stop(taskId)
    }

    public suspend fun restartPluginTask(pluginId: String, taskId: String): Boolean {
        val scheduler = synchronized(lifecycleLock) {
            plugins[pluginId]?.taskScheduler
        } ?: return false
        return scheduler.restart(taskId)
    }

    public fun getPublisherLoginProviders(): List<PluginHandle<PublisherLoginProvider>> {
        return activeExtensionHandles()
    }

    public fun getPublisherLookupPlugins(): List<PluginHandle<PublisherLookupPlugin>> {
        return activeExtensionHandles()
    }

    public fun getLinkResolvers(): List<LinkResolver> {
        return activeExtensionHandles<LinkResolver>().map { it.instance }
    }

    public fun getLinkVideoDownloaders(): List<LinkVideoDownloader> {
        return activeExtensionHandles<LinkVideoDownloader>().map { it.instance }
    }

    public fun findPublisherLookupPlugin(platformId: String): PublisherLookupPlugin? {
        val normalized = top.colter.dynamic.core.data.PlatformId.of(platformId)
        return activeExtensionHandles<PublisherLookupPlugin>()
            .firstOrNull { it.instance.platformId == normalized }
            ?.instance
    }

    public fun findPublisherFollowPlugin(platformId: String): PublisherFollowPlugin? {
        val normalized = top.colter.dynamic.core.data.PlatformId.of(platformId)
        return activeExtensionHandles<PublisherFollowPlugin>()
            .firstOrNull { it.instance.platformId == normalized }
            ?.instance
    }

    public fun findPublisherLoginProvider(platformId: String): PublisherLoginProvider? {
        val normalized = top.colter.dynamic.core.data.PlatformId.of(platformId)
        return activeExtensionHandles<PublisherLoginProvider>()
            .firstOrNull { it.instance.platformId == normalized }
            ?.instance
    }

    internal fun registerPluginForTest(
        descriptor: PluginDescriptor,
        instance: Plugin,
        capabilities: Set<PluginCapability> = inferCapabilities(instance),
        state: PluginState = PluginState.LOADED,
        classLoader: ClassLoader? = null,
    ) {
        val pluginScope = createPluginScope(descriptor.id)
        plugins[descriptor.id] = PluginRuntime(
            descriptor = descriptor,
            capabilities = capabilities,
            instance = instance,
            classLoader = classLoader,
            sourceJarPath = "",
            scope = pluginScope,
            taskScheduler = DefaultTaskScheduler(pluginScope),
            state = state,
        )
    }

    private fun loadPlugin(scanResult: PluginScanner.ScanResult) {
        val descriptor = scanResult.descriptor
        validateDescriptor(descriptor)
        require(!plugins.containsKey(descriptor.id)) { "插件 ID 重复：${descriptor.id}" }
        validateApiVersion(descriptor.apiVersion)

        val loaded = loader.load(descriptor, scanResult.jarFile.absolutePath)
        val capabilities = inferCapabilities(loaded.instance)
        if (capabilities.isEmpty()) {
            runCatching { loaded.classLoader.close() }
            throw IllegalArgumentException("插件 ${descriptor.id} 未实现任何已知扩展接口")
        }
        runCatching {
            drawAssetRegistry.registerPluginAssets(descriptor.id, descriptor.drawAssets, loaded.classLoader)
        }.onFailure {
            runCatching { loaded.classLoader.close() }
            throw it
        }

        val pluginScope = createPluginScope(descriptor.id)
        val runtime = PluginRuntime(
            descriptor = descriptor,
            capabilities = capabilities,
            instance = loaded.instance,
            classLoader = loaded.classLoader,
            sourceJarPath = scanResult.jarFile.absolutePath,
            scope = pluginScope,
            taskScheduler = DefaultTaskScheduler(pluginScope),
        )
        val context = PluginContext(
            pluginId = descriptor.id,
            descriptor = descriptor,
            configService = configService,
            dataStore = YamlPluginDataStore(descriptor.id, Paths.get(pluginDataDirPath)),
            scope = pluginScope,
            taskScheduler = runtime.taskScheduler,
            commandPublisher = commandPublisher,
            incomingMessagePublisher = incomingMessagePublisher,
            messagePublisher = messagePublisherFor(descriptor.id),
            sourceUpdatePublisher = sourceUpdatePublisher,
            sourceStateStore = sourceStateStore,
            subscriptionQueryService = subscriptionQueryService,
            notificationPublisher = notificationPublisherFor(descriptor.id),
        )

        runCatching {
            runPluginHook(descriptor.id, "load") { loaded.instance.onLoad(context) }
        }.onFailure {
            drawAssetRegistry.unregisterPluginAssets(descriptor.id)
            pluginScope.cancel("插件加载失败：${descriptor.id}")
            runCatching { loaded.classLoader.close() }
            throw it
        }

        plugins[descriptor.id] = runtime
        logger.debug {
            "插件已加载：pluginId=${descriptor.id}，api=${descriptor.apiVersion}，capabilities=$capabilities"
        }
    }

    private fun resolveInstallTarget(existing: PluginRuntime?, pluginId: String): File {
        val pluginDirCanonical = pluginDir.canonicalFile
        val existingFile = existing
            ?.sourceJarPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.canonicalFile
            ?.takeIf { file ->
                file.parentFile?.canonicalFile == pluginDirCanonical &&
                    file.extension.equals("jar", ignoreCase = true)
            }

        return existingFile ?: pluginDir.resolve("$pluginId.jar").canonicalFile
    }

    private fun ensureNoConflictingJar(pluginId: String, targetFile: File) {
        val targetCanonical = targetFile.canonicalFile
        val conflicts = scanner.scanForPlugins()
            .filter { it.descriptor.id == pluginId && it.jarFile.canonicalFile != targetCanonical }
        require(conflicts.isEmpty()) {
            "插件 ID 重复：$pluginId，冲突文件=${conflicts.joinToString { it.jarFile.name }}"
        }
    }

    private fun notificationPublisherFor(pluginId: String): SystemNotificationPublisher = SystemNotificationPublisher { request ->
        if (!systemNotificationsEnabled) {
            logger.debug {
                "启动阶段忽略插件系统通知：pluginId=$pluginId，type=${request.type}，title=${request.title}"
            }
            return@SystemNotificationPublisher SystemNotificationPublishResult.ignored("系统通知尚未启用：启动阶段")
        }
        eventBus.broadcast(
            SystemNotificationEvent(
                sourcePlugin = pluginId,
                notification = request,
            ),
        )
        SystemNotificationPublishResult.accepted()
    }

    private fun publishPluginFailure(
        pluginId: String,
        operation: String,
        error: Throwable,
        title: String,
    ) {
        publishPluginFailure(
            pluginId = pluginId,
            operation = operation,
            errorText = error.message ?: error::class.qualifiedName ?: "未知错误",
            title = title,
        )
    }

    private fun publishPluginFailure(
        pluginId: String,
        operation: String,
        errorText: String,
        title: String,
    ) {
        if (!systemNotificationsEnabled) {
            logger.debug {
                "启动阶段忽略插件生命周期通知：pluginId=$pluginId，operation=$operation，title=$title"
            }
            return
        }
        eventBus.broadcast(
            SystemNotificationEvent(
                sourcePlugin = pluginId,
                notification = SystemNotificationPublishRequest(
                    type = "plugin.lifecycle_failed",
                    severity = SystemNotificationSeverity.ERROR,
                    title = title,
                    content = "插件执行 $operation 操作失败：$errorText",
                    dedupeKey = "plugin.lifecycle_failed:$pluginId:$operation",
                    details = mapOf(
                        "pluginId" to pluginId,
                        "operation" to operation,
                        "error" to errorText,
                    ),
                ),
            ),
        )
    }

    private fun ensureTargetFileReusable(pluginId: String, targetFile: File) {
        if (!targetFile.exists()) return
        val targetDescriptor = scanner.parsePluginDescriptor(targetFile)
        require(targetDescriptor.id == pluginId) {
            "目标文件已存在且不是该插件：file=${targetFile.name}，pluginId=${targetDescriptor.id}"
        }
    }

    private fun rollbackInstallOrUpdate(
        pluginId: String,
        targetFile: File,
        backupFile: File,
        backupCreated: Boolean,
        movedNewJar: Boolean,
        shouldUnloadCurrent: Boolean,
        shouldRestoreExisting: Boolean,
        oldState: PluginState?,
    ) {
        runCatching {
            if (shouldUnloadCurrent && plugins.containsKey(pluginId)) {
                unloadPlugin(pluginId)
            }
        }.onFailure {
            logger.warn(it) { "插件安装更新回滚卸载新插件失败：pluginId=$pluginId" }
        }

        runCatching {
            if (movedNewJar && targetFile.exists()) {
                targetFile.delete()
            }
            if (backupCreated && backupFile.exists()) {
                moveFile(backupFile, targetFile)
            }
        }.onFailure {
            logger.warn(it) { "插件安装更新回滚文件失败：pluginId=$pluginId" }
        }

        if ((backupCreated || shouldRestoreExisting) && targetFile.exists()) {
            runCatching {
                val descriptor = scanner.parsePluginDescriptor(targetFile)
                loadPlugin(PluginScanner.ScanResult(descriptor, targetFile))
                if (oldState == PluginState.ACTIVE) {
                    startPlugin(pluginId)
                }
            }.onFailure {
                logger.warn(it) { "插件安装更新回滚加载旧插件失败：pluginId=$pluginId" }
            }
        }
    }

    private fun moveFile(source: File, target: File) {
        target.parentFile?.toPath()?.let { Files.createDirectories(it) }
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun stopPlugin(runtime: PluginRuntime) {
        val pluginId = runtime.descriptor.id
        commandRegistry.unregisterByOwner(pluginId)

        if (runtime.state == PluginState.ACTIVE) {
            runtime.state = PluginState.LOADED
            drainDispatchJobs(pluginId)
            val stopped = runCatching {
                runPluginHook(pluginId, "stop") { runtime.instance.onStop() }
                runtime.error = null
                logger.info { "插件已停止：pluginId=$pluginId" }
            }
            shutdownTaskScheduler(runtime)
            stopped.onFailure { e ->
                runtime.state = PluginState.FAILED
                runtime.error = e
                logger.error(e) { "插件停止失败：pluginId=$pluginId" }
                publishPluginFailure(pluginId, "stop", e, "插件停止失败")
            }
        } else {
            drainDispatchJobs(pluginId)
            shutdownTaskScheduler(runtime)
        }
    }

    private fun shutdownTaskScheduler(runtime: PluginRuntime) {
        runCatching { runBlocking { runtime.taskScheduler.shutdown() } }
            .onFailure {
                logger.warn(it) { "插件任务调度器关闭失败：pluginId=${runtime.descriptor.id}" }
            }
    }

    private fun runPluginHook(pluginId: String, operation: String, block: suspend () -> Unit) {
        hookRunner.run(pluginId, operation, block)
    }

    private fun ensureSubscriptionListenerRegistered() {
        if (subscriptionChangedListenerToken != null) return
        subscriptionChangedListenerToken = object : Listener<SubscriptionChangedEvent> {
            override suspend fun onMessage(event: SubscriptionChangedEvent) {
                dispatchSubscriptionChangedToSources(event)
            }
        }.let { eventBus.subscribe(it) }
    }

    private fun validateDescriptor(descriptor: PluginDescriptor) {
        require(PLUGIN_ID_REGEX.matches(descriptor.id)) { "插件 ID 不合法：${descriptor.id}" }
        require(descriptor.mainClass.isNotBlank()) { "插件 ${descriptor.id} 的 mainClass 不能为空" }
    }

    private fun validateApiVersion(apiVersion: String) {
        val pluginMajor = majorVersionOf(apiVersion)
            ?: throw IllegalArgumentException("apiVersion 格式非法：$apiVersion，期望形如 $CORE_PLUGIN_API_VERSION")
        val coreMajor = majorVersionOf(CORE_PLUGIN_API_VERSION)
            ?: error("核心 apiVersion 常量非法：$CORE_PLUGIN_API_VERSION")
        require(pluginMajor == coreMajor) {
            "不兼容的 apiVersion=$apiVersion（主版本 $pluginMajor），核心要求主版本 $coreMajor"
        }
    }

    private fun majorVersionOf(version: String): Int? {
        return version.trim().substringBefore('.').toIntOrNull()
    }

    private fun inferCapabilities(instance: Plugin): Set<PluginCapability> {
        return buildSet {
            if (instance is PublisherSourcePlugin) add(PluginCapability.PUBLISHER_SOURCE)
            if (instance is PublisherLookupPlugin) add(PluginCapability.PUBLISHER_LOOKUP)
            if (instance is PublisherFollowPlugin) add(PluginCapability.PUBLISHER_FOLLOW)
            if (instance is PublisherLoginProvider) add(PluginCapability.PUBLISHER_LOGIN)
            if (instance is MessageSinkPlugin) add(PluginCapability.MESSAGE_SINK)
            if (instance is IncomingMessageConsumerPlugin) add(PluginCapability.INCOMING_MESSAGE_CONSUMER)
            if (instance is CommandContributor) add(PluginCapability.COMMAND_CONTRIBUTOR)
            if (instance is LinkResolver) add(PluginCapability.LINK_RESOLVER)
            if (instance is LinkVideoDownloader) add(PluginCapability.LINK_VIDEO_DOWNLOADER)
            if (instance is PluginAdminPageProvider && instance.adminPages.isNotEmpty()) add(PluginCapability.ADMIN_PAGE)
            if (instance is PluginAdminApiProvider) add(PluginCapability.ADMIN_API)
            if (instance is ConfigurablePlugin<*>) add(PluginCapability.CONFIGURABLE)
        }
    }

    private fun activeMessageSinkPlugins(): List<PluginRuntime> {
        return activePluginsWith(PluginCapability.MESSAGE_SINK)
    }

    private fun activePublisherSourcePlugins(): List<PluginRuntime> {
        return activePluginsWith(PluginCapability.PUBLISHER_SOURCE)
    }

    private fun activeIncomingMessageConsumerPlugins(): List<PluginRuntime> {
        return activePluginsWith(PluginCapability.INCOMING_MESSAGE_CONSUMER)
    }

    private fun activePluginsWith(capability: PluginCapability): List<PluginRuntime> {
        return plugins.values
            .filter { capability in it.capabilities && it.state == PluginState.ACTIVE }
            .sortedBy { it.descriptor.id }
    }

    private fun registerCommands(runtime: PluginRuntime) {
        if (PluginCapability.COMMAND_CONTRIBUTOR !in runtime.capabilities) return
        val contributor = runtime.instance as? CommandContributor ?: return
        contributor.commandHandlers().forEach { handler ->
            commandRegistry.register(handler, runtime.descriptor.id)
        }
    }

    private inline fun <reified T : Any> activeExtensionHandles(): List<PluginHandle<T>> {
        return extensionHandles(activeOnly = true)
    }

    private inline fun <reified T : Any> extensionHandles(activeOnly: Boolean): List<PluginHandle<T>> {
        return plugins.values
            .filter { !activeOnly || it.state == PluginState.ACTIVE }
            .mapNotNull { runtime ->
                val instance = runtime.instance as? T ?: return@mapNotNull null
                PluginHandle(runtime.toInfo(), instance)
            }
            .sortedBy { it.info.descriptor.id }
    }

    private fun orderedPluginsForStart(): List<PluginRuntime> {
        return plugins.values.sortedWith(compareBy<PluginRuntime> { startPriority(it) }.thenBy { it.descriptor.id })
    }

    private fun orderedPluginsForStop(): List<PluginRuntime> {
        return plugins.values.sortedWith(compareBy<PluginRuntime> { stopPriority(it) }.thenByDescending { it.descriptor.id })
    }

    private fun startPriority(runtime: PluginRuntime): Int {
        return when {
            PluginCapability.MESSAGE_SINK in runtime.capabilities -> 0
            PluginCapability.COMMAND_CONTRIBUTOR in runtime.capabilities -> 10
            PluginCapability.INCOMING_MESSAGE_CONSUMER in runtime.capabilities -> 15
            PluginCapability.PUBLISHER_SOURCE in runtime.capabilities -> 20
            else -> 100
        }
    }

    private fun stopPriority(runtime: PluginRuntime): Int {
        return when {
            PluginCapability.PUBLISHER_SOURCE in runtime.capabilities -> 0
            PluginCapability.INCOMING_MESSAGE_CONSUMER in runtime.capabilities -> 5
            PluginCapability.COMMAND_CONTRIBUTOR in runtime.capabilities -> 10
            PluginCapability.MESSAGE_SINK in runtime.capabilities -> 20
            else -> 100
        }
    }

    private fun messagePublisherFor(pluginId: String): PluginMessagePublisher {
        return object : PluginMessagePublisher {
            override suspend fun sendBatches(
                targets: List<TargetAddress>,
                batches: List<MessageBatch>,
                renderVariant: String?,
                options: PluginMessagePublishOptions,
            ): PluginMessagePublishResult {
                val normalizedVariant = renderVariant?.trim()?.takeIf { it.isNotBlank() } ?: pluginId
                return pluginMessagePublishSink.publish(
                    PluginMessagePublishRequest(
                        sourcePlugin = pluginId,
                        targets = targets,
                        batches = batches,
                        renderVariant = normalizedVariant,
                        options = options,
                    ),
                )
            }
        }
    }

    private fun launchIncomingMessageForPlugin(
        runtime: PluginRuntime,
        consumer: IncomingMessageConsumerPlugin,
        message: IncomingMessage,
    ) {
        val pluginId = runtime.descriptor.id
        val state = incomingDispatchStates.computeIfAbsent(pluginId) { IncomingDispatchState() }
        val pending = state.pending.incrementAndGet()
        val pendingLimit = incomingMessagePendingLimit.coerceAtLeast(1)
        if (pending > pendingLimit) {
            state.pending.decrementAndGet()
            logger.warn {
                "入站消息分发已丢弃：pluginId=$pluginId，pending=$pending，limit=$pendingLimit，platform=${message.platformId.value}，target=${message.target.stableValue()}"
            }
            return
        }

        val pendingReleased = AtomicBoolean(false)
        fun releasePending() {
            if (pendingReleased.compareAndSet(false, true)) {
                state.pending.decrementAndGet()
            }
        }

        val job = launchForPlugin(pluginId) {
            try {
                state.semaphore.withPermit {
                    if (isShuttingDown) return@withPermit
                    runCatching { consumer.onIncomingMessage(message) }
                        .onFailure { error ->
                            logger.error(error) {
                                "入站消息分发失败：pluginId=$pluginId，platform=${message.platformId.value}，target=${message.target.stableValue()}"
                            }
                            publishPluginFailure(
                                pluginId = pluginId,
                                operation = "incoming_message",
                                error = error,
                                title = "插件入站消息处理失败",
                            )
                        }
                }
            } finally {
                releasePending()
            }
        }
        if (job == null) {
            releasePending()
        } else {
            job.invokeOnCompletion { releasePending() }
        }
    }

    private fun createPluginScope(pluginId: String): CoroutineScope {
        val parentJob = scope.coroutineContext[Job]
        val contextWithoutJob = scope.coroutineContext.minusKey(Job) + CoroutineName("plugin-$pluginId")
        val job = if (parentJob != null) SupervisorJob(parentJob) else SupervisorJob()
        return CoroutineScope(contextWithoutJob + job)
    }

    private fun launchForPlugin(pluginId: String, block: suspend CoroutineScope.() -> Unit): Job? {
        val runtime = plugins[pluginId] ?: return null
        val jobSet = inFlightDispatchJobs.computeIfAbsent(pluginId) { ConcurrentHashMap.newKeySet() }
        val job = runtime.scope.launch(block = block)
        jobSet.add(job)
        job.invokeOnCompletion {
            jobSet.remove(job)
            if (jobSet.isEmpty()) {
                inFlightDispatchJobs.remove(pluginId, jobSet)
            }
        }
        return job
    }

    private fun drainDispatchJobs(pluginId: String) {
        runBlocking {
            withTimeoutOrNull(shutdownDrainTimeoutMs) {
                while (true) {
                    val jobs = inFlightDispatchJobs[pluginId]?.toList().orEmpty()
                    if (jobs.isEmpty()) return@withTimeoutOrNull
                    jobs.forEach { it.join() }
                }
            } ?: logger.warn { "等待插件分发任务结束超时：pluginId=$pluginId，timeoutMs=$shutdownDrainTimeoutMs" }
        }
    }

    private fun drainAllDispatchJobs() {
        runBlocking {
            withTimeoutOrNull(shutdownDrainTimeoutMs) {
                while (inFlightDispatchJobs.isNotEmpty()) {
                    inFlightDispatchJobs.values.flatMap { it.toList() }.forEach { it.join() }
                }
            } ?: logger.warn { "等待分发任务结束超时：timeoutMs=$shutdownDrainTimeoutMs" }
        }
    }

    private class PluginRuntime(
        val descriptor: PluginDescriptor,
        val capabilities: Set<PluginCapability>,
        val instance: Plugin,
        val classLoader: ClassLoader?,
        val sourceJarPath: String,
        val loadTime: Long = System.currentTimeMillis(),
        val scope: CoroutineScope,
        val taskScheduler: TaskScheduler,
        @Volatile var state: PluginState = PluginState.LOADED,
        @Volatile var error: Throwable? = null,
    ) {
        fun toInfo(): PluginInfo = PluginInfo(
            descriptor = descriptor,
            capabilities = capabilities,
            state = state,
            sourceJarPath = sourceJarPath,
            loadTime = loadTime,
            error = error,
        )

        fun toPluginTaskInfo(task: TaskSnapshot): PluginTaskInfo = PluginTaskInfo(
            pluginId = descriptor.id,
            pluginName = descriptor.name,
            pluginVersion = descriptor.version,
            pluginState = state,
            task = task,
        )
    }

    private class IncomingDispatchState {
        val pending: AtomicInteger = AtomicInteger(0)
        val semaphore: Semaphore = Semaphore(1)
    }

    private companion object {
        private val PLUGIN_ID_REGEX: Regex = Regex("^[a-zA-Z0-9._-]+$")
        private const val DEFAULT_PLUGIN_HOOK_TIMEOUT_MS: Long = 10_000
        private const val DEFAULT_INCOMING_MESSAGE_PENDING_LIMIT: Int = 64
    }
}

private fun normalizePluginAdminResourcePath(path: String): String {
    val normalized = path.replace('\\', '/').trim('/')
    require(normalized.isNotBlank()) { "插件后台资源路径不能为空" }
    val segments = normalized.split("/")
    require(segments.none { it.isBlank() || it == "." || it == ".." }) {
        "插件后台资源路径非法：$path"
    }
    return segments.joinToString("/")
}

public data class LoadResult(
    val loadedPlugins: MutableList<String> = mutableListOf(),
    val failedPlugins: MutableMap<String, String> = mutableMapOf(),
)

public data class PluginReloadResult(
    val pluginId: String,
    val success: Boolean,
    val changed: Boolean,
    val pluginState: PluginState? = null,
    val message: String,
    val error: String? = null,
)

public data class PluginInstallResult(
    val pluginId: String,
    val success: Boolean,
    val changed: Boolean,
    val pluginState: PluginState? = null,
    val oldVersion: String? = null,
    val newVersion: String? = null,
    val message: String,
)

public data class PluginTaskInfo(
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val pluginState: PluginState,
    val task: TaskSnapshot,
)
