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
import top.colter.dynamic.core.data.MessageDelivery
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.MessageTargetSource
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginManager
import top.colter.dynamic.plugin.PluginReloadResult
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PluginDescriptor
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
    private val pluginReloader: (String) -> PluginReloadResult = {
        throw IllegalStateException("插件重载功能未配置")
    },
    private val pluginStarter: (String) -> Unit = {
        throw IllegalStateException("插件启动功能未配置")
    },
    private val pluginStopper: (String) -> Unit = {
        throw IllegalStateException("插件停止功能未配置")
    },
    private val pluginCatalogService: PluginCatalogService? = null,
    private val startedAtEpochMillis: Long = System.currentTimeMillis(),
    private val databasePathProvider: () -> String? = { PersistenceManager.currentPath() },
    private val publisherDrawThemeService: PublisherDrawThemeService = PublisherDrawThemeService(),
    private val publisherThemeInitializer: PublisherThemeInitializer = DefaultPublisherThemeInitializer(configProvider = configProvider),
) {
    private val loginStateCache: ConcurrentHashMap<String, CachedLoginState> = ConcurrentHashMap()

    public constructor(
        pluginManager: PluginManager,
        configProvider: () -> MainDynamicConfig,
        mainConfigUpdater: (MainDynamicConfig) -> ConfigApplyResult = {
            throw IllegalStateException("主配置编辑未初始化")
        },
        configService: ConfigService = YamlConfigService(),
        commandRegistry: CommandRegistry = CommandRegistry(),
        eventBus: EventBus = EventBus(),
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
        pluginReloader = pluginManager::reloadPlugin,
        pluginStarter = pluginManager::startPlugin,
        pluginStopper = pluginManager::stopPlugin,
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
        val liveStatuses = PublisherLiveStatusRepository.findAll().groupBy { it.publisherId }
        val cursors = SourceCursorRepository.findAll().groupBy { it.publisherId }
        val drawThemes = PublisherDrawThemeRepository.findAll().associateBy { it.publisherId }
        return PublisherRepository.findAll()
            .sortedWith(compareBy<Publisher> { it.platformId.value }.thenBy { it.externalId })
            .map { publisher ->
                publisher.toDto(
                    subscriptionCount = subscriptionCounts[publisher.id]?.toLong() ?: 0,
                    liveStatuses = liveStatuses[publisher.id].orEmpty(),
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
                PublisherPlatformDto(
                    platformId = plugin.platformId.value,
                    pluginId = info.descriptor.id,
                    pluginName = info.descriptor.name,
                    pluginState = info.state.name,
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
        val plugin = handle?.instance ?: publisherLookupResolver(platform)
            ?: throw NoSuchElementException("未找到发布者查询插件：$platform")
        val publisherInfo = plugin.fetchPublisherInfo(externalId)?.normalized() ?: return emptyList()
        val pluginInfo = handle?.info ?: fallbackPublisherPluginInfo(platform)
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
                targets += sink.listMessageTargets(targetType)
                    .filter { target ->
                        (normalizedPlatform == null || target.address.platformId.value.equals(normalizedPlatform, ignoreCase = true)) &&
                            (targetType == null || target.address.kind == targetType)
                    }
                    .map { target -> MessageTargetWithPlugin(target, info) }
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
        val targetProfile = resolveSubscriberTarget(address)
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

    public suspend fun createSubscription(request: CreateSubscriptionRequest): CreateSubscriptionResponse {
        val publisherUpsert = if (request.publisherId != null) {
            val publisher = PublisherRepository.findById(request.publisherId)
                ?: throw NoSuchElementException("发布者不存在：publisherId=${request.publisherId}")
            UpsertResult(publisher, created = false, updated = false)
        } else {
            val platform = request.publisherPlatform?.trim()?.lowercase().orEmpty()
            val externalId = request.publisherExternalId?.trim().orEmpty()
            require(platform.isNotBlank()) { "发布者平台不能为空" }
            require(externalId.isNotBlank()) { "发布者外部 ID 不能为空" }

            val lookupPlugin = publisherLookupResolver(platform)
                ?: throw NoSuchElementException("未找到发布者查询插件：$platform")
            val publisherInfo = lookupPlugin.fetchPublisherInfo(externalId)
                ?: throw NoSuchElementException("未找到发布者：$platform:$externalId")
            PublisherRepository.upsertInfo(publisherInfo.normalized())
        }
        val publisherPlatform = publisherUpsert.value.key.platformId.value
        val publisherExternalId = publisherUpsert.value.key.externalId
        val autoFollowed = if (configProvider().subscription.autoFollowPublisherOnSubscribe) {
            val followPlugin = publisherFollowResolver(publisherPlatform)
                ?: throw NoSuchElementException("未找到发布者关注插件：$publisherPlatform")
            ensureFollowed(followPlugin, publisherPlatform, publisherExternalId)
        } else {
            false
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
            val targetProfile = resolveSubscriberTarget(subscriberAddress)
            SubscriberRepository.upsert(
                address = subscriberAddress,
                name = targetProfile?.name?.trim()?.takeIf { it.isNotBlank() }
                    ?: existedSubscriber?.name
                    ?: subscriberAddress.externalId,
                avatar = targetProfile?.avatar,
            )
        }
        parseLinkParseTriggerModeOrNull(request.subscriberLinkParseTriggerMode)?.let { mode ->
            LinkParseTargetConfigRepository.upsert(
                address = subscriberUpsert.value.address,
                triggerMode = mode,
                updatedBy = "web-admin",
            )
        }
        requireSubscriptionPolicyAllowed(subscriberUpsert.value, request.policy)
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
            SubscriptionRepository.updatePolicy(existing.id, request.policy)
        }
        val subscription = SubscriptionRepository.findBySubscriberAndPublisher(
            subscriberId = subscriberUpsert.value.id,
            publisherId = publisherUpsert.value.id,
        ) ?: throw IllegalStateException("订阅创建失败")

        logger.info {
            "后台订阅已${if (subscriptionResult.changed) "创建" else "更新"}：subscriptionId=${subscription.id}，publisherId=${publisherUpsert.value.id}，subscriberId=${subscriberUpsert.value.id}，autoFollowed=$autoFollowed"
        }
        return CreateSubscriptionResponse(
            subscription = subscription.toDto(
                mapOf(publisherUpsert.value.id to publisherUpsert.value),
                mapOf(subscriberUpsert.value.id to subscriberUpsert.value),
                DynamicFilterRuleRepository.findBySubscriptionId(subscription.id),
            ),
            publisherCreated = publisherUpsert.created,
            publisherUpdated = publisherUpsert.updated,
            subscriberCreated = subscriberUpsert.created,
            subscriberUpdated = subscriberUpsert.updated,
            subscriptionCreated = subscriptionResult.changed,
            autoFollowed = autoFollowed,
        )
    }

    public fun updateSubscription(id: Int, request: UpdateSubscriptionRequest): SubscriptionDto {
        val subscription = SubscriptionRepository.findById(id) ?: throw NoSuchElementException("未找到订阅：$id")
        val subscriber = SubscriberRepository.findById(subscription.subscriberId)
            ?: throw NoSuchElementException("未找到消息目标：${subscription.subscriberId}")
        requireSubscriptionPolicyAllowed(subscriber, request.policy)
        val updated = SubscriptionRepository.updatePolicy(subscription.id, request.policy)
        logger.info {
            "后台订阅规则已更新：subscriptionId=${updated.id}，publisherId=${updated.publisherId}，subscriberId=${updated.subscriberId}"
        }
        return updated.toDto(
            publishers = PublisherRepository.findById(updated.publisherId)?.let { mapOf(it.id to it) }.orEmpty(),
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
            values = AdminConfigJson.valuesFor(current, plugin.configFormSpec, maskSecrets = false),
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
            values = AdminConfigJson.valuesFor(current, MainConfigForms.formSpec, maskSecrets = false),
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
            values = AdminConfigJson.valuesFor(saved, MainConfigForms.formSpec, maskSecrets = false),
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
            values = AdminConfigJson.valuesFor(saved, plugin.configFormSpec, maskSecrets = false),
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

    private suspend fun ensureFollowed(
        plugin: PublisherFollowPlugin,
        platform: String,
        externalId: String,
    ): Boolean {
        return when (plugin.queryFollowState(externalId)) {
            FollowState.FOLLOWING -> false
            FollowState.NOT_FOLLOWING -> {
                val result = plugin.followPublisher(externalId)
                when (result.status) {
                    FollowActionStatus.FOLLOWED -> true
                    FollowActionStatus.ALREADY_FOLLOWING -> false
                    FollowActionStatus.FAILED -> throw IllegalStateException(
                        result.message ?: "关注发布者失败：$platform",
                    )
                    FollowActionStatus.UNSUPPORTED -> throw IllegalStateException("平台不支持关注操作：$platform")
                }
            }
            FollowState.UNSUPPORTED -> throw IllegalStateException("平台不支持关注状态检查：$platform")
        }
    }

    private suspend fun fetchPublisherInfo(platform: String, externalId: String): Pair<PublisherInfo, PluginInfo> {
        val handle = publisherLookupProvider()
            .firstOrNull { it.instance.platformId.value.equals(platform, ignoreCase = true) }
        val plugin = handle?.instance ?: publisherLookupResolver(platform)
            ?: throw NoSuchElementException("未找到发布者查询插件：$platform")
        val publisherInfo = plugin.fetchPublisherInfo(externalId)?.normalized()
            ?: throw NoSuchElementException("未找到发布者：$platform:$externalId")
        val pluginInfo = handle?.info ?: fallbackPublisherPluginInfo(platform)
        return publisherInfo to pluginInfo
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
            if (candidate.address.stableValue() == address.stableValue()) return candidate
        }
        return null
    }
}

public class PluginReloadFailedException(
    public val response: PluginReloadResponse,
) : IllegalStateException(response.error ?: response.message)

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
    liveStatuses: List<PublisherLiveStatus> = emptyList(),
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
    drawTheme = drawTheme?.palette?.toDto(),
    liveStatuses = liveStatuses.sortedBy { it.roomId }.map { it.toDto() },
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
    linkColor = linkColor,
    textColor = textColor,
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

private fun MessageDelivery.toDto(): MessageDeliveryDto = MessageDeliveryDto(
    id = id,
    messageId = messageId,
    sourceUpdateKey = sourceUpdateKey?.stableValue(),
    renderVariant = renderVariant,
    platformId = target.platformId.value,
    targetKind = target.kind.name,
    targetId = target.externalId,
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

private fun requireSubscriptionPolicyAllowed(subscriber: Subscriber, policy: SubscriptionPolicy) {
    require(policy.enabledEvents.isNotEmpty()) {
        "订阅规则至少需要启用一个事件"
    }
    require(policy.mentionAllEvents.all { it in policy.enabledEvents }) {
        "@全体事件必须先启用接收"
    }
    if (policy.mentionAllEvents.isEmpty()) return
    require(subscriber.kind == TargetKind.GROUP) {
        "只有 GROUP 消息目标可以启用 @全体"
    }
}

private fun PublisherInfo.normalized(): PublisherInfo = copy(
    key = PublisherKey.of(platformId.value, kind, externalId),
)

private const val LOGIN_STATE_CACHE_TTL_MS: Long = 15_000
private const val LOGIN_STATE_TIMEOUT_MS: Long = 5_000
