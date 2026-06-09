package top.colter.dynamic.admin

import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.MainConfigForms
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.DynamicFilterRule
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageDelivery
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformCapability
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.coreJson
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.message.OutboundMessageService
import top.colter.dynamic.message.RENDER_VARIANT_MANUAL_FORWARD
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.MessageTargetSource
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginManager
import top.colter.dynamic.plugin.PluginReloadResult
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.plugin.PluginTaskInfo
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskSnapshot
import top.colter.dynamic.core.task.TaskStatus
import top.colter.dynamic.draw.DefaultPublisherThemeInitializer
import top.colter.dynamic.draw.DrawThemePalette
import top.colter.dynamic.draw.PublisherDrawThemeService
import top.colter.dynamic.draw.PublisherThemeInitializer
import top.colter.dynamic.repository.DynamicFilterRuleRepository
import top.colter.dynamic.repository.LinkParseTargetConfigRepository
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PublisherDrawTheme
import top.colter.dynamic.repository.PublisherDrawThemeRepository
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.PublisherLiveRecord
import top.colter.dynamic.repository.PublisherLiveRecordRepository
import top.colter.dynamic.repository.PublisherLiveStatusRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SourceCursorRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.repository.SubscriptionMutationResult
import top.colter.dynamic.repository.UpsertResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import top.colter.dynamic.plugin.PluginCapability
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<AdminService>()

