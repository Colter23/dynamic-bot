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
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.config.YamlPluginDataStore
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.event.ListenerToken
import top.colter.dynamic.event.MessageEvent
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.link.DynamicLinkResolver
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.core.plugin.CommandContributor
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkPlugin
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
import top.colter.dynamic.task.DefaultTaskScheduler
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageDelivery
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

private val logger = loggerFor<PluginManager>()

public class PluginManager(
    pluginDirPath: String = "plugins",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val eventBus: EventBus = EventBus(),
    private val configService: ConfigService = YamlConfigService(),
    private val commandRegistry: CommandRegistry = CommandRegistry(),
    private val commandPublisher: CommandPublisher = CommandPublisher { },
    private val sourceUpdatePublisher: SourceUpdatePublisher = SourceUpdatePublisher {
        SourceUpdatePublishResult.failed("来源更新发布器未配置")
    },
    private val pluginDataDirPath: String = "data/plugins",
    private val sourceStateStore: SourceStateStore = RepositorySourceStateStore,
    private val subscriptionQueryService: SubscriptionQueryService = RepositorySubscriptionQueryService,
    private val drawAssetRegistry: PlatformDrawAssetRegistry = PlatformDrawAssetRegistry(),
    private val sinkMaxConcurrency: Int = 4,
    private val shutdownDrainTimeoutMs: Long = 5000,
) {
    private val pluginDir: File = File(pluginDirPath)
    private val objectMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val scanner: PluginScanner = PluginScanner(pluginDir, objectMapper)
    private val loader: PluginLoader = PluginLoader()
    private val sinkSemaphore: Semaphore = Semaphore(sinkMaxConcurrency)

    private val plugins = ConcurrentHashMap<String, PluginRuntime>()
    private val inFlightDispatchJobs = ConcurrentHashMap<String, MutableSet<Job>>()
    private val lifecycleLock: Any = Any()

    @Volatile
    private var subscriptionChangedListenerToken: ListenerToken? = null

    @Volatile
    private var isShuttingDown: Boolean = false

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
                runBlocking { runtime.instance.onStart() }
                registerCommands(runtime)
                runtime.state = PluginState.ACTIVE
                runtime.error = null
                logger.info { "插件已启动：pluginId=$pluginId" }
            }.onFailure { e ->
                commandRegistry.unregisterByOwner(pluginId)
                drainDispatchJobs(pluginId)
                runCatching { runBlocking { runtime.instance.onStop() } }
                    .onFailure { stopError ->
                        logger.warn(stopError) { "插件启动回滚停止失败：pluginId=$pluginId" }
                    }
                shutdownTaskScheduler(runtime)
                runtime.state = PluginState.FAILED
                runtime.error = e
                logger.error(e) { "插件启动失败：pluginId=$pluginId" }
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

            runCatching { runBlocking { runtime.instance.onUnload() } }
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

    public fun dispatchMessageToSinks(event: MessageEvent) {
        if (isShuttingDown) {
            logger.debug { "应用关闭中，跳过消息分发：messageId=${event.message.id}" }
            return
        }

        val eventId = event.message.id
        activeMessageSinkPlugins().forEach { runtime ->
            val sink = runtime.instance as? MessageSinkPlugin ?: return@forEach
            launchForPlugin(runtime.descriptor.id) {
                if (isShuttingDown) return@launchForPlugin
                sinkSemaphore.withPermit {
                    event.message.targets
                        .filter { it.platformId == sink.platformId }
                        .forEachIndexed { index, target ->
                            val request = MessageDeliveryRequest(
                                delivery = legacyDelivery(event.message, target, index),
                                message = event.message.copy(targets = listOf(target)),
                            )
                            runCatching { sink.sendMessage(request) }
                                .onFailure {
                                    logger.error(it) {
                                        "消息分发失败：pluginId=${runtime.descriptor.id}，messageId=$eventId"
                                    }
                                }
                            }
                }
            }
        }
    }

    public fun dispatchCommandResultToSinks(event: CommandResultEvent) {
        if (isShuttingDown) {
            logger.debug { "应用关闭中，跳过命令结果分发：traceId=${event.inReplyTo}" }
            return
        }

        activeMessageSinkPlugins().forEach { runtime ->
            val sink = runtime.instance as? MessageSinkPlugin ?: return@forEach
            launchForPlugin(runtime.descriptor.id) {
                if (isShuttingDown) return@launchForPlugin
                sinkSemaphore.withPermit {
                    runCatching {
                        sink.sendCommandResult(
                            CommandResultSendRequest(
                                target = event.target,
                                chain = event.chain,
                                inReplyTo = event.inReplyTo,
                            ),
                        )
                    }
                        .onFailure {
                            logger.error(it) {
                                "命令结果分发失败：pluginId=${runtime.descriptor.id}，traceId=${event.inReplyTo}"
                            }
                        }
                }
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

    public fun shutdown() {
        synchronized(lifecycleLock) {
            if (isShuttingDown) return
            isShuttingDown = true
        }
        stopAllPlugins()
        subscriptionChangedListenerToken?.let { eventBus.unsubscribe(it) }
        subscriptionChangedListenerToken = null
        drainAllDispatchJobs()
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

    public fun getPublisherLoginProviders(): List<PluginHandle<PublisherLoginProvider>> {
        return activeExtensionHandles()
    }

    public fun getDynamicLinkResolvers(): List<DynamicLinkResolver> {
        return activeExtensionHandles<DynamicLinkResolver>().map { it.instance }
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
            sourceUpdatePublisher = sourceUpdatePublisher,
            sourceStateStore = sourceStateStore,
            subscriptionQueryService = subscriptionQueryService,
        )

        runCatching {
            runBlocking { loaded.instance.onLoad(context) }
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

    private fun stopPlugin(runtime: PluginRuntime) {
        val pluginId = runtime.descriptor.id
        commandRegistry.unregisterByOwner(pluginId)

        if (runtime.state == PluginState.ACTIVE) {
            runtime.state = PluginState.LOADED
            drainDispatchJobs(pluginId)
            val stopped = runCatching {
                runBlocking { runtime.instance.onStop() }
                runtime.error = null
                logger.info { "插件已停止：pluginId=$pluginId" }
            }
            shutdownTaskScheduler(runtime)
            stopped.onFailure { e ->
                runtime.state = PluginState.FAILED
                runtime.error = e
                logger.error(e) { "插件停止失败：pluginId=$pluginId" }
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
        require(apiVersion == CORE_PLUGIN_API_VERSION) {
            "不支持的 apiVersion=$apiVersion，期望 $CORE_PLUGIN_API_VERSION"
        }
    }

    private fun inferCapabilities(instance: Plugin): Set<PluginCapability> {
        return buildSet {
            if (instance is PublisherSourcePlugin) add(PluginCapability.PUBLISHER_SOURCE)
            if (instance is PublisherLookupPlugin) add(PluginCapability.PUBLISHER_LOOKUP)
            if (instance is PublisherFollowPlugin) add(PluginCapability.PUBLISHER_FOLLOW)
            if (instance is PublisherLoginProvider) add(PluginCapability.PUBLISHER_LOGIN)
            if (instance is MessageSinkPlugin) add(PluginCapability.MESSAGE_SINK)
            if (instance is CommandContributor) add(PluginCapability.COMMAND_CONTRIBUTOR)
            if (instance is DynamicLinkResolver) add(PluginCapability.LINK_RESOLVER)
            if (instance is ConfigurablePlugin<*>) add(PluginCapability.CONFIGURABLE)
        }
    }

    private fun activeMessageSinkPlugins(): List<PluginRuntime> {
        return activePluginsWith(PluginCapability.MESSAGE_SINK)
    }

    private fun activePublisherSourcePlugins(): List<PluginRuntime> {
        return activePluginsWith(PluginCapability.PUBLISHER_SOURCE)
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
            PluginCapability.PUBLISHER_SOURCE in runtime.capabilities -> 20
            else -> 100
        }
    }

    private fun stopPriority(runtime: PluginRuntime): Int {
        return when {
            PluginCapability.PUBLISHER_SOURCE in runtime.capabilities -> 0
            PluginCapability.COMMAND_CONTRIBUTOR in runtime.capabilities -> 10
            PluginCapability.MESSAGE_SINK in runtime.capabilities -> 20
            else -> 100
        }
    }

    private fun createPluginScope(pluginId: String): CoroutineScope {
        val parentJob = scope.coroutineContext[Job]
        val contextWithoutJob = scope.coroutineContext.minusKey(Job) + CoroutineName("plugin-$pluginId")
        val job = if (parentJob != null) SupervisorJob(parentJob) else SupervisorJob()
        return CoroutineScope(contextWithoutJob + job)
    }

    private fun launchForPlugin(pluginId: String, block: suspend CoroutineScope.() -> Unit) {
        val runtime = plugins[pluginId] ?: return
        val jobSet = inFlightDispatchJobs.computeIfAbsent(pluginId) { ConcurrentHashMap.newKeySet() }
        val job = runtime.scope.launch(block = block)
        jobSet.add(job)
        job.invokeOnCompletion {
            jobSet.remove(job)
            if (jobSet.isEmpty()) {
                inFlightDispatchJobs.remove(pluginId, jobSet)
            }
        }
    }

    private fun legacyDelivery(message: Message, target: TargetAddress, index: Int): MessageDelivery {
        return MessageDelivery(
            id = -index - 1,
            messageId = message.id,
            sourceUpdateKey = message.sourceUpdateKey,
            renderVariant = message.renderVariant,
            target = target,
            status = DeliveryStatus.PENDING,
            attempts = 0,
            createdAtEpochSeconds = message.time,
            updatedAtEpochSeconds = message.time,
        )
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
    }

    private companion object {
        private val PLUGIN_ID_REGEX: Regex = Regex("^[a-zA-Z0-9._-]+$")
    }
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
