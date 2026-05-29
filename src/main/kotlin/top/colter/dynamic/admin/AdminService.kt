package top.colter.dynamic.admin

import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.MainConfigForms
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.YamlConfigService
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.DynamicAttachmentKind
import top.colter.dynamic.core.data.DynamicFilterRule
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.FilterAction
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MentionMode
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.EventBus
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PluginHandle
import top.colter.dynamic.core.plugin.PluginInfo
import top.colter.dynamic.core.plugin.PluginManager
import top.colter.dynamic.core.plugin.PluginReloadResult
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.repository.PublisherLiveStatusRepository
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SourceCursorRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import top.colter.dynamic.core.repository.SubscriptionMutationResult

public class AdminService(
    private val pluginProvider: () -> List<PluginInfo>,
    private val messageSinkProvider: () -> List<PluginHandle<MessageSinkPlugin>> = { emptyList() },
    private val publisherLoginProvider: () -> List<PluginHandle<PublisherLoginProvider>> = { emptyList() },
    private val publisherLookupResolver: (String) -> PublisherLookupPlugin?,
    private val publisherFollowResolver: (String) -> PublisherFollowPlugin?,
    private val configurablePluginProvider: () -> List<PluginHandle<ConfigurablePlugin<*>>> = { emptyList() },
    private val configProvider: () -> MainDynamicConfig,
    private val mainConfigUpdater: (MainDynamicConfig) -> ConfigApplyResult = {
        throw IllegalStateException("main config editing is not configured")
    },
    private val configService: ConfigService = YamlConfigService(),
    private val commandRegistry: CommandRegistry = CommandRegistry(),
    private val eventBus: EventBus = EventBus(),
    private val pluginReloader: (String) -> PluginReloadResult = {
        throw IllegalStateException("插件重载功能未配置")
    },
) {
    public constructor(
        pluginManager: PluginManager,
        configProvider: () -> MainDynamicConfig,
        mainConfigUpdater: (MainDynamicConfig) -> ConfigApplyResult = {
            throw IllegalStateException("main config editing is not configured")
        },
        configService: ConfigService = YamlConfigService(),
        commandRegistry: CommandRegistry = CommandRegistry(),
        eventBus: EventBus = EventBus(),
    ) : this(
        pluginProvider = { pluginManager.getAllPlugins() },
        messageSinkProvider = { pluginManager.getMessageSinkPlugins() },
        publisherLoginProvider = { pluginManager.getPublisherLoginProviders() },
        publisherLookupResolver = { platformId -> pluginManager.findPublisherLookupPlugin(platformId) },
        publisherFollowResolver = { platformId -> pluginManager.findPublisherFollowPlugin(platformId) },
        configurablePluginProvider = { pluginManager.getConfigurablePlugins() },
        configProvider = configProvider,
        mainConfigUpdater = mainConfigUpdater,
        configService = configService,
        commandRegistry = commandRegistry,
        eventBus = eventBus,
        pluginReloader = pluginManager::reloadPlugin,
    )

    public fun overview(): OverviewResponse {
        return OverviewResponse(
            commandCount = commandRegistry.listCommands().size,
            subscriptionCount = SubscriptionRepository.countAll(),
            deliveryPending = MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING),
            deliveryFailed = MessageDeliveryRepository.countByStatus(DeliveryStatus.FAILED),
            plugins = plugins(),
        )
    }

    public fun plugins(): List<PluginDto> {
        return pluginProvider()
            .sortedBy { it.descriptor.id }
            .map { it.toDto() }
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

    public suspend fun platformLogins(): List<PlatformLoginDto> {
        return publisherLoginProvider()
            .map { handle ->
                val info = handle.info
                val plugin = handle.instance
                    val loginState = runCatching { plugin.checkLoginState() }
                    .getOrElse { error ->
                        PublisherLoginResult(
                            status = PublisherLoginStatus.FAILED,
                            message = error.message ?: "登录状态检查失败",
                        )
                    }
                PlatformLoginDto(
                    platformId = plugin.platformId.value,
                    pluginId = info.descriptor.id,
                    pluginName = info.descriptor.name,
                    pluginVersion = info.descriptor.version,
                    pluginState = info.state.name,
                    supportedLoginMethods = plugin.supportedLoginMethods.map { it.name }.sorted(),
                    status = loginState.status.name,
                    message = loginState.message,
                    account = loginState.account?.toDto(),
                )
            }
            .sortedBy { it.platformId }
    }

    public fun publishers(): List<PublisherDto> {
        return PublisherRepository.findAll()
            .sortedWith(compareBy<Publisher> { it.platformId.value }.thenBy { it.externalId })
            .map { it.toDto() }
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
        return PublisherRepository.findById(id)?.toDto() ?: updated.toDto()
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
        val removed = PublisherRepository.deleteById(publisher.id)
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
        return SubscriberRepository.findAll()
            .sortedWith(compareBy<Subscriber> { it.platformId.value }.thenBy { it.kind.name }.thenBy { it.externalId })
            .map { it.toDto() }
    }

    public fun subscriberTargetPlatforms(): List<SubscriberTargetPlatformDto> {
        return messageSinkProvider()
            .map { handle ->
                val info = handle.info
                val sink = handle.instance
                val platformId = sink.platformId.value
                SubscriberTargetPlatformDto(
                    platformId = platformId,
                    pluginId = info.descriptor.id,
                    pluginName = info.descriptor.name,
                    pluginState = info.state.name,
                    supportedTypes = sink.supportedTargetKinds.map { it.name }.sorted(),
                )
            }
            .sortedWith(compareBy<SubscriberTargetPlatformDto> { it.platformId }.thenBy { it.pluginId })
    }

    public suspend fun subscriberTargets(platformId: String?, type: String?): List<SubscriberTargetDto> {
        val normalizedPlatform = platformId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val targetType = type?.trim()?.takeIf { it.isNotBlank() }?.let { parseEnum<TargetKind>(it, "type") }
        val targets = mutableListOf<SubscriberTargetDto>()
        messageSinkProvider()
            .forEach { handle ->
                val info = handle.info
                val sink = handle.instance
                val sinkPlatform = sink.platformId.value
                if (normalizedPlatform != null && !sinkPlatform.equals(normalizedPlatform, ignoreCase = true)) {
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
                    .map { target -> target.toDto(info) }
            }
        return targets
            .sortedWith(compareBy<SubscriberTargetDto> { it.platformId }.thenBy { it.targetKind }.thenBy { it.name }.thenBy { it.externalId })
    }

    public fun updateSubscriber(id: Int, request: UpdateSubscriberRequest): SubscriberDto {
        val subscriber = SubscriberRepository.findById(id) ?: throw NoSuchElementException("未找到消息目标：$id")
        val updated = subscriber.copy(
            name = request.name?.trim()?.takeIf { it.isNotBlank() } ?: subscriber.name,
            state = request.state?.let { parseEnum<EntityState>(it, "state") } ?: subscriber.state,
        )
        SubscriberRepository.replace(updated)
        return SubscriberRepository.findById(id)?.toDto() ?: updated.toDto()
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
        val removed = SubscriberRepository.deleteById(subscriber.id)
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
        return SubscriptionRepository.findAll()
            .sortedBy { it.id }
            .map { it.toDto(publishers, subscribers) }
    }

    public suspend fun createSubscription(request: CreateSubscriptionRequest): CreateSubscriptionResponse {
        val platform = request.publisherPlatform.trim().lowercase()
        val externalId = request.publisherExternalId.trim()
        require(platform.isNotBlank()) { "发布者平台不能为空" }
        require(externalId.isNotBlank()) { "发布者外部 ID 不能为空" }

        val lookupPlugin = publisherLookupResolver(platform)
            ?: throw NoSuchElementException("未找到发布者查询插件：$platform")
        val publisherInfo = lookupPlugin.fetchPublisherInfo(externalId)
            ?: throw NoSuchElementException("未找到发布者：$platform:$externalId")

        val autoFollowed = if (request.autoFollow) {
            val followPlugin = publisherFollowResolver(platform)
                ?: throw NoSuchElementException("未找到发布者关注插件：$platform")
            ensureFollowed(followPlugin, platform, externalId)
        } else {
            false
        }
        val publisherUpsert = PublisherRepository.upsertInfo(publisherInfo.normalized())
        val subscriberPlatform = request.subscriberPlatform.trim().lowercase().also {
            require(it.isNotBlank()) { "消息目标平台不能为空" }
        }
        val subscriberExternalId = request.subscriberTargetId.trim().also {
            require(it.isNotBlank()) { "消息目标 ID 不能为空" }
        }
        val subscriberAddress = TargetAddress.of(
            platformId = subscriberPlatform,
            kind = parseEnum<TargetKind>(request.targetKind, "targetKind"),
            externalId = subscriberExternalId,
        )
        val subscriberUpsert = SubscriberRepository.upsert(
            address = subscriberAddress,
            name = request.subscriberName?.trim()?.takeIf { it.isNotBlank() } ?: subscriberExternalId,
        )
        requireMentionRulesTargetAllowed(subscriberUpsert.value, request.policy)
        val subscriptionResult = SubscriptionRepository.subscribe(
            subscriberId = subscriberUpsert.value.id,
            publisherId = publisherUpsert.value.id,
            policy = request.policy,
        )
        applySubscriptionMutation(subscriptionResult)
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
        ) ?: throw IllegalStateException("subscription was not created")

        return CreateSubscriptionResponse(
            subscription = subscription.toDto(
                mapOf(publisherUpsert.value.id to publisherUpsert.value),
                mapOf(subscriberUpsert.value.id to subscriberUpsert.value),
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
        requireMentionRulesTargetAllowed(subscriber, request.policy)
        val updated = SubscriptionRepository.updatePolicy(subscription.id, request.policy)
        return updated.toDto(
            publishers = PublisherRepository.findById(updated.publisherId)?.let { mapOf(it.id to it) }.orEmpty(),
            subscribers = mapOf(subscriber.id to subscriber),
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

    public fun createFilterRule(request: CreateFilterRuleRequest): DynamicFilterRuleDto {
        val result = DynamicFilterRuleRepository.addRule(
            subscriptionId = request.subscriptionId,
            action = parseEnum<FilterAction>(request.action, "action"),
            condition = request.condition,
            priority = request.priority,
        )
        return result.value.toDto()
    }

    public fun deleteFilterRule(id: Int): ActionResultResponse {
        val removed = DynamicFilterRuleRepository.removeById(id)
        if (!removed) throw NoSuchElementException("未找到过滤规则：$id")
        return ActionResultResponse(true, "过滤规则已删除")
    }

    public fun clearFilterRules(subscriptionId: Int): ActionResultResponse {
        require(SubscriptionRepository.findById(subscriptionId) != null) {
            "未找到订阅：$subscriptionId"
        }
        val removed = DynamicFilterRuleRepository.clearBySubscriptionId(subscriptionId)
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
            values = AdminConfigJson.valuesFor(current, plugin.configFormSpec),
            secretStates = AdminConfigJson.secretStates(current, plugin.configFormSpec),
        )
    }

    public fun updateConfig(id: String, request: UpdateConfigRequest): UpdateConfigResponse {
        if (id == MainDynamicConfig.CONFIG_ID) return updateMainConfig(request)
        val (info, plugin) = configurablePlugins().firstOrNull { (_, plugin) -> plugin.configId == id }
            ?: throw NoSuchElementException("未找到配置：$id")
        return updatePluginConfig(info, plugin, request)
    }

    private fun mainConfigDetail(): ConfigDetailDto {
        val current = configProvider()
        return ConfigDetailDto(
            id = MainDynamicConfig.CONFIG_ID,
            name = "主配置",
            description = "主项目配置",
            sourcePath = configService.resolvePath(MainDynamicConfig.CONFIG_ID).toString(),
            schema = MainConfigForms.formSpec,
            values = AdminConfigJson.valuesFor(current, MainConfigForms.formSpec),
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
        return UpdateConfigResponse(
            changed = result.changed,
            restartRequired = result.restartRequired,
            restartTargets = result.restartTargets,
            message = result.message,
            pluginId = null,
            values = AdminConfigJson.valuesFor(saved, MainConfigForms.formSpec),
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
            plugin.applyConfig(next).also {
                configService.save(plugin.configId, next)
            }
        } else {
            ConfigApplyResult(changed = false, message = "${plugin.configName}配置未变化")
        }
        val saved = if (changed) next else current
        return UpdateConfigResponse(
            changed = result.changed,
            restartRequired = result.restartRequired,
            restartTargets = result.restartTargets,
            message = result.message,
            pluginId = info.descriptor.id,
            values = AdminConfigJson.valuesFor(saved, plugin.configFormSpec),
            secretStates = AdminConfigJson.secretStates(saved, plugin.configFormSpec),
        )
    }

    private fun configurablePlugins(): List<Pair<PluginInfo, ConfigurablePlugin<*>>> {
        return configurablePluginProvider()
            .map { it.info to it.instance }
            .sortedBy { (_, plugin) -> plugin.configId }
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
}

public class PluginReloadFailedException(
    public val response: PluginReloadResponse,
) : IllegalStateException(response.error ?: response.message)

private fun PluginInfo.toDto(): PluginDto = PluginDto(
    id = descriptor.id,
    name = descriptor.name,
    version = descriptor.version,
    capabilities = capabilities.map { it.name }.sorted(),
    state = state.name,
    error = error?.message ?: error?.javaClass?.name,
    sourceJarPath = sourceJarPath,
    loadTime = loadTime,
)

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
)

private fun Publisher.toDto(): PublisherDto = PublisherDto(
    id = id,
    platformId = platformId.value,
    kind = kind.name,
    externalId = externalId,
    name = name,
    official = official,
    state = state.name,
    avatarUri = avatar.uri,
    pendantUri = pendant?.uri,
    bannerUri = banner?.uri,
    createTime = createTime,
    createUser = createUser,
)

private fun Subscriber.toDto(): SubscriberDto = SubscriberDto(
    id = id,
    platformId = platformId.value,
    targetKind = kind.name,
    externalId = externalId,
    scopeId = address.scopeId,
    threadId = address.threadId,
    accountId = address.accountId,
    name = name,
    state = state.name,
    createTime = createTime,
    createUser = createUser,
)

private fun MessageTargetCandidate.toDto(info: PluginInfo): SubscriberTargetDto = SubscriberTargetDto(
    platformId = address.platformId.value,
    targetKind = address.kind.name,
    externalId = address.externalId,
    name = name,
    sourcePluginId = info.descriptor.id,
    sourcePluginName = info.descriptor.name,
)

private fun Subscription.toDto(
    publishers: Map<Int, Publisher>,
    subscribers: Map<Int, Subscriber>,
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
)

private fun DynamicFilterRule.toDto(): DynamicFilterRuleDto = DynamicFilterRuleDto(
    id = id,
    subscriptionId = subscriptionId,
    action = action.name,
    condition = condition,
    priority = priority,
    enabled = enabled,
    createdAtEpochSeconds = createdAtEpochSeconds,
)

private inline fun <reified T : Enum<T>> parseEnum(value: String, fieldName: String): T {
    val normalized = value.trim()
    return enumValues<T>().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        ?: throw IllegalArgumentException(
            "$fieldName 无效：$value，可选值=${enumValues<T>().joinToString("|") { it.name }}",
        )
}

private fun requireMentionRulesTargetAllowed(subscriber: Subscriber, policy: SubscriptionPolicy) {
    val mentionsAll = policy.mentionRules.any { it.mode == MentionMode.MENTION_ALL }
    if (!mentionsAll) return
    require(subscriber.kind == TargetKind.GROUP) {
        "只有 GROUP 消息目标可以启用 MENTION_ALL"
    }
}

private fun PublisherInfo.normalized(): PublisherInfo = copy(
    key = PublisherKey.of(platformId.value, kind, externalId),
)