public class AdminService(
    private val pluginProvider: () -> List<PluginInfo>,
    private val messageSinkProvider: () -> List<PluginHandle<MessageSinkPlugin>> = { emptyList() },
    private val publisherLoginProvider: () -> List<PluginHandle<PublisherLoginProvider>> = { emptyList() },
    private val publisherLookupProvider: () -> List<PluginHandle<PublisherLookupPlugin>> = { emptyList() },
    private val publisherLookupResolver: (String) -> PublisherLookupPlugin?,
    private val publisherFollowResolver: (String) -> PublisherFollowPlugin?,
    private val configurablePluginProvider: () -> List<PluginHandle<ConfigurablePlugin<*>>> = { emptyList() },
    private val configProvider: () -> MainDynamicConfig,
    private val mainConfigUpdater: (MainDynamicConfig) -> ConfigApplyResult = {
        throw IllegalStateException("主配置编辑未初始化")
    },
    private val configService: ConfigService = YamlConfigService(),
    private val commandRegistry: CommandRegistry = CommandRegistry(),
    private val eventBus: EventBus = EventBus(),
    private val outboundMessageService: OutboundMessageService = OutboundMessageService(),
    private val pluginReloader: (String) -> PluginReloadResult = {
        throw IllegalStateException("插件重载功能未配置")
    },
    private val pluginStarter: (String) -> Unit = {
        throw IllegalStateException("插件启动功能未配置")
    },
    private val pluginStopper: (String) -> Unit = {
        throw IllegalStateException("插件停止功能未配置")
    },
    private val mainTaskScheduler: TaskScheduler? = null,
    private val pluginTaskProvider: () -> List<PluginTaskInfo> = { emptyList() },
    private val pluginTaskResolver: (String, String) -> PluginTaskInfo? = { _, _ -> null },
    private val pluginTaskStarter: (String, String) -> Boolean = { _, _ -> false },
    private val pluginTaskStopper: suspend (String, String) -> Boolean = { _, _ -> false },
    private val pluginTaskRestarter: suspend (String, String) -> Boolean = { _, _ -> false },
    private val pluginCatalogService: PluginCatalogService? = null,
    private val startedAtEpochMillis: Long = System.currentTimeMillis(),
    private val databasePathProvider: () -> String? = { PersistenceManager.currentPath() },
    private val publisherDrawThemeService: PublisherDrawThemeService = PublisherDrawThemeService(),
    private val publisherThemeInitializer: PublisherThemeInitializer = DefaultPublisherThemeInitializer(configProvider = configProvider),
) {
    private val loginStateCache: ConcurrentHashMap<String, CachedLoginState> = ConcurrentHashMap()
    private val publisherCandidateCacheLock = Any()
    private val publisherCandidateCache = LinkedHashMap<String, CachedPublisherCandidate>()
    private val subscriberTargetCandidateCacheLock = Any()
    private val subscriberTargetCandidateCache = LinkedHashMap<String, CachedMessageTargetCandidate>()

    public constructor(
        pluginManager: PluginManager,
        configProvider: () -> MainDynamicConfig,
        mainConfigUpdater: (MainDynamicConfig) -> ConfigApplyResult = {
            throw IllegalStateException("主配置编辑未初始化")
        },
        configService: ConfigService = YamlConfigService(),
        commandRegistry: CommandRegistry = CommandRegistry(),
        eventBus: EventBus = EventBus(),
        outboundMessageService: OutboundMessageService = OutboundMessageService(),
        mainTaskScheduler: TaskScheduler? = null,
        startedAtEpochMillis: Long = System.currentTimeMillis(),
    ) : this(
        pluginProvider = { pluginManager.getAllPlugins() },
        messageSinkProvider = { pluginManager.getMessageSinkPlugins() },
        publisherLoginProvider = { pluginManager.getPublisherLoginProviders() },
        publisherLookupProvider = { pluginManager.getPublisherLookupPlugins() },
        publisherLookupResolver = { platformId -> pluginManager.findPublisherLookupPlugin(platformId) },
        publisherFollowResolver = { platformId -> pluginManager.findPublisherFollowPlugin(platformId) },
        configurablePluginProvider = { pluginManager.getConfigurablePlugins() },
        configProvider = configProvider,
        mainConfigUpdater = mainConfigUpdater,
        configService = configService,
        commandRegistry = commandRegistry,
        eventBus = eventBus,
        outboundMessageService = outboundMessageService,
        pluginReloader = pluginManager::reloadPlugin,
        pluginStarter = pluginManager::startPlugin,
        pluginStopper = pluginManager::stopPlugin,
        mainTaskScheduler = mainTaskScheduler,
        pluginTaskProvider = pluginManager::getPluginTasks,
        pluginTaskResolver = pluginManager::getPluginTask,
        pluginTaskStarter = pluginManager::startPluginTask,
        pluginTaskStopper = pluginManager::stopPluginTask,
        pluginTaskRestarter = pluginManager::restartPluginTask,
        pluginCatalogService = PluginCatalogService(
            configProvider = { configProvider().pluginCatalog },
            pluginProvider = { pluginManager.getAllPlugins() },
            pluginDirPathProvider = { pluginManager.pluginDirPath },
            pluginInstaller = pluginManager::installOrUpdatePluginJar,
        ),
        startedAtEpochMillis = startedAtEpochMillis,
    )

    public suspend fun dashboard(): DashboardResponse {
        val pluginDtos = plugins()
        return DashboardResponse(
            generatedAtEpochMillis = System.currentTimeMillis(),
            system = systemStatus(),
            commandCount = commandRegistry.listCommands().size,
            publisherCount = PublisherRepository.findAll().size,
            subscriberCount = SubscriberRepository.findAll().size,
            subscriptionCount = SubscriptionRepository.countAll(),
            pluginStateCounts = pluginDtos
                .groupingBy { it.state }
                .eachCount()
                .toStateCounts(pluginDtos.size.toLong()),
            deliveryStatusCounts = MessageDeliveryRepository.countsByStatus()
                .map { (status, count) -> StateCountDto(status.name, count) },
            plugins = pluginDtos,
            platformLogins = platformLogins(),
            recentLogs = AdminLogBuffer.snapshot(level = "WARN,ERROR", limit = 8).entries.map { it.toDto() },
            recentDeliveries = deliveries(status = "FAILED", limit = 8),
        )
    }

    public fun systemStatus(): SystemStatusDto {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val config = configProvider()
        return SystemStatusDto(
            startedAtEpochMillis = startedAtEpochMillis,
            uptimeMs = (System.currentTimeMillis() - startedAtEpochMillis).coerceAtLeast(0),
            javaVersion = System.getProperty("java.version") ?: "",
            osName = System.getProperty("os.name") ?: "",
            availableProcessors = runtime.availableProcessors(),
            usedMemoryBytes = totalMemory - freeMemory,
            freeMemoryBytes = freeMemory,
            totalMemoryBytes = totalMemory,
            maxMemoryBytes = runtime.maxMemory(),
            databasePath = databasePathProvider(),
            mainConfigPath = configService.resolvePath(MainDynamicConfig.CONFIG_ID).toString(),
            webAdminEnabled = config.webAdmin.enabled,
            webAdminHost = config.webAdmin.host,
            webAdminPort = config.webAdmin.port,
        )
    }

    public fun tasks(): TaskListResponse {
        val taskDtos = currentTaskDtos()
        return TaskListResponse(
            generatedAtEpochMillis = System.currentTimeMillis(),
            tasks = taskDtos,
            statusCounts = taskDtos
                .groupingBy { it.status }
                .eachCount()
                .toStateCounts(taskDtos.size.toLong()),
        )
    }

    public suspend fun startTask(ownerType: String, ownerId: String, taskId: String): TaskOperationResponse {
        return operateTask(ownerType, ownerId, taskId, "恢复") { normalizedType, normalizedOwner, id ->
            when (normalizedType) {
                TASK_OWNER_MAIN -> mainTaskScheduler?.start(id) ?: false
                TASK_OWNER_PLUGIN -> pluginTaskStarter(normalizedOwner, id)
                else -> false
            }
        }
    }

    public suspend fun stopTask(ownerType: String, ownerId: String, taskId: String): TaskOperationResponse {
        return operateTask(ownerType, ownerId, taskId, "停止") { normalizedType, normalizedOwner, id ->
            when (normalizedType) {
                TASK_OWNER_MAIN -> mainTaskScheduler?.stop(id) ?: false
                TASK_OWNER_PLUGIN -> pluginTaskStopper(normalizedOwner, id)
                else -> false
            }
        }
    }

    public suspend fun restartTask(ownerType: String, ownerId: String, taskId: String): TaskOperationResponse {
        return operateTask(ownerType, ownerId, taskId, "重启") { normalizedType, normalizedOwner, id ->
            when (normalizedType) {
                TASK_OWNER_MAIN -> mainTaskScheduler?.restart(id) ?: false
                TASK_OWNER_PLUGIN -> pluginTaskRestarter(normalizedOwner, id)
                else -> false
            }
        }
    }

    public fun logs(
        since: Long? = null,
        level: String? = null,
        logger: String? = null,
        query: String? = null,
        limit: Int? = null,
    ): AdminLogResponse {
        val snapshot = AdminLogBuffer.snapshot(
            since = since,
            level = level,
            logger = logger,
            query = query,
            limit = limit ?: 300,
        )
        return AdminLogResponse(
            entries = snapshot.entries.map { it.toDto() },
            nextSince = snapshot.nextSince,
            capacity = snapshot.capacity,
            retainedCount = snapshot.retainedCount,
        )
    }

    public fun deliveries(
        status: String? = null,
        platformId: String? = null,
        targetKind: String? = null,
        query: String? = null,
        limit: Int? = null,
    ): List<MessageDeliveryDto> {
        val deliveryStatus = status
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { parseEnum<DeliveryStatus>(it, "status") }
        val deliveryTargetKind = targetKind
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { parseEnum<TargetKind>(it, "targetKind") }
        return MessageDeliveryRepository
            .findRecent(
                status = deliveryStatus,
                platformId = platformId,
                targetKind = deliveryTargetKind,
                query = query,
                limit = limit ?: 50,
            )
            .map { it.toDto() }
    }

    public fun delivery(id: Int): MessageDeliveryDetailDto {
        val delivery = MessageDeliveryRepository.findById(id)
            ?: throw NoSuchElementException("消息记录不存在：$id")
        val message = MessageDeliveryRepository.findMessage(delivery.messageId)
        return MessageDeliveryDetailDto(
            delivery = delivery.toDto(),
            message = message?.toJsonElement(),
        )
    }

    public suspend fun forwardMessage(request: CreateMessageForwardRequest): MessageForwardResponse {
        val targets = request.targets.map { target ->
            TargetAddress.of(
                platformId = target.platformId,
                kind = parseEnum<TargetKind>(target.targetKind, "targetKind"),
                externalId = target.externalId,
                scopeId = target.scopeId,
                threadId = target.threadId,
                accountId = target.accountId,
            )
        }
        val text = request.text?.trim()?.takeIf { it.isNotBlank() }
        val batches = request.batches?.takeIf { it.isNotEmpty() }
        require((text != null) xor (batches != null)) { "text 和 batches 必须且只能提供一个" }

        val result = if (text != null) {
            outboundMessageService.enqueueText(
                source = "web-admin",
                targets = targets,
                text = text,
                renderVariant = RENDER_VARIANT_MANUAL_FORWARD,
            )
        } else {
            outboundMessageService.enqueueBatches(
                source = "web-admin",
                targets = targets,
                batches = batches.orEmpty(),
                renderVariant = RENDER_VARIANT_MANUAL_FORWARD,
            )
        }
        val newDeliveryIds = result.newDeliveries.mapTo(mutableSetOf()) { it.id }
        return MessageForwardResponse(
            messageId = result.message.id,
            targetCount = result.targetCount,
            newDeliveryCount = result.newDeliveryCount,
            existingDeliveryCount = result.existingDeliveryCount,
            deliveries = (result.newDeliveries + result.existingDeliveries)
                .sortedWith(compareBy<MessageDelivery> { it.target.platformId.value }
                    .thenBy { it.target.kind.name }
                    .thenBy { it.target.externalId })
                .map { delivery -> delivery.toForwardDto(newDelivery = delivery.id in newDeliveryIds) },
        )
    }

    public fun plugins(): List<PluginDto> {
        val catalogById = pluginCatalogService
            ?.cachedCatalog()
            ?.plugins
            ?.associateBy { it.id }
            .orEmpty()
        return pluginProvider()
            .sortedBy { it.descriptor.id }
            .map { it.toDto(catalogById[it.descriptor.id]) }
    }

    public fun pluginCatalog(force: Boolean = false): PluginCatalogResponse {
        return requirePluginCatalogService().catalog(force = force)
    }

    public fun installCatalogPlugin(id: String): PluginCatalogOperationResponse {
        return requirePluginCatalogService().install(id)
    }

    public fun updateCatalogPlugin(id: String): PluginCatalogOperationResponse {
        return requirePluginCatalogService().update(id)
    }

    public fun reloadPlugin(id: String): PluginReloadResponse {
        val pluginId = id.trim()
        require(pluginId.isNotBlank()) { "插件 ID 不能为空" }
        pluginProvider().firstOrNull { it.descriptor.id == pluginId }
            ?: throw NoSuchElementException("未找到插件：$pluginId")

        val result = pluginReloader(pluginId)
        val response = result.toResponse()
        if (!result.success) throw PluginReloadFailedException(response)
        return response
    }

    public fun startPlugin(id: String): PluginLifecycleResponse {
        val pluginId = id.trim()
        require(pluginId.isNotBlank()) { "插件 ID 不能为空" }
        val before = pluginInfoOrThrow(pluginId)
        return when (before.state) {
            PluginState.ACTIVE -> PluginLifecycleResponse(
                changed = false,
                success = true,
                pluginId = pluginId,
                pluginState = before.state.name,
                message = "插件已在运行：$pluginId",
            )
            PluginState.FAILED -> throw IllegalStateException("失败插件需要重载后再启动：$pluginId")
            PluginState.LOADED -> {
                pluginStarter(pluginId)
                val after = pluginInfoOrThrow(pluginId)
                if (after.state != PluginState.ACTIVE) {
                    throw IllegalStateException(after.errorText() ?: "插件启动后状态异常：${after.state.name}")
                }
                PluginLifecycleResponse(
                    changed = true,
                    success = true,
                    pluginId = pluginId,
                    pluginState = after.state.name,
                    message = "插件已启动：$pluginId",
                )
            }
        }
    }

    public fun stopPlugin(id: String): PluginLifecycleResponse {
        val pluginId = id.trim()
        require(pluginId.isNotBlank()) { "插件 ID 不能为空" }
        val before = pluginInfoOrThrow(pluginId)
        return when (before.state) {
            PluginState.LOADED -> PluginLifecycleResponse(
                changed = false,
                success = true,
                pluginId = pluginId,
                pluginState = before.state.name,
                message = "插件已停止：$pluginId",
            )
            PluginState.FAILED -> throw IllegalStateException("失败插件无法执行停止操作：$pluginId")
            PluginState.ACTIVE -> {
                pluginStopper(pluginId)
                val after = pluginInfoOrThrow(pluginId)
                if (after.state == PluginState.FAILED) {
                    throw IllegalStateException(after.errorText() ?: "插件停止失败：$pluginId")
                }
                PluginLifecycleResponse(
                    changed = true,
                    success = true,
                    pluginId = pluginId,
                    pluginState = after.state.name,
                    message = "插件已停止：$pluginId",
                )
            }
        }
    }

    public suspend fun platformLogins(force: Boolean = false): List<PlatformLoginDto> = coroutineScope {
        val handles = publisherLoginProvider()
        val activeCacheKeys = handles
            .map { handle -> "${handle.info.descriptor.id}:${handle.instance.platformId.value}" }
            .toSet()
        loginStateCache.keys.removeIf { it !in activeCacheKeys }
        handles
            .map { handle ->
                async { handle.toPlatformLoginDto(force) }
            }
            .awaitAll()
            .sortedBy { it.platformId }
    }

    public suspend fun targetPlatformAccounts(): List<TargetPlatformAccountDto> = coroutineScope {
        messageSinkProvider()
            .map { handle -> async { handle.toTargetPlatformAccountDtos() } }
            .awaitAll()
            .flatten()
            .sortedWith(
                compareBy<TargetPlatformAccountDto> { it.platformId }
                    .thenBy { it.accountId }
                    .thenBy { it.transportId }
                    .thenBy { it.routeId },
            )
    }

    public fun commands(): List<CommandDto> {
        return commandRegistry.listCommands().map { spec ->
            CommandDto(
                path = spec.path,
                pathText = spec.path.joinToString(" "),
                aliases = spec.aliases,
                description = spec.description,
                usage = spec.usage,
                requiredRole = spec.requiredRole.name,
            )
        }
    }

    private suspend fun PluginHandle<MessageSinkPlugin>.toTargetPlatformAccountDtos(): List<TargetPlatformAccountDto> {
        val routedSink = instance as? AccountRoutedMessageSinkPlugin ?: return emptyList()
        val pluginInfo = info
        val checkedAt = System.currentTimeMillis()
        val routes = if (pluginInfo.state == PluginState.ACTIVE) {
            runCatching {
                withTimeoutOrNull(LOGIN_STATE_TIMEOUT_MS) {
                    routedSink.listMessageSinkRoutes()
                }.orEmpty()
            }.getOrElse {
                logger.warn(it) { "目标平台账号状态读取失败：pluginId=${pluginInfo.descriptor.id}" }
                emptyList()
            }
        } else {
            emptyList()
        }
        return routes.map { route ->
            TargetPlatformAccountDto(
                platformId = route.targetPlatformId.value,
                transportId = route.transportId,
                transportName = route.transportName,
                pluginId = pluginInfo.descriptor.id,
                pluginName = pluginInfo.descriptor.name,
                pluginVersion = pluginInfo.descriptor.version,
                pluginState = pluginInfo.state.name,
                routeId = route.routeId,
                accountId = route.accountId,
                accountName = route.accountName,
                avatarUri = route.accountAvatar?.uri,
                enabled = route.enabled,
                state = route.state.name,
                checkedAtEpochMillis = checkedAt,
            )
        }
    }

    private suspend fun PluginHandle<PublisherLoginProvider>.toPlatformLoginDto(force: Boolean): PlatformLoginDto {
        val pluginInfo = this.info
        val plugin = this.instance
        val cacheKey = "${pluginInfo.descriptor.id}:${plugin.platformId.value}"
        val loginState = if (pluginInfo.state == PluginState.ACTIVE) {
            cachedLoginState(cacheKey, plugin, force)
        } else {
            CachedLoginState(
                result = PublisherLoginResult(
                    status = PublisherLoginStatus.FAILED,
                    message = "插件未运行：${pluginInfo.state.name}",
                ),
                checkedAtEpochMillis = System.currentTimeMillis(),
            )
        }
        return PlatformLoginDto(
            platformId = plugin.platformId.value,
            pluginId = pluginInfo.descriptor.id,
            pluginName = pluginInfo.descriptor.name,
            pluginVersion = pluginInfo.descriptor.version,
            pluginState = pluginInfo.state.name,
            supportedLoginMethods = plugin.supportedLoginMethods.map { it.name }.sorted(),
            status = loginState.result.status.name,
            message = loginState.result.message,
            checkedAtEpochMillis = loginState.checkedAtEpochMillis,
            actions = plugin.loginActions(pluginInfo.state),
            account = loginState.result.account?.toDto(),
        )
    }

    private suspend fun cachedLoginState(
        cacheKey: String,
        plugin: PublisherLoginProvider,
        force: Boolean,
    ): CachedLoginState {
        val now = System.currentTimeMillis()
        if (!force) {
            loginStateCache[cacheKey]
                ?.takeIf { now - it.checkedAtEpochMillis <= LOGIN_STATE_CACHE_TTL_MS }
                ?.let { return it }
        }

        val result = try {
            withTimeoutOrNull(LOGIN_STATE_TIMEOUT_MS) {
                plugin.checkLoginState()
            } ?: PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "登录状态检查超时",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = e.message ?: "登录状态检查失败",
            )
        }
        val checked = CachedLoginState(result, System.currentTimeMillis())
        loginStateCache[cacheKey] = checked
        return checked
    }

    private fun PublisherLoginProvider.loginActions(pluginState: PluginState): List<PlatformLoginActionDto> {
        val active = pluginState == PluginState.ACTIVE
        val inactiveReason = if (active) null else "插件未运行：${pluginState.name}"
        fun action(
            key: String,
            label: String,
            description: String,
            available: Boolean,
            unavailableReason: String,
            sensitive: Boolean = false,
        ): PlatformLoginActionDto {
            val reason = inactiveReason ?: if (available) null else unavailableReason
            return PlatformLoginActionDto(
                key = key,
                label = label,
                description = description,
                enabled = active && available,
                reason = reason,
                sensitive = sensitive,
            )
        }

        return listOf(
            action(
                key = "QR_LOGIN",
                label = "扫码登录",
                description = "使用平台 App 扫码并确认登录。",
                available = PublisherLoginMethod.QR_CODE in supportedLoginMethods,
                unavailableReason = "当前平台不支持扫码登录",
                sensitive = true,
            ),
            action(
                key = "COOKIE_LOGIN",
                label = "Cookie 登录",
                description = "粘贴浏览器 Cookie 或插件支持的 Cookie JSON 进行登录。",
                available = PublisherLoginMethod.COOKIE in supportedLoginMethods,
                unavailableReason = "当前平台不支持 Cookie 登录",
                sensitive = true,
            ),
            action(
                key = "COOKIE_EXPORT",
                label = "导出 Cookie",
                description = "导出当前保存的浏览器 Cookie JSON。",
                available = supportsCookieExport,
                unavailableReason = "当前平台不支持 Cookie 导出",
                sensitive = true,
            ),
            action(
                key = "REFRESH_STATUS",
                label = "刷新",
                description = "重新检查当前账号登录状态。",
                available = true,
                unavailableReason = "",
            ),
        )
    }

    public fun publishers(): List<PublisherDto> {
        val subscriptions = SubscriptionRepository.findAll()
        val subscriptionCounts = subscriptions.groupingBy { it.publisherId }.eachCount()
        val liveSubscriptionCounts = subscriptions
            .filter { subscription ->
                subscription.state == EntityState.ACTIVE &&
                    subscription.policy.enabledEvents.any { event ->
                        event == SubscriptionEventKind.LIVE_STARTED || event == SubscriptionEventKind.LIVE_ENDED
                    }
            }
            .groupingBy { it.publisherId }
            .eachCount()
        val liveStatuses = PublisherLiveStatusRepository.findAll().groupBy { it.publisherId }
        val cursors = SourceCursorRepository.findAll().groupBy { it.publisherId }
        val drawThemes = PublisherDrawThemeRepository.findAll().associateBy { it.publisherId }
        val publishers = PublisherRepository.findAll()
            .sortedWith(compareBy<Publisher> { it.platformId.value }.thenBy { it.externalId })
        val liveRecords = PublisherLiveRecordRepository.findRecentByPublisherIds(publishers.map { it.id }, limitPerPublisher = 10)
        return publishers.map { publisher ->
            publisher.toDto(
                subscriptionCount = subscriptionCounts[publisher.id]?.toLong() ?: 0,
                liveSubscriptionCount = liveSubscriptionCounts[publisher.id]?.toLong() ?: 0,
                liveStatuses = liveStatuses[publisher.id].orEmpty(),
                liveRecords = liveRecords[publisher.id].orEmpty(),
                cursors = cursors[publisher.id].orEmpty(),
                drawTheme = drawThemes[publisher.id],
            )
        }
    }

    public fun publisherPlatforms(): List<PublisherPlatformDto> {
        return publisherLookupProvider()
            .map { handle ->
                val info = handle.info
                val plugin = handle.instance
                val descriptor = plugin.platformDescriptor
                val capabilities = descriptor.capabilities.map { it.name }.sorted()
                PublisherPlatformDto(
                    platformId = plugin.platformId.value,
                    pluginId = info.descriptor.id,
                    pluginName = info.descriptor.name,
                    pluginState = info.state.name,
                    capabilities = capabilities,
                    supportsLive = PlatformCapability.LIVE_SOURCE in descriptor.capabilities,
                )
            }
            .sortedWith(compareBy<PublisherPlatformDto> { it.platformId }.thenBy { it.pluginId })
    }

    public suspend fun searchPublishers(platformId: String?, query: String?): List<PublisherCandidateDto> {
        val platform = platformId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("发布者平台不能为空")
        val externalId = query?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("发布者 ID 不能为空")
        val handle = publisherLookupProvider()
            .firstOrNull { it.instance.platformId.value.equals(platform, ignoreCase = true) }
        val pluginInfo = handle?.info ?: fallbackPublisherPluginInfo(platform)
        cachedPublisherCandidate(PublisherKey.of(platform, PublisherKind.USER, externalId))?.let { cached ->
            return listOf(cached.toCandidateDto(pluginInfo))
        }
        val publisherKey = PublisherKey.of(platform, PublisherKind.USER, externalId)
        PublisherRepository.findByKey(publisherKey)
            ?.takeUnless { it.isPlaceholderPublisher() }
            ?.let { publisher ->
                return listOf(publisher.toInfo().toCandidateDto(pluginInfo))
            }
        val plugin = handle?.instance ?: publisherLookupResolver(platform)
            ?: throw NoSuchElementException("未找到发布者查询插件：$platform")
        val publisherInfo = plugin.fetchPublisherInfo(externalId)?.normalized() ?: return emptyList()
        rememberPublisherCandidate(publisherInfo)
        return listOf(publisherInfo.toCandidateDto(pluginInfo))
    }

    public suspend fun createPublisher(request: CreatePublisherRequest): PublisherDto {
        val platform = request.platformId.trim().lowercase().also {
            require(it.isNotBlank()) { "发布者平台不能为空" }
        }
        val externalId = request.externalId.trim().also {
            require(it.isNotBlank()) { "发布者 ID 不能为空" }
        }
        val (publisherInfo, _) = fetchPublisherInfo(platform, externalId)
        val upsert = PublisherRepository.upsertInfo(publisherInfo.normalized())
        val publisher = upsert.value
        publisherThemeInitializer.initializeAfterPublisherUpsert(publisher)
        logger.info {
            "后台发布者已${upsert.operationLabel()}：publisherId=${publisher.id}，platform=$platform，externalId=$externalId"
        }
        return PublisherRepository.findById(publisher.id)?.toDto(
            drawTheme = PublisherDrawThemeRepository.findByPublisherId(publisher.id),
        ) ?: publisher.toDto()
    }

    public fun updatePublisher(id: Int, request: UpdatePublisherRequest): PublisherDto {
        val publisher = PublisherRepository.findById(id) ?: throw NoSuchElementException("未找到发布者：$id")
        val updated = publisher.copy(
            name = request.name?.trim()?.takeIf { it.isNotBlank() } ?: publisher.name,
            banner = request.headerUri?.trim()?.takeIf { it.isNotBlank() }?.let { MediaRef(uri = it, kind = MediaKind.COVER) }
                ?: if (request.headerUri != null) null else publisher.banner,
            state = request.state?.let { parseEnum<EntityState>(it, "state") } ?: publisher.state,
        )
        PublisherRepository.replace(updated)
        if (request.clearTheme) {
            publisherDrawThemeService.clearTheme(id)
        } else {
            request.themeColors?.trim()?.takeIf { it.isNotBlank() }?.let { colors ->
                publisherDrawThemeService.setTheme(id, colors)
            }
        }
        val drawTheme = PublisherDrawThemeRepository.findByPublisherId(id)
        logger.info { "后台发布者已更新：publisherId=$id，platform=${updated.platformId.value}，state=${updated.state}" }
        return PublisherRepository.findById(id)?.toDto(drawTheme = drawTheme) ?: updated.toDto(drawTheme = drawTheme)
    }

    public fun deletePublisher(id: Int): ActionResultResponse {
        val publisher = PublisherRepository.findById(id) ?: throw NoSuchElementException("未找到发布者：$id")
        val removedSubscriptions = SubscriptionRepository.findAll()
            .filter { it.publisherId == publisher.id }
            .count { subscription ->
                applySubscriptionMutation(
                    SubscriptionRepository.unsubscribe(subscription.subscriberId, subscription.publisherId),
                )
            }
        SourceCursorRepository.deleteByPublisherId(publisher.id)
        PublisherLiveStatusRepository.deleteByPublisherId(publisher.id)
        PublisherLiveRecordRepository.deleteByPublisherId(publisher.id)
        PublisherDrawThemeRepository.deleteByPublisherId(publisher.id)
        val removed = PublisherRepository.deleteById(publisher.id)
        if (removed) {
            logger.info {
                "后台发布者已删除：publisherId=${publisher.id}，platform=${publisher.platformId.value}，externalId=${publisher.externalId}，关联订阅=$removedSubscriptions"
            }
        }
        return ActionResultResponse(
            changed = removed,
            message = if (removed) {
                "发布者已删除：关联订阅=$removedSubscriptions"
            } else {
                "发布者未变化"
            },
        )
    }

    public fun subscribers(): List<SubscriberDto> {
        val subscriptions = SubscriptionRepository.findAll()
        val subscriptionCounts = subscriptions.groupingBy { it.subscriberId }.eachCount()
        val linkParseConfigs = LinkParseTargetConfigRepository.findAll().associateBy { it.address.stableValue() }
        val fallbackTriggerMode = configProvider().linkParsing.fallbackTriggerMode
        return SubscriberRepository.findAll()
            .sortedWith(compareBy<Subscriber> { it.platformId.value }.thenBy { it.kind.name }.thenBy { it.externalId })
            .map { subscriber ->
                subscriber.toDto(
                    subscriptionCount = subscriptionCounts[subscriber.id]?.toLong() ?: 0,
                    linkParseTriggerMode = linkParseConfigs[subscriber.address.stableValue()]?.triggerMode,
                    fallbackTriggerMode = fallbackTriggerMode,
                )
            }
    }

    public fun subscriberTargetPlatforms(): List<SubscriberTargetPlatformDto> {
        return messageSinkProvider()
            .flatMap { handle ->
                val info = handle.info
                val sink = handle.instance
                sink.supportedTargetPlatforms.map { platformId ->
                    SinkPlatformEntry(
                        platformId = platformId.value,
                        pluginId = info.descriptor.id,
                        pluginName = info.descriptor.name,
                        pluginState = info.state.name,
                        transportId = sink.transportId,
                        supportedTypes = sink.supportedTargetKinds.map { it.name }.sorted(),
                    )
                }
            }
            .groupBy { it.platformId }
            .map { (platformId, entries) ->
                SubscriberTargetPlatformDto(
                    platformId = platformId,
                    pluginId = entries.joinToString(",") { it.pluginId },
                    pluginName = entries.joinToString("、") { it.pluginName },
                    pluginState = entries.map { it.pluginState }.distinct().joinToString(","),
                    supportedTypes = entries.flatMap { it.supportedTypes }.distinct().sorted(),
                    transportIds = entries.map { it.transportId }.distinct().sorted(),
                    transportCount = entries.map { it.transportId }.distinct().size,
                )
            }
            .sortedWith(compareBy<SubscriberTargetPlatformDto> { it.platformId }.thenBy { it.pluginId })
    }

    public suspend fun subscriberTargets(platformId: String?, type: String?): List<SubscriberTargetDto> {
        val normalizedPlatform = platformId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val targetType = type?.trim()?.takeIf { it.isNotBlank() }?.let { parseEnum<TargetKind>(it, "type") }
        val targets = mutableListOf<MessageTargetWithPlugin>()
        messageSinkProvider()
            .forEach { handle ->
                val info = handle.info
                val sink = handle.instance
                if (normalizedPlatform != null && sink.supportedTargetPlatforms.none {
                        it.value.equals(normalizedPlatform, ignoreCase = true)
                    }
                ) {
                    return@forEach
                }
                if (targetType != null && sink.supportedTargetKinds.isNotEmpty() && targetType !in sink.supportedTargetKinds) {
                    return@forEach
                }
                val listedTargets = sink.listMessageTargets(targetType)
                    .filter { target ->
                        (normalizedPlatform == null || target.address.platformId.value.equals(normalizedPlatform, ignoreCase = true)) &&
                            (targetType == null || target.address.kind == targetType)
                    }
                rememberMessageTargetCandidates(listedTargets)
                targets += listedTargets.map { target -> MessageTargetWithPlugin(target, info) }
            }
        return targets
            .groupBy { it.candidate.address.stableValue() }
            .values
            .map { group -> group.toSubscriberTargetDto() }
            .sortedWith(compareBy<SubscriberTargetDto> { it.platformId }.thenBy { it.targetKind }.thenBy { it.name }.thenBy { it.externalId })
    }

    public suspend fun createSubscriber(request: CreateSubscriberRequest): SubscriberDto {
        val platformId = request.platformId.trim().also {
            require(it.isNotBlank()) { "消息目标平台不能为空" }
        }
        val externalId = request.externalId.trim().also {
            require(it.isNotBlank()) { "消息目标 ID 不能为空" }
        }
        val address = TargetAddress.of(
            platformId = platformId,
            kind = parseEnum<TargetKind>(request.targetKind, "targetKind"),
            externalId = externalId,
            scopeId = request.scopeId,
            threadId = request.threadId,
            accountId = request.accountId,
        )
        val existed = SubscriberRepository.findByAddress(address)
        val targetProfile = cachedMessageTargetCandidate(address)
            ?: resolveSubscriberTarget(address)
        val name = request.name?.trim()?.takeIf { it.isNotBlank() }
            ?: targetProfile?.name?.trim()?.takeIf { it.isNotBlank() }
            ?: existed?.name
            ?: address.externalId
        val entityState = request.state?.let { parseEnum<EntityState>(it, "state") }
            ?: existed?.state
            ?: EntityState.ACTIVE
        val upsert = SubscriberRepository.upsert(
            address = address,
            name = name,
            avatar = targetProfile?.avatar,
            state = entityState,
        )
        parseLinkParseTriggerModeOrNull(request.linkParseTriggerMode)?.let { mode ->
            LinkParseTargetConfigRepository.upsert(
                address = address,
                triggerMode = mode,
                updatedBy = "web-admin",
            )
        }
        val linkParseConfig = LinkParseTargetConfigRepository.findByAddress(address)
        logger.info {
            "后台消息目标已${upsert.operationLabel()}：subscriberId=${upsert.value.id}，target=${address.stableValue()}，state=${upsert.value.state}"
        }
        return upsert.value.toDto(
            linkParseTriggerMode = linkParseConfig?.triggerMode,
            fallbackTriggerMode = configProvider().linkParsing.fallbackTriggerMode,
        )
    }

    public fun updateSubscriber(id: Int, request: UpdateSubscriberRequest): SubscriberDto {
        val subscriber = SubscriberRepository.findById(id) ?: throw NoSuchElementException("未找到消息目标：$id")
        val updated = subscriber.copy(
            name = request.name?.trim()?.takeIf { it.isNotBlank() } ?: subscriber.name,
            state = request.state?.let { parseEnum<EntityState>(it, "state") } ?: subscriber.state,
        )
        SubscriberRepository.replace(updated)
        if (request.clearLinkParseTrigger) {
            LinkParseTargetConfigRepository.deleteByAddress(updated.address)
        } else {
            parseLinkParseTriggerModeOrNull(request.linkParseTriggerMode)?.let { mode ->
                LinkParseTargetConfigRepository.upsert(
                    address = updated.address,
                    triggerMode = mode,
                    updatedBy = "web-admin",
                )
            }
        }
        val linkParseConfig = LinkParseTargetConfigRepository.findByAddress(updated.address)
        logger.info {
            "后台消息目标已更新：subscriberId=$id，target=${updated.address.stableValue()}，state=${updated.state}"
        }
        return (SubscriberRepository.findById(id) ?: updated).toDto(
            linkParseTriggerMode = linkParseConfig?.triggerMode,
            fallbackTriggerMode = configProvider().linkParsing.fallbackTriggerMode,
        )
    }

    public fun deleteSubscriber(id: Int): ActionResultResponse {
        val subscriber = SubscriberRepository.findById(id) ?: throw NoSuchElementException("未找到消息目标：$id")
        val removedSubscriptions = SubscriptionRepository.findAll()
            .filter { it.subscriberId == subscriber.id }
            .count { subscription ->
                applySubscriptionMutation(
                    SubscriptionRepository.unsubscribe(subscription.subscriberId, subscription.publisherId),
                )
        }
        LinkParseTargetConfigRepository.deleteByAddress(subscriber.address)
        val removed = SubscriberRepository.deleteById(subscriber.id)
        if (removed) {
            logger.info {
                "后台消息目标已删除：subscriberId=${subscriber.id}，target=${subscriber.address.stableValue()}，关联订阅=$removedSubscriptions"
            }
        }
        return ActionResultResponse(
            changed = removed,
            message = if (removed) {
                "消息目标已删除：关联订阅=$removedSubscriptions"
            } else {
                "消息目标未变化"
            },
        )
    }

    public fun subscriptions(): List<SubscriptionDto> {
        val publishers = PublisherRepository.findAll().associateBy { it.id }
        val subscribers = SubscriberRepository.findAll().associateBy { it.id }
        val subscriptions = SubscriptionRepository.findAll()
        val rules = DynamicFilterRuleRepository.findBySubscriptionIds(subscriptions.map { it.id })
        return subscriptions
            .sortedBy { it.id }
            .map { it.toDto(publishers, subscribers, rules[it.id].orEmpty()) }
    }

    public fun exportSubscriptions(request: SubscriptionExportRequest): SubscriptionExportDocument {
        val allSubscriptions = SubscriptionRepository.findAll().sortedBy { it.id }
        val selectedSubscriptions = request.subscriptionIds
            ?.takeIf { it.isNotEmpty() }
            ?.let { requestedIds ->
                val byId = allSubscriptions.associateBy { it.id }
                val missing = requestedIds.distinct().filterNot { it in byId }
                require(missing.isEmpty()) { "订阅不存在：${missing.joinToString(", ")}" }
                requestedIds.distinct().map { byId.getValue(it) }
            }
            ?: allSubscriptions

        val publishers = PublisherRepository.findAll().associateBy { it.id }
        val subscribers = SubscriberRepository.findAll().associateBy { it.id }
        val rules = DynamicFilterRuleRepository.findBySubscriptionIds(selectedSubscriptions.map { it.id })
        return SubscriptionExportDocument(
            exportedAtEpochSeconds = System.currentTimeMillis() / 1_000,
            subscriptions = selectedSubscriptions.map { subscription ->
                val publisher = publishers[subscription.publisherId]
                    ?: throw NoSuchElementException("订阅缺少发布者：subscriptionId=${subscription.id}")
                val subscriber = subscribers[subscription.subscriberId]
                    ?: throw NoSuchElementException("订阅缺少消息目标：subscriptionId=${subscription.id}")
                val linkParseConfig = LinkParseTargetConfigRepository.findByAddress(subscriber.address)
                SubscriptionExportItem(
                    publisher = SubscriptionExportPublisher(
                        platformId = publisher.platformId.value,
                        kind = publisher.kind.name,
                        externalId = publisher.externalId,
                    ),
                    target = SubscriptionExportTarget(
                        platformId = subscriber.platformId.value,
                        targetKind = subscriber.kind.name,
                        externalId = subscriber.externalId,
                        scopeId = subscriber.address.scopeId,
                        threadId = subscriber.address.threadId,
                        accountId = subscriber.address.accountId,
                    ),
                    policy = subscription.policy,
                    filterRules = rules[subscription.id].orEmpty()
                        .sortedBy { it.id }
                        .map { SubscriptionExportFilterRule(it.condition) },
                    linkParseTriggerMode = linkParseConfig?.triggerMode?.name,
                )
            },
        )
    }

    public suspend fun importSubscriptions(document: SubscriptionExportDocument): SubscriptionImportResponse {
        require(document.schemaVersion == 1) { "订阅导入文件版本不支持：${document.schemaVersion}" }

        val warnings = mutableListOf<String>()
        val latestByRelation = linkedMapOf<String, IndexedValue<SubscriptionExportItem>>()
        var duplicateCount = 0
        document.subscriptions.forEachIndexed { index, item ->
            val key = item.relationKeyOrFallback()
            if (latestByRelation.containsKey(key)) {
                latestByRelation.remove(key)
                duplicateCount += 1
                warnings += "导入文件中存在重复订阅，已使用最后一条：$key"
            }
            latestByRelation[key] = IndexedValue(index, item)
        }

        var created = 0
        var updated = 0
        var failed = 0
        val items = mutableListOf<SubscriptionImportItemResult>()
        for ((index, item) in latestByRelation.values) {
            val publisherKey = item.publisherKeyText()
            val targetKey = item.targetKeyText()
            try {
                val filterConditions = item.filterRules.map { it.condition }
                validateSubscriptionImportItemBeforeMutation(item, filterConditions)
                val response = createSubscriptionInternal(
                    request = item.toCreateSubscriptionRequest(),
                    replacementFilterConditions = filterConditions,
                )
                val status = if (response.subscriptionCreated) "CREATED" else "UPDATED"
                if (response.subscriptionCreated) created += 1 else updated += 1
                items += SubscriptionImportItemResult(
                    index = index,
                    status = status,
                    message = if (response.subscriptionCreated) "订阅已创建" else "订阅已更新",
                    publisherKey = publisherKey,
                    targetKey = targetKey,
                    filterRuleCount = filterConditions.size,
                    subscription = response.subscription,
                    warnings = response.warnings,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += 1
                items += SubscriptionImportItemResult(
                    index = index,
                    status = "FAILED",
                    message = e.message ?: e.javaClass.name,
                    publisherKey = publisherKey,
                    targetKey = targetKey,
                    filterRuleCount = item.filterRules.size,
                )
            }
        }

        logger.info {
            "后台订阅导入完成：total=${document.subscriptions.size}，created=$created，updated=$updated，failed=$failed，duplicates=$duplicateCount"
        }
        return SubscriptionImportResponse(
            total = document.subscriptions.size,
            created = created,
            updated = updated,
            failed = failed,
            skipped = duplicateCount,
            duplicateCount = duplicateCount,
            items = items,
            warnings = warnings,
        )
    }

    public suspend fun createSubscription(request: CreateSubscriptionRequest): CreateSubscriptionResponse {
        return createSubscriptionInternal(request, replacementFilterConditions = null)
    }

    private fun validateSubscriptionImportItemBeforeMutation(
        item: SubscriptionExportItem,
        filterConditions: List<FilterCondition>,
    ) {
        filterConditions.forEach(DynamicFilterRuleRepository::validateCondition)
        parseLinkParseTriggerModeOrNull(item.linkParseTriggerMode)

        val publisherKey = item.importPublisherKey()
        val targetAddress = item.importTargetAddress()
        val existingPublisher = PublisherRepository.findByKey(publisherKey)
        val existingSubscriber = SubscriberRepository.findByAddress(targetAddress)
        val existingSubscription = if (existingPublisher != null && existingSubscriber != null) {
            SubscriptionRepository.findBySubscriberAndPublisher(
                subscriberId = existingSubscriber.id,
                publisherId = existingPublisher.id,
            )
        } else {
            null
        }
        requireSubscriptionPolicyAllowed(
            subscriber = existingSubscriber ?: Subscriber(
                id = 0,
                address = targetAddress,
                name = targetAddress.externalId,
                state = EntityState.ACTIVE,
                createTime = 0,
                createUser = 0,
            ),
            publisher = existingPublisher ?: Publisher(
                id = 0,
                key = publisherKey,
                name = publisherKey.externalId,
                avatar = MediaRef("import://placeholder", MediaKind.AVATAR),
                createTime = 0,
                createUser = 0,
            ),
            policy = item.policy,
            liveSupport = publisherPlatformLiveSupport(publisherKey.platformId.value),
            previousPolicy = existingSubscription?.policy,
        )
    }

    private suspend fun createSubscriptionInternal(
        request: CreateSubscriptionRequest,
        replacementFilterConditions: List<FilterCondition>?,
    ): CreateSubscriptionResponse {
        val warnings = mutableListOf<String>()
        val publisherUpsert = if (request.publisherId != null) {
            val publisher = PublisherRepository.findById(request.publisherId)
                ?: throw NoSuchElementException("发布者不存在：publisherId=${request.publisherId}")
            UpsertResult(publisher, created = false, updated = false)
        } else {
            val publisherLookupMode = parsePublisherLookupMode(request.publisherLookupMode)
            val platform = request.publisherPlatform?.trim()?.lowercase().orEmpty()
            val publisherKind = request.publisherKind
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { parseEnum<PublisherKind>(it, "publisherKind") }
                ?: PublisherKind.USER
            val externalId = request.publisherExternalId?.trim().orEmpty()
            require(platform.isNotBlank()) { "发布者平台不能为空" }
            require(externalId.isNotBlank()) { "发布者外部 ID 不能为空" }
            val publisherKey = PublisherKey.of(
                platformId = platform,
                kind = publisherKind,
                externalId = externalId,
            )
            val existedPublisher = PublisherRepository.findByKey(publisherKey)
            if (existedPublisher != null) {
                val cachedPublisherInfo = cachedPublisherCandidate(publisherKey)
                if (cachedPublisherInfo != null) {
                    PublisherRepository.upsertInfo(cachedPublisherInfo)
                } else {
                    UpsertResult(existedPublisher, created = false, updated = false)
                }
            } else {
                val publisherInfo = cachedPublisherCandidate(publisherKey)
                    ?: if (publisherLookupMode == PUBLISHER_LOOKUP_MODE_PLACEHOLDER) {
                        warnings += "未查询平台资料，已使用发布者 ID 创建占位发布者：$platform:$externalId"
                        placeholderPublisherInfo(publisherKey)
                    } else {
                        fetchPublisherInfo(platform, externalId).first.normalized().copy(key = publisherKey)
                    }
                PublisherRepository.upsertInfo(publisherInfo)
            }
        }
        val subscriberUpsert = if (request.subscriberId != null) {
            val subscriber = SubscriberRepository.findById(request.subscriberId)
                ?: throw NoSuchElementException("消息目标不存在：subscriberId=${request.subscriberId}")
            UpsertResult(subscriber, created = false, updated = false)
        } else {
            val subscriberPlatform = request.subscriberPlatform?.trim()?.lowercase().orEmpty().also {
                require(it.isNotBlank()) { "消息目标平台不能为空" }
            }
            val subscriberExternalId = request.subscriberTargetId?.trim().orEmpty().also {
                require(it.isNotBlank()) { "消息目标 ID 不能为空" }
            }
            val subscriberAddress = TargetAddress.of(
                platformId = subscriberPlatform,
                kind = parseEnum<TargetKind>(request.targetKind.orEmpty(), "targetKind"),
                externalId = subscriberExternalId,
                scopeId = request.subscriberScopeId,
                threadId = request.subscriberThreadId,
                accountId = request.subscriberAccountId,
            )
            val existedSubscriber = SubscriberRepository.findByAddress(subscriberAddress)
            if (existedSubscriber != null) {
                SubscriberRepository.upsert(
                    address = subscriberAddress,
                    name = existedSubscriber.name,
                    avatar = existedSubscriber.avatar,
                )
            } else {
                val targetProfile = cachedMessageTargetCandidate(subscriberAddress)
                    ?: resolveSubscriberTarget(subscriberAddress)
                SubscriberRepository.upsert(
                    address = subscriberAddress,
                    name = targetProfile?.name?.trim()?.takeIf { it.isNotBlank() }
                        ?: subscriberAddress.externalId,
                    avatar = targetProfile?.avatar,
                )
            }
        }
        parseLinkParseTriggerModeOrNull(request.subscriberLinkParseTriggerMode)?.let { mode ->
            LinkParseTargetConfigRepository.upsert(
                address = subscriberUpsert.value.address,
                triggerMode = mode,
                updatedBy = "web-admin",
            )
        }
        val publisherPlatform = publisherUpsert.value.key.platformId.value
        val publisherExternalId = publisherUpsert.value.key.externalId
        val existingSubscription = SubscriptionRepository.findBySubscriberAndPublisher(
            subscriberId = subscriberUpsert.value.id,
            publisherId = publisherUpsert.value.id,
        )
        requireSubscriptionPolicyAllowed(
            subscriber = subscriberUpsert.value,
            publisher = publisherUpsert.value,
            policy = request.policy,
            liveSupport = publisherPlatformLiveSupport(publisherPlatform),
            previousPolicy = existingSubscription?.policy,
        )
        replacementFilterConditions?.forEach(DynamicFilterRuleRepository::validateCondition)
        val previousSubscriptionCount = SubscriptionRepository.countByPublisherId(publisherUpsert.value.id)
        val subscriptionResult = SubscriptionRepository.subscribe(
            subscriberId = subscriberUpsert.value.id,
            publisherId = publisherUpsert.value.id,
            policy = request.policy,
        )
        applySubscriptionMutation(subscriptionResult)
        if (subscriptionResult.changed) {
            publisherThemeInitializer.initializeAfterFirstSubscription(
                publisher = publisherUpsert.value,
                previousSubscriptionCount = previousSubscriptionCount,
            )
        }
        if (!subscriptionResult.changed) {
            val existing = SubscriptionRepository.findBySubscriberAndPublisher(
                subscriberId = subscriberUpsert.value.id,
                publisherId = publisherUpsert.value.id,
            ) ?: throw IllegalStateException("订阅未找到")
            val updated = SubscriptionRepository.updatePolicy(existing.id, request.policy)
            broadcastSubscriptionUpdated(updated, publisherUpsert.value, subscriberUpsert.value)
        }
        val subscription = SubscriptionRepository.findBySubscriberAndPublisher(
            subscriberId = subscriberUpsert.value.id,
            publisherId = publisherUpsert.value.id,
        ) ?: throw IllegalStateException("订阅创建失败")

        if (replacementFilterConditions != null) {
            DynamicFilterRuleRepository.clearBySubscriptionId(subscription.id)
            replacementFilterConditions.forEach { condition ->
                DynamicFilterRuleRepository.addRule(subscription.id, condition)
            }
        }
        val autoFollowOutcome = if (subscriptionResult.changed && configProvider().subscription.autoFollowPublisherOnSubscribe) {
            tryEnsureFollowed(publisherPlatform, publisherExternalId)
        } else {
            AutoFollowOutcome(followed = false)
        }
        val autoFollowed = autoFollowOutcome.followed
        val filterRules = DynamicFilterRuleRepository.findBySubscriptionId(subscription.id)
        logger.info {
            "后台订阅已${if (subscriptionResult.changed) "创建" else "更新"}：subscriptionId=${subscription.id}，publisherId=${publisherUpsert.value.id}，subscriberId=${subscriberUpsert.value.id}，autoFollowed=$autoFollowed"
        }
        return CreateSubscriptionResponse(
            subscription = subscription.toDto(
                mapOf(publisherUpsert.value.id to publisherUpsert.value),
                mapOf(subscriberUpsert.value.id to subscriberUpsert.value),
                filterRules,
            ),
            publisherCreated = publisherUpsert.created,
            publisherUpdated = publisherUpsert.updated,
            subscriberCreated = subscriberUpsert.created,
            subscriberUpdated = subscriberUpsert.updated,
            subscriptionCreated = subscriptionResult.changed,
            autoFollowed = autoFollowed,
            warnings = warnings + listOfNotNull(autoFollowOutcome.warning),
        )
    }

    public fun updateSubscription(id: Int, request: UpdateSubscriptionRequest): SubscriptionDto {
        val subscription = SubscriptionRepository.findById(id) ?: throw NoSuchElementException("未找到订阅：$id")
        val subscriber = SubscriberRepository.findById(subscription.subscriberId)
            ?: throw NoSuchElementException("未找到消息目标：${subscription.subscriberId}")
        val publisher = PublisherRepository.findById(subscription.publisherId)
            ?: throw NoSuchElementException("未找到发布者：${subscription.publisherId}")
        requireSubscriptionPolicyAllowed(
            subscriber = subscriber,
            publisher = publisher,
            policy = request.policy,
            liveSupport = publisherPlatformLiveSupport(publisher.platformId.value),
            previousPolicy = subscription.policy,
        )
        val updated = SubscriptionRepository.updatePolicy(subscription.id, request.policy)
        broadcastSubscriptionUpdated(updated, publisher, subscriber)
        logger.info {
            "后台订阅规则已更新：subscriptionId=${updated.id}，publisherId=${updated.publisherId}，subscriberId=${updated.subscriberId}"
        }
        return updated.toDto(
            publishers = mapOf(publisher.id to publisher),
            subscribers = mapOf(subscriber.id to subscriber),
            filterRules = DynamicFilterRuleRepository.findBySubscriptionId(updated.id),
        )
    }

    public suspend fun deleteSubscription(id: Int): ActionResultResponse {
        val subscription = SubscriptionRepository.findById(id)
            ?: throw NoSuchElementException("未找到订阅：$id")
        val publisher = PublisherRepository.findById(subscription.publisherId)
        val removed = applySubscriptionMutation(
            SubscriptionRepository.unsubscribe(subscription.subscriberId, subscription.publisherId),
        )
        if (removed && publisher != null && configProvider().subscription.unfollowWhenNoSubscribers) {
            if (SubscriptionRepository.countByPublisherId(publisher.id) == 0L) {
                publisherFollowResolver(publisher.platformId.value)?.unfollowPublisher(publisher.externalId)
            }
        }
        if (removed) {
            logger.info {
                "后台订阅已删除：subscriptionId=$id，publisherId=${subscription.publisherId}，subscriberId=${subscription.subscriberId}"
            }
        }
        return ActionResultResponse(removed, if (removed) "订阅已删除" else "订阅未变化")
    }

    public fun filterRules(subscriptionId: Int?): List<DynamicFilterRuleDto> {
        val rules = if (subscriptionId == null) {
            DynamicFilterRuleRepository.findAll()
        } else {
            require(SubscriptionRepository.findById(subscriptionId) != null) {
                "未找到订阅：$subscriptionId"
            }
            DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId)
        }
        return rules.sortedBy { it.id }.map { it.toDto() }
    }

    public fun createFilterRule(subscriptionId: Int, request: CreateFilterRuleRequest): DynamicFilterRuleDto {
        val result = DynamicFilterRuleRepository.addRule(
            subscriptionId = subscriptionId,
            condition = request.condition,
        )
        logger.info {
            "后台过滤规则已${if (result.created) "创建" else "确认"}：ruleId=${result.value.id}，subscriptionId=$subscriptionId"
        }
        return result.value.toDto()
    }

    public fun deleteFilterRule(id: Int): ActionResultResponse {
        val removed = DynamicFilterRuleRepository.removeById(id)
        if (!removed) throw NoSuchElementException("未找到过滤规则：$id")
        logger.info { "后台过滤规则已删除：ruleId=$id" }
        return ActionResultResponse(true, "过滤规则已删除")
    }

    public fun clearFilterRules(subscriptionId: Int): ActionResultResponse {
        require(SubscriptionRepository.findById(subscriptionId) != null) {
            "未找到订阅：$subscriptionId"
        }
        val removed = DynamicFilterRuleRepository.clearBySubscriptionId(subscriptionId)
        if (removed > 0) {
            logger.info { "后台过滤规则已清空：subscriptionId=$subscriptionId，数量=$removed" }
        }
        return ActionResultResponse(removed > 0, "过滤规则已清空：数量=$removed")
    }

    public fun configs(): List<ConfigSummaryDto> {
        return buildList {
            add(
                ConfigSummaryDto(
                    id = MainDynamicConfig.CONFIG_ID,
                    name = "主配置",
                    description = "主项目配置",
                    sourcePath = configService.resolvePath(MainDynamicConfig.CONFIG_ID).toString(),
                )
            )
            addAll(
                configurablePlugins().map { (info, plugin) ->
                    ConfigSummaryDto(
                        id = plugin.configId,
                        name = plugin.configName,
                        description = plugin.configDescription,
                        pluginId = info.descriptor.id,
                        pluginName = info.descriptor.name,
                        pluginState = info.state.name,
                        sourcePath = configService.resolvePath(plugin.configId).toString(),
                    )
                }
            )
        }.sortedWith(compareBy<ConfigSummaryDto> {
            if (it.id == MainDynamicConfig.CONFIG_ID) 0 else 1
        }.thenBy { it.name })
    }

    public fun config(id: String): ConfigDetailDto {
        if (id == MainDynamicConfig.CONFIG_ID) return mainConfigDetail()
        val (info, plugin) = configurablePlugins().firstOrNull { (_, plugin) -> plugin.configId == id }
            ?: throw NoSuchElementException("未找到配置：$id")
        val current = plugin.currentConfig()
        return ConfigDetailDto(
            id = plugin.configId,
            name = plugin.configName,
            description = plugin.configDescription,
            pluginId = info.descriptor.id,
            pluginName = info.descriptor.name,
            pluginState = info.state.name,
            sourcePath = configService.resolvePath(plugin.configId).toString(),
            schema = plugin.configFormSpec,
            values = AdminConfigJson.valuesFor(current, plugin.configFormSpec, maskSecrets = true),
            secretStates = AdminConfigJson.secretStates(current, plugin.configFormSpec),
        )
    }

    public fun configSecret(id: String, path: String): ConfigSecretValueResponse {
        if (id == MainDynamicConfig.CONFIG_ID) {
            return secretValue(path, configProvider(), MainConfigForms.formSpec)
        }
        val (_, plugin) = configurablePlugins().firstOrNull { (_, plugin) -> plugin.configId == id }
            ?: throw NoSuchElementException("未找到配置：$id")
        return secretValue(path, plugin.currentConfig(), plugin.configFormSpec)
    }

    public fun updateConfig(id: String, request: UpdateConfigRequest): UpdateConfigResponse {
        if (id == MainDynamicConfig.CONFIG_ID) return updateMainConfig(request)
        val (info, plugin) = configurablePlugins().firstOrNull { (_, plugin) -> plugin.configId == id }
            ?: throw NoSuchElementException("未找到配置：$id")
        return updatePluginConfig(info, plugin, request)
    }

    private fun secretValue(path: String, current: Any, spec: ConfigFormSpec): ConfigSecretValueResponse {
        val field = spec.fields.firstOrNull { it.path == path }
            ?: throw NoSuchElementException("未找到配置项：$path")
        require(field.secret) { "配置项不是密钥字段：$path" }
        return ConfigSecretValueResponse(
            path = path,
            value = AdminConfigJson.valuesFor(current, spec, maskSecrets = false)[path] ?: JsonNull,
        )
    }

    private fun mainConfigDetail(): ConfigDetailDto {
        val current = configProvider()
        return ConfigDetailDto(
            id = MainDynamicConfig.CONFIG_ID,
            name = "主配置",
            description = "主项目配置",
            sourcePath = configService.resolvePath(MainDynamicConfig.CONFIG_ID).toString(),
            schema = MainConfigForms.formSpec,
            values = AdminConfigJson.valuesFor(current, MainConfigForms.formSpec, maskSecrets = true),
            secretStates = AdminConfigJson.secretStates(current, MainConfigForms.formSpec),
        )
    }

    private fun updateMainConfig(request: UpdateConfigRequest): UpdateConfigResponse {
        val current = configProvider()
        val next = AdminConfigJson.decode(
            values = request.values,
            current = current,
            spec = MainConfigForms.formSpec,
            clazz = MainDynamicConfig::class,
        )
        val result = mainConfigUpdater(next)
        val saved = configProvider()
        if (result.changed) {
            logger.info {
                "后台配置已保存：configId=${MainDynamicConfig.CONFIG_ID}，restartRequired=${result.restartRequired}，restartTargets=${result.restartTargets.ifEmpty { listOf("-") }}"
            }
        }
        return UpdateConfigResponse(
            changed = result.changed,
            restartRequired = result.restartRequired,
            restartTargets = result.restartTargets,
            message = result.message,
            pluginId = null,
            values = AdminConfigJson.valuesFor(saved, MainConfigForms.formSpec, maskSecrets = true),
            secretStates = AdminConfigJson.secretStates(saved, MainConfigForms.formSpec),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun updatePluginConfig(
        info: PluginInfo,
        plugin: ConfigurablePlugin<*>,
        request: UpdateConfigRequest,
    ): UpdateConfigResponse {
        return updatePluginConfigTyped(info, plugin as ConfigurablePlugin<Any>, request)
    }

    private fun <T : Any> updatePluginConfigTyped(
        info: PluginInfo,
        plugin: ConfigurablePlugin<T>,
        request: UpdateConfigRequest,
    ): UpdateConfigResponse {
        val current = plugin.currentConfig()
        val next = AdminConfigJson.decode(
            values = request.values,
            current = current,
            spec = plugin.configFormSpec,
            clazz = plugin.configClass,
        )
        val changed = current != next
        val result = if (changed) {
            try {
                configService.save(plugin.configId, next)
                plugin.applyConfig(next)
            } catch (e: Exception) {
                runCatching { configService.save(plugin.configId, current) }
                runCatching { plugin.applyConfig(current) }
                throw e
            }
        } else {
            ConfigApplyResult(changed = false, message = "${plugin.configName}配置未变化")
        }
        val saved = if (changed) next else current
        if (result.changed) {
            logger.info {
                "后台配置已保存：configId=${plugin.configId}，pluginId=${info.descriptor.id}，restartRequired=${result.restartRequired}，restartTargets=${result.restartTargets.ifEmpty { listOf("-") }}"
            }
        }
        return UpdateConfigResponse(
            changed = result.changed,
            restartRequired = result.restartRequired,
            restartTargets = result.restartTargets,
            message = result.message,
            pluginId = info.descriptor.id,
            values = AdminConfigJson.valuesFor(saved, plugin.configFormSpec, maskSecrets = true),
            secretStates = AdminConfigJson.secretStates(saved, plugin.configFormSpec),
        )
    }

    private fun configurablePlugins(): List<Pair<PluginInfo, ConfigurablePlugin<*>>> {
        return configurablePluginProvider()
            .map { it.info to it.instance }
            .sortedBy { (_, plugin) -> plugin.configId }
    }

    private fun pluginInfoOrThrow(pluginId: String): PluginInfo {
        return pluginProvider().firstOrNull { it.descriptor.id == pluginId }
            ?: throw NoSuchElementException("未找到插件：$pluginId")
    }

    private fun requirePluginCatalogService(): PluginCatalogService {
        return pluginCatalogService ?: throw IllegalStateException("插件目录功能未配置")
    }

    private fun applySubscriptionMutation(result: SubscriptionMutationResult): Boolean {
        result.event?.let { eventBus.broadcast(it) }
        return result.changed
    }

    private fun broadcastSubscriptionUpdated(
        subscription: Subscription,
        publisher: Publisher,
        subscriber: Subscriber,
    ) {
        eventBus.broadcast(
            SubscriptionChangedEvent(
                changeType = SubscriptionChangeType.UPDATED,
                subscription = subscription,
                publisher = publisher,
                subscriber = subscriber,
                changedAtEpochSeconds = subscription.updatedAtEpochSeconds,
            )
        )
    }

    private suspend fun tryEnsureFollowed(
        platform: String,
        externalId: String,
    ): AutoFollowOutcome {
        val plugin = publisherFollowResolver(platform)
            ?: return AutoFollowOutcome(
                followed = false,
                warning = "未找到发布者关注插件，已跳过自动关注：$platform",
            )
        return ensureFollowed(plugin, platform, externalId)
    }

    private suspend fun ensureFollowed(
        plugin: PublisherFollowPlugin,
        platform: String,
        externalId: String,
    ): AutoFollowOutcome {
        val result = plugin.followPublisher(externalId)
        return when (result.status) {
            FollowActionStatus.DONE -> AutoFollowOutcome(followed = true)
            FollowActionStatus.NOOP -> AutoFollowOutcome(followed = false)
            FollowActionStatus.FAILED -> AutoFollowOutcome(
                followed = false,
                warning = result.message ?: "关注发布者失败，已跳过自动关注：$platform",
            )
            FollowActionStatus.UNSUPPORTED -> AutoFollowOutcome(
                followed = false,
                warning = "平台不支持关注操作，已跳过自动关注：$platform",
            )
        }
    }

    private suspend fun fetchPublisherInfo(platform: String, externalId: String): Pair<PublisherInfo, PluginInfo> {
        val handle = publisherLookupProvider()
            .firstOrNull { it.instance.platformId.value.equals(platform, ignoreCase = true) }
        val pluginInfo = handle?.info ?: fallbackPublisherPluginInfo(platform)
        cachedPublisherCandidate(PublisherKey.of(platform, PublisherKind.USER, externalId))?.let { cached ->
            return cached to pluginInfo
        }
        val plugin = handle?.instance ?: publisherLookupResolver(platform)
            ?: throw NoSuchElementException("未找到发布者查询插件：$platform")
        val publisherInfo = plugin.fetchPublisherInfo(externalId)?.normalized()
            ?: throw NoSuchElementException("未找到发布者：$platform:$externalId")
        rememberPublisherCandidate(publisherInfo)
        return publisherInfo to pluginInfo
    }

    private fun rememberPublisherCandidate(publisherInfo: PublisherInfo) {
        val key = publisherInfo.key.stableValue()
        synchronized(publisherCandidateCacheLock) {
            prunePublisherCandidateCacheLocked()
            publisherCandidateCache.remove(key)
            publisherCandidateCache[key] = CachedPublisherCandidate(
                publisherInfo = publisherInfo,
                cachedAtEpochMillis = System.currentTimeMillis(),
            )
            trimPublisherCandidateCacheLocked()
        }
    }

    private fun cachedPublisherCandidate(key: PublisherKey): PublisherInfo? {
        val cacheKey = key.stableValue()
        return synchronized(publisherCandidateCacheLock) {
            val cached = publisherCandidateCache[cacheKey] ?: return@synchronized null
            if (cached.isExpired()) {
                publisherCandidateCache.remove(cacheKey)
                return@synchronized null
            }
            cached.publisherInfo.normalized().copy(key = key)
        }
    }

    private fun prunePublisherCandidateCacheLocked() {
        val now = System.currentTimeMillis()
        publisherCandidateCache.entries.removeIf { (_, value) ->
            now - value.cachedAtEpochMillis > ADMIN_CANDIDATE_CACHE_TTL_MS
        }
    }

    private fun trimPublisherCandidateCacheLocked() {
        while (publisherCandidateCache.size > ADMIN_PUBLISHER_CANDIDATE_CACHE_MAX_SIZE) {
            val oldestKey = publisherCandidateCache.keys.firstOrNull() ?: return
            publisherCandidateCache.remove(oldestKey)
        }
    }

    private fun fallbackPublisherPluginInfo(platform: String): PluginInfo {
        return PluginInfo(
            descriptor = PluginDescriptor(
                id = platform,
                name = platform,
                version = "",
                mainClass = "",
            ),
            capabilities = setOf(PluginCapability.PUBLISHER_LOOKUP),
            state = PluginState.ACTIVE,
            sourceJarPath = "",
        )
    }

    private suspend fun resolveSubscriberTarget(address: TargetAddress): MessageTargetCandidate? {
        for (handle in messageSinkProvider()) {
            val sink = handle.instance
            if (!sink.supportsTarget(address)) continue
            val candidate = sink.resolveMessageTarget(address) ?: continue
            if (candidate.address.stableValue() == address.stableValue()) {
                rememberMessageTargetCandidate(candidate)
                return candidate
            }
        }
        return null
    }

    private fun rememberMessageTargetCandidate(candidate: MessageTargetCandidate) {
        rememberMessageTargetCandidates(listOf(candidate))
    }

    private fun rememberMessageTargetCandidates(candidates: Collection<MessageTargetCandidate>) {
        if (candidates.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(subscriberTargetCandidateCacheLock) {
            pruneMessageTargetCandidateCacheLocked(now)
            candidates.forEach { candidate ->
                val key = candidate.address.stableValue()
                subscriberTargetCandidateCache.remove(key)
                subscriberTargetCandidateCache[key] = CachedMessageTargetCandidate(
                    candidate = candidate,
                    cachedAtEpochMillis = now,
                )
            }
            trimMessageTargetCandidateCacheLocked()
        }
    }

    private fun cachedMessageTargetCandidate(address: TargetAddress): MessageTargetCandidate? {
        val cacheKey = address.stableValue()
        return synchronized(subscriberTargetCandidateCacheLock) {
            val cached = subscriberTargetCandidateCache[cacheKey] ?: return@synchronized null
            if (cached.isExpired()) {
                subscriberTargetCandidateCache.remove(cacheKey)
                return@synchronized null
            }
            cached.candidate
        }
    }

    private fun pruneMessageTargetCandidateCacheLocked(now: Long = System.currentTimeMillis()) {
        subscriberTargetCandidateCache.entries.removeIf { (_, value) ->
            now - value.cachedAtEpochMillis > ADMIN_CANDIDATE_CACHE_TTL_MS
        }
    }

    private fun trimMessageTargetCandidateCacheLocked() {
        while (subscriberTargetCandidateCache.size > ADMIN_TARGET_CANDIDATE_CACHE_MAX_SIZE) {
            val oldestKey = subscriberTargetCandidateCache.keys.firstOrNull() ?: return
            subscriberTargetCandidateCache.remove(oldestKey)
        }
    }

    private fun publisherPlatformLiveSupport(platformId: String): Boolean? {
        val normalized = PlatformId.of(platformId)
        val providerPlugin = publisherLookupProvider()
            .firstOrNull { it.instance.platformId == normalized }
            ?.instance
        val plugin = providerPlugin ?: publisherLookupResolver(platformId)
            ?.takeIf { it.platformId == normalized }
            ?: return null
        return plugin.platformDescriptor.capabilities.contains(PlatformCapability.LIVE_SOURCE)
    }

    private fun currentTaskDtos(): List<TaskDto> {
        return buildList {
            mainTaskScheduler?.snapshots().orEmpty().forEach { snapshot ->
                add(snapshot.toTaskDto(TASK_OWNER_MAIN, TASK_OWNER_MAIN_ID, "主项目"))
            }
            pluginTaskProvider().forEach { pluginTask ->
                add(
                    pluginTask.task.toTaskDto(
                        ownerType = TASK_OWNER_PLUGIN,
                        ownerId = pluginTask.pluginId,
                        ownerName = pluginTask.pluginName,
                        pluginVersion = pluginTask.pluginVersion,
                        pluginState = pluginTask.pluginState.name,
                    )
                )
            }
        }.sortedWith(
            compareBy<TaskDto> { it.ownerType }
                .thenBy { it.ownerId }
                .thenBy { it.id }
        )
    }

    private suspend fun operateTask(
        ownerType: String,
        ownerId: String,
        taskId: String,
        operationLabel: String,
        operation: suspend (normalizedType: String, normalizedOwnerId: String, normalizedTaskId: String) -> Boolean,
    ): TaskOperationResponse {
        val before = taskDtoOrThrow(ownerType, ownerId, taskId)
        val changed = operation(before.ownerType, before.ownerId, before.id)
        val after = taskDtoOrNull(before.ownerType, before.ownerId, before.id) ?: before
        val taskName = before.name.ifBlank { before.id }
        val message = if (changed) {
            "任务已${operationLabel}：${before.ownerName} / $taskName"
        } else {
            "任务状态未变化：${before.ownerName} / $taskName"
        }
        return TaskOperationResponse(
            changed = changed,
            ownerType = before.ownerType,
            ownerId = before.ownerId,
            taskId = before.id,
            status = after.status,
            message = message,
        )
    }

    private fun taskDtoOrThrow(ownerType: String, ownerId: String, taskId: String): TaskDto {
        return taskDtoOrNull(ownerType, ownerId, taskId)
            ?: throw NoSuchElementException("未找到任务：${normalizeTaskOwnerType(ownerType)}:${ownerId.trim()}:${taskId.trim()}")
    }

    private fun taskDtoOrNull(ownerType: String, ownerId: String, taskId: String): TaskDto? {
        val normalizedType = normalizeTaskOwnerType(ownerType)
        val normalizedOwnerId = normalizeTaskOwnerId(normalizedType, ownerId)
        val normalizedTaskId = taskId.trim()
        require(normalizedTaskId.isNotBlank()) { "任务 ID 不能为空" }
        return when (normalizedType) {
            TASK_OWNER_MAIN -> mainTaskScheduler
                ?.snapshot(normalizedTaskId)
                ?.toTaskDto(TASK_OWNER_MAIN, TASK_OWNER_MAIN_ID, "主项目")
            TASK_OWNER_PLUGIN -> pluginTaskResolver(normalizedOwnerId, normalizedTaskId)
                ?.let { pluginTask ->
                    pluginTask.task.toTaskDto(
                        ownerType = TASK_OWNER_PLUGIN,
                        ownerId = pluginTask.pluginId,
                        ownerName = pluginTask.pluginName,
                        pluginVersion = pluginTask.pluginVersion,
                        pluginState = pluginTask.pluginState.name,
                    )
                }
            else -> null
        }
    }
}

public class PluginReloadFailedException(
    public val response: PluginReloadResponse,
) : IllegalStateException(response.error ?: response.message)

private fun TaskSnapshot.toTaskDto(
    ownerType: String,
    ownerId: String,
    ownerName: String,
    pluginVersion: String? = null,
    pluginState: String? = null,
): TaskDto {
    val schedule = schedule
    val scheduleType = schedule.typeText()
    val statusName = status.name
    return TaskDto(
        key = listOf(ownerType, ownerId, id).joinToString(":"),
        ownerType = ownerType,
        ownerId = ownerId,
        ownerName = ownerName,
        pluginVersion = pluginVersion,
        pluginState = pluginState,
        id = id,
        name = name.ifBlank { id },
        description = description,
        status = statusName,
        scheduleType = scheduleType,
        scheduleText = schedule.describe(),
        runImmediately = (schedule as? TaskSchedule.FixedDelay)?.runImmediately,
        retryBackoffMillis = retryBackoffMillis,
        nextRunAtMillis = nextRunAtMillis,
        lastRunAtMillis = lastRunAtMillis,
        lastSuccessAtMillis = lastSuccessAtMillis,
        runCount = runCount,
        lastErrorSummary = lastErrorSummary,
        canStart = status != TaskStatus.RUNNING,
        canStop = status == TaskStatus.RUNNING,
        canRestart = true,
    )
}

private fun TaskSchedule?.typeText(): String {
    return when (this) {
        null -> "UNKNOWN"
        TaskSchedule.Once -> "ONCE"
        is TaskSchedule.FixedDelay -> "FIXED_DELAY"
        is TaskSchedule.Cron -> "CRON"
    }
}

private fun TaskSchedule?.describe(): String {
    return when (this) {
        null -> "未知调度"
        TaskSchedule.Once -> "一次性任务"
        is TaskSchedule.FixedDelay -> {
            val suffix = if (runImmediately) "启动后立即执行" else "启动后先等待"
            "固定间隔 ${formatDurationMillis(delay.inWholeMilliseconds)}，$suffix"
        }
        is TaskSchedule.Cron -> "Cron $expression，时区 ${zone.id}"
    }
}

private fun normalizeTaskOwnerType(value: String): String {
    return when (value.trim().uppercase()) {
        TASK_OWNER_MAIN -> TASK_OWNER_MAIN
        TASK_OWNER_PLUGIN -> TASK_OWNER_PLUGIN
        else -> throw IllegalArgumentException("任务来源类型无效：$value")
    }
}

private fun normalizeTaskOwnerId(ownerType: String, ownerId: String): String {
    val normalized = ownerId.trim()
    return when (ownerType) {
        TASK_OWNER_MAIN -> TASK_OWNER_MAIN_ID
        TASK_OWNER_PLUGIN -> {
            require(normalized.isNotBlank()) { "插件 ID 不能为空" }
            normalized
        }
        else -> normalized
    }
}

private fun formatDurationMillis(value: Long): String {
    val millis = value.coerceAtLeast(0)
    if (millis < 1_000) return "${millis}ms"
    val seconds = millis / 1_000.0
    if (seconds < 60) return "${trimDecimal(seconds)} 秒"
    val minutes = seconds / 60.0
    if (minutes < 60) return "${trimDecimal(minutes)} 分钟"
    val hours = minutes / 60.0
    return "${trimDecimal(hours)} 小时"
}

private fun trimDecimal(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}

private fun PluginInfo.toDto(catalog: PluginCatalogEntryDto? = null): PluginDto = PluginDto(
    id = descriptor.id,
    name = descriptor.name,
    version = descriptor.version,
    capabilities = capabilities.map { it.name }.sorted(),
    state = state.name,
    error = error?.message ?: error?.javaClass?.name,
    sourceJarPath = sourceJarPath,
    loadTime = loadTime,
    catalogVersion = catalog?.version,
    updateAvailable = catalog?.updateAvailable ?: false,
    catalogStatus = catalog?.catalogStatus,
)

private fun PluginInfo.errorText(): String? = error?.message ?: error?.javaClass?.name

private fun PluginReloadResult.toResponse(): PluginReloadResponse = PluginReloadResponse(
    changed = changed,
    success = success,
    pluginId = pluginId,
    pluginState = pluginState?.name,
    message = message,
    error = error,
)

private fun top.colter.dynamic.core.plugin.PublisherLoginAccount.toDto(): LoginAccountDto = LoginAccountDto(
    userId = userId,
    name = name,
    avatarUri = avatar?.uri,
)

private fun Publisher.toDto(
    subscriptionCount: Long = 0,
    liveSubscriptionCount: Long = 0,
    liveStatuses: List<PublisherLiveStatus> = emptyList(),
    liveRecords: List<PublisherLiveRecord> = emptyList(),
    cursors: List<SourceCursor> = emptyList(),
    drawTheme: PublisherDrawTheme? = null,
): PublisherDto = PublisherDto(
    id = id,
    platformId = platformId.value,
    kind = kind.name,
    externalId = externalId,
    name = name,
    avatarBadgeKey = avatarBadgeKey,
    state = state.name,
    avatarUri = avatar.uri,
    pendantUri = pendant?.uri,
    bannerUri = banner?.uri,
    createTime = createTime,
    createUser = createUser,
    subscriptionCount = subscriptionCount,
    liveSubscriptionCount = liveSubscriptionCount,
    drawTheme = drawTheme?.palette?.toDto(),
    liveStatuses = liveStatuses.sortedBy { it.roomId }.map { it.toDto() },
    liveRecords = liveRecords.sortedByDescending { it.startedAtEpochSeconds }.map { it.toDto() },
    cursors = cursors.sortedWith(compareBy<SourceCursor> { it.sourceKey }.thenBy { it.eventType.value }).map { it.toDto() },
)

private fun PublisherInfo.toCandidateDto(info: PluginInfo): PublisherCandidateDto = PublisherCandidateDto(
    platformId = platformId.value,
    kind = kind.name,
    externalId = externalId,
    name = name,
    avatarUri = avatar.uri,
    bannerUri = banner?.uri,
    sourcePluginId = info.descriptor.id,
    sourcePluginName = info.descriptor.name,
)

private fun DrawThemePalette.toDto(): PublisherDrawThemeDto = PublisherDrawThemeDto(
    mode = mode.name,
    backgroundColors = backgroundColors,
    primaryColor = primaryColor,
    readableAccentColor = readableAccentColor,
    onPrimaryColor = onPrimaryColor,
    qrPointColor = qrPointColor,
    cardColor = cardColor,
    borderColor = borderColor,
    linkColor = linkColor,
    textColor = textColor,
    secondaryTextColor = secondaryTextColor,
    mutedTextColor = mutedTextColor,
)

private fun Subscriber.toDto(
    subscriptionCount: Long = 0,
    linkParseTriggerMode: LinkParseTriggerMode? = null,
    fallbackTriggerMode: LinkParseTriggerMode = LinkParseTriggerMode.MENTION_ONLY,
): SubscriberDto = SubscriberDto(
    id = id,
    platformId = platformId.value,
    targetKind = kind.name,
    externalId = externalId,
    scopeId = address.scopeId,
    threadId = address.threadId,
    accountId = address.accountId,
    name = name,
    avatarUri = avatar?.uri,
    state = state.name,
    createTime = createTime,
    createUser = createUser,
    subscriptionCount = subscriptionCount,
    linkParseTriggerMode = linkParseTriggerMode?.name,
    effectiveLinkParseTriggerMode = (linkParseTriggerMode ?: fallbackTriggerMode).name,
    linkParseConfigSource = if (linkParseTriggerMode == null) "FALLBACK" else "CUSTOM",
)

private fun Collection<MessageTargetWithPlugin>.toSubscriberTargetDto(): SubscriberTargetDto {
    val first = first()
    val candidate = first.candidate
    val sources = flatMap { it.candidate.sources }
        .distinctBy { it.routeId }
        .sortedWith(compareBy<MessageTargetSource> { it.transportId }.thenBy { it.accountId })
    return SubscriberTargetDto(
        platformId = candidate.address.platformId.value,
        targetKind = candidate.address.kind.name,
        externalId = candidate.address.externalId,
        scopeId = candidate.address.scopeId,
        threadId = candidate.address.threadId,
        accountId = candidate.address.accountId,
        name = candidate.name,
        avatarUri = candidate.avatar?.uri,
        sourcePluginId = first.info.descriptor.id,
        sourcePluginName = first.info.descriptor.name,
        sourceCount = sources.size,
        sources = sources.map { it.toDto() },
    )
}

private fun MessageTargetSource.toDto(): SubscriberTargetSourceDto = SubscriberTargetSourceDto(
    routeId = routeId,
    transportId = transportId,
    transportName = transportName,
    accountId = accountId,
    accountName = accountName,
    state = state.name,
)

private fun Subscription.toDto(
    publishers: Map<Int, Publisher>,
    subscribers: Map<Int, Subscriber>,
    filterRules: List<DynamicFilterRule> = emptyList(),
): SubscriptionDto = SubscriptionDto(
    id = id,
    subscriberId = subscriberId,
    publisherId = publisherId,
    createdAtEpochSeconds = createdAtEpochSeconds,
    updatedAtEpochSeconds = updatedAtEpochSeconds,
    state = state.name,
    policy = policy,
    subscriber = subscribers[subscriberId]?.toDto(),
    publisher = publishers[publisherId]?.toDto(),
    filterRuleCount = filterRules.size,
    filterRules = filterRules.sortedBy { it.id }.map { it.toDto() },
)

private fun DynamicFilterRule.toDto(): DynamicFilterRuleDto = DynamicFilterRuleDto(
    id = id,
    subscriptionId = subscriptionId,
    condition = condition,
    createdAtEpochSeconds = createdAtEpochSeconds,
)

private fun SourceCursor.toDto(): SourceCursorDto = SourceCursorDto(
    publisherId = publisherId,
    sourceKey = sourceKey,
    eventType = eventType.value,
    lastSeenUpdateKey = lastSeenUpdateKey,
    lastSeenAtEpochSeconds = lastSeenAtEpochSeconds,
    recentUpdateKeys = recentUpdateKeys,
)

private fun PublisherLiveStatus.toDto(): PublisherLiveStatusDto = PublisherLiveStatusDto(
    publisherId = publisherId,
    roomId = roomId,
    status = status.name,
    title = title,
    coverUri = cover?.uri,
    area = area,
    startedAtEpochSeconds = startedAtEpochSeconds,
    lastObservedAtEpochSeconds = lastObservedAtEpochSeconds,
)

private fun PublisherLiveRecord.toDto(): PublisherLiveRecordDto = PublisherLiveRecordDto(
    id = id,
    publisherId = publisherId,
    roomId = roomId,
    title = title,
    coverUri = coverUri,
    area = area,
    startedAtEpochSeconds = startedAtEpochSeconds,
    endedAtEpochSeconds = endedAtEpochSeconds,
    durationSeconds = durationSeconds,
    lastObservedAtEpochSeconds = lastObservedAtEpochSeconds,
)

private fun MessageDelivery.toDto(): MessageDeliveryDto = MessageDeliveryDto(
    id = id,
    messageId = messageId,
    sourceUpdateKey = sourceUpdateKey?.stableValue(),
    renderVariant = renderVariant,
    platformId = target.platformId.value,
    targetKind = target.kind.name,
    targetId = target.externalId,
    targetScopeId = target.scopeId,
    targetThreadId = target.threadId,
    targetAccountId = target.accountId,
    targetKey = target.stableValue(),
    status = status.name,
    attempts = attempts,
    sinkMessageId = sinkMessageId,
    sinkRouteId = sinkRouteId,
    sinkAccountId = sinkAccountId,
    lastError = lastError,
    nextAttemptAtEpochSeconds = nextAttemptAtEpochSeconds,
    lockedUntilEpochSeconds = lockedUntilEpochSeconds,
    createdAtEpochSeconds = createdAtEpochSeconds,
    updatedAtEpochSeconds = updatedAtEpochSeconds,
)

private fun Message.toJsonElement() = coreJson.encodeToJsonElement(Message.serializer(), this)

private fun MessageDelivery.toForwardDto(newDelivery: Boolean): MessageForwardDeliveryDto = MessageForwardDeliveryDto(
    deliveryId = id,
    targetKey = target.stableValue(),
    platformId = target.platformId.value,
    targetKind = target.kind.name,
    targetId = target.externalId,
    accountId = target.accountId,
    status = status.name,
    newDelivery = newDelivery,
)

private fun AdminLogRecord.toDto(): AdminLogEntryDto = AdminLogEntryDto(
    seq = seq,
    timestampEpochMillis = timestampEpochMillis,
    level = level,
    loggerName = loggerName,
    threadName = threadName,
    message = message,
    throwable = throwable,
)

private fun Map<String, Int>.toStateCounts(total: Long): List<StateCountDto> {
    val counts = map { (state, count) -> StateCountDto(state, count.toLong()) }
        .sortedBy { it.state }
    return listOf(StateCountDto("TOTAL", total)) + counts
}

private data class CachedLoginState(
    val result: PublisherLoginResult,
    val checkedAtEpochMillis: Long,
)

private data class CachedPublisherCandidate(
    val publisherInfo: PublisherInfo,
    val cachedAtEpochMillis: Long,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return now - cachedAtEpochMillis > ADMIN_CANDIDATE_CACHE_TTL_MS
    }
}

private data class CachedMessageTargetCandidate(
    val candidate: MessageTargetCandidate,
    val cachedAtEpochMillis: Long,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return now - cachedAtEpochMillis > ADMIN_CANDIDATE_CACHE_TTL_MS
    }
}

private data class AutoFollowOutcome(
    val followed: Boolean,
    val warning: String? = null,
)

private data class SinkPlatformEntry(
    val platformId: String,
    val pluginId: String,
    val pluginName: String,
    val pluginState: String,
    val transportId: String,
    val supportedTypes: List<String>,
)

private data class MessageTargetWithPlugin(
    val candidate: MessageTargetCandidate,
    val info: PluginInfo,
)

private fun UpsertResult<*>.operationLabel(): String {
    return when {
        created -> "创建"
        updated -> "更新"
        else -> "确认"
    }
}

private inline fun <reified T : Enum<T>> parseEnum(value: String, fieldName: String): T {
    val normalized = value.trim()
    return enumValues<T>().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        ?: throw IllegalArgumentException(
            "$fieldName 无效：$value，可选值=${enumValues<T>().joinToString("|") { it.name }}",
        )
}

private fun parseLinkParseTriggerModeOrNull(value: String?): LinkParseTriggerMode? {
    val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (normalized.equals("INHERIT", ignoreCase = true)) return null
    return parseEnum<LinkParseTriggerMode>(normalized, "linkParseTriggerMode")
}

private fun SubscriptionExportItem.toCreateSubscriptionRequest(): CreateSubscriptionRequest {
    val publisherKey = importPublisherKey()
    val targetAddress = importTargetAddress()
    return CreateSubscriptionRequest(
        subscriberPlatform = targetAddress.platformId.value,
        targetKind = targetAddress.kind.name,
        subscriberTargetId = targetAddress.externalId,
        subscriberScopeId = targetAddress.scopeId,
        subscriberThreadId = targetAddress.threadId,
        subscriberAccountId = targetAddress.accountId,
        subscriberLinkParseTriggerMode = linkParseTriggerMode,
        publisherPlatform = publisherKey.platformId.value,
        publisherKind = publisherKey.kind.name,
        publisherExternalId = publisherKey.externalId,
        policy = policy,
        publisherLookupMode = publisherLookupMode,
    )
}

private fun parsePublisherLookupMode(value: String?): String {
    val normalized = value?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        ?: PUBLISHER_LOOKUP_MODE_VERIFY
    return when (normalized) {
        PUBLISHER_LOOKUP_MODE_VERIFY -> PUBLISHER_LOOKUP_MODE_VERIFY
        PUBLISHER_LOOKUP_MODE_PLACEHOLDER, "SKIP", "LOCAL_ONLY" -> PUBLISHER_LOOKUP_MODE_PLACEHOLDER
        else -> throw IllegalArgumentException(
            "publisherLookupMode 无效：$value，可选值：$PUBLISHER_LOOKUP_MODE_VERIFY|$PUBLISHER_LOOKUP_MODE_PLACEHOLDER",
        )
    }
}

private fun placeholderPublisherInfo(key: PublisherKey): PublisherInfo {
    return PublisherInfo(
        key = key,
        name = key.externalId,
        avatar = MediaRef("", MediaKind.AVATAR),
    )
}

private fun Publisher.isPlaceholderPublisher(): Boolean {
    return name == externalId &&
        avatar.uri.isBlank() &&
        avatarBadgeKey == null &&
        banner == null &&
        pendant == null
}

private fun SubscriptionExportItem.importPublisherKey(): PublisherKey {
    return PublisherKey.of(
        platformId = publisher.platformId,
        kind = publisher.kind
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { parseEnum<PublisherKind>(it, "publisher.kind") }
            ?: PublisherKind.USER,
        externalId = publisher.externalId,
    )
}

private fun SubscriptionExportItem.importTargetAddress(): TargetAddress {
    return TargetAddress.of(
        platformId = normalizeImportedTargetPlatformId(target.platformId),
        kind = parseEnum<TargetKind>(target.targetKind, "target.targetKind"),
        externalId = target.externalId,
        scopeId = target.scopeId,
        threadId = target.threadId,
        accountId = target.accountId,
    )
}

private fun SubscriptionExportItem.publisherKeyText(): String {
    return listOf(
        publisher.platformId.trim().lowercase(),
        publisher.kind.trim().uppercase().ifBlank { PublisherKind.USER.name },
        publisher.externalId.trim(),
    ).joinToString(":")
}

private fun SubscriptionExportItem.targetKeyText(): String {
    return listOf(
        normalizeImportedTargetPlatformId(target.platformId),
        target.targetKind.trim().uppercase(),
        target.externalId.trim(),
        target.scopeId?.trim().orEmpty(),
        target.threadId?.trim().orEmpty(),
        target.accountId?.trim().orEmpty(),
    ).joinToString(":")
}

private fun SubscriptionExportItem.relationKeyOrFallback(): String {
    return listOf(
        publisherKeyText(),
        normalizeImportedTargetPlatformId(target.platformId),
        target.targetKind.trim().uppercase(),
        target.externalId.trim(),
        target.scopeId?.trim().orEmpty(),
        target.threadId?.trim().orEmpty(),
    ).joinToString(separator = "\u001F")
}

private fun normalizeImportedTargetPlatformId(platformId: String): String {
    val normalized = platformId.trim().lowercase()
    // 临时兼容旧数据库/旧导出文件：消息目标平台曾使用 onebot，当前统一为 qq。
    return if (normalized == "onebot") "qq" else normalized
}

private fun requireSubscriptionPolicyAllowed(
    subscriber: Subscriber,
    publisher: Publisher,
    policy: SubscriptionPolicy,
    liveSupport: Boolean?,
    previousPolicy: SubscriptionPolicy? = null,
) {
    require(policy.enabledEvents.isNotEmpty()) {
        "订阅规则至少需要启用一个事件"
    }
    val liveEvents = policy.enabledEvents.filter { it.isLiveEvent() }
    when (liveSupport) {
        true -> Unit
        false -> require(liveEvents.isEmpty()) {
            val events = liveEvents.joinToString("、") { it.displayName() }
            "发布者平台不支持${events}订阅：${publisher.platformId.value}"
        }
        null -> {
            val previousLiveEvents = previousPolicy?.enabledEvents.orEmpty().filter { it.isLiveEvent() }.toSet()
            val newLiveEvents = liveEvents.filterNot { it in previousLiveEvents }
            require(newLiveEvents.isEmpty()) {
                val events = newLiveEvents.joinToString("、") { it.displayName() }
                "发布者平台能力暂不可用，不能新增${events}订阅：${publisher.platformId.value}"
            }
        }
    }
    require(policy.mentionAllEvents.all { it in policy.enabledEvents }) {
        "@全体事件必须先启用接收"
    }
    if (policy.mentionAllEvents.isEmpty()) return
    require(subscriber.kind == TargetKind.GROUP) {
        "只有 GROUP 消息目标可以启用 @全体"
    }
}

private fun SubscriptionEventKind.isLiveEvent(): Boolean {
    return this == SubscriptionEventKind.LIVE_STARTED || this == SubscriptionEventKind.LIVE_ENDED
}

private fun SubscriptionEventKind.displayName(): String {
    return when (this) {
        SubscriptionEventKind.DYNAMIC -> "动态"
        SubscriptionEventKind.LIVE_STARTED -> "开播"
        SubscriptionEventKind.LIVE_ENDED -> "下播"
    }
}

private fun PublisherInfo.normalized(): PublisherInfo = copy(
    key = PublisherKey.of(platformId.value, kind, externalId),
)

private const val LOGIN_STATE_CACHE_TTL_MS: Long = 15_000
private const val LOGIN_STATE_TIMEOUT_MS: Long = 5_000
private const val ADMIN_CANDIDATE_CACHE_TTL_MS: Long = 5 * 60 * 1_000
private const val ADMIN_PUBLISHER_CANDIDATE_CACHE_MAX_SIZE: Int = 256
private const val ADMIN_TARGET_CANDIDATE_CACHE_MAX_SIZE: Int = 1_000
private const val PUBLISHER_LOOKUP_MODE_VERIFY: String = "VERIFY"
private const val PUBLISHER_LOOKUP_MODE_PLACEHOLDER: String = "PLACEHOLDER"
private const val TASK_OWNER_MAIN: String = "MAIN"
private const val TASK_OWNER_PLUGIN: String = "PLUGIN"
private const val TASK_OWNER_MAIN_ID: String = "main"
