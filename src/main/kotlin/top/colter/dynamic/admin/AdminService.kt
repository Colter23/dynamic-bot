package top.colter.dynamic.admin

import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.MainConfigForms
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.DynamicElementType
import top.colter.dynamic.core.data.DynamicFilterMatcher
import top.colter.dynamic.core.data.DynamicFilterRule
import top.colter.dynamic.core.data.DynamicFilterRuleType
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherProfile
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.plugin.PluginInfo
import top.colter.dynamic.core.plugin.PluginManager
import top.colter.dynamic.core.plugin.PluginReloadResult
import top.colter.dynamic.core.plugin.PluginState
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.repository.PublisherCursorRepository
import top.colter.dynamic.core.repository.PublisherLiveStatusRepository
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.PublisherTemplateRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository

public class AdminService(
    private val pluginProvider: () -> List<PluginInfo>,
    private val platformPluginResolver: (String) -> PlatformPublisherPlugin?,
    private val configProvider: () -> MainDynamicConfig,
    private val mainConfigUpdater: (MainDynamicConfig) -> ConfigApplyResult = {
        throw IllegalStateException("main config editing is not configured")
    },
    private val configService: ConfigService = DefaultConfigService,
    private val pluginReloader: (String) -> PluginReloadResult = {
        throw IllegalStateException("plugin reload is not configured")
    },
) {
    public constructor(
        pluginManager: PluginManager,
        configProvider: () -> MainDynamicConfig,
        mainConfigUpdater: (MainDynamicConfig) -> ConfigApplyResult = {
            throw IllegalStateException("main config editing is not configured")
        },
        configService: ConfigService = DefaultConfigService,
    ) : this(
        pluginProvider = { pluginManager.getAllPlugins() },
        platformPluginResolver = { platformId -> pluginManager.findPlatformPublisherPlugin(platformId) },
        configProvider = configProvider,
        mainConfigUpdater = mainConfigUpdater,
        configService = configService,
        pluginReloader = pluginManager::reloadPlugin,
    )

    public fun overview(): OverviewResponse {
        return OverviewResponse(
            commandCount = CommandRegistry.listCommands().size,
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
        require(pluginId.isNotBlank()) { "plugin id must not be blank" }
        pluginProvider().firstOrNull { it.descriptor.id == pluginId }
            ?: throw NoSuchElementException("plugin not found: $pluginId")

        val result = pluginReloader(pluginId)
        val response = result.toResponse()
        if (!result.success) throw PluginReloadFailedException(response)
        return response
    }

    public suspend fun platformLogins(): List<PlatformLoginDto> {
        return pluginProvider()
            .filter { it.state == PluginState.ACTIVE }
            .mapNotNull { info ->
                val plugin = info.instance as? PlatformPublisherPlugin ?: return@mapNotNull null
                val loginState = runCatching { plugin.checkLoginState() }
                    .getOrElse { error ->
                        PublisherLoginResult(
                            status = PublisherLoginStatus.FAILED,
                            message = error.message ?: "login status check failed",
                        )
                    }
                PlatformLoginDto(
                    platformId = plugin.platformId,
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
            .sortedWith(compareBy<Publisher> { it.platformId }.thenBy { it.externalId })
            .map { it.toDto() }
    }

    public fun updatePublisher(id: Int, request: UpdatePublisherRequest): PublisherDto {
        val publisher = PublisherRepository.findById(id) ?: throw NoSuchElementException("publisher not found: $id")
        val updated = publisher.copy(
            name = request.name?.trim()?.takeIf { it.isNotBlank() } ?: publisher.name,
            header = request.headerUri?.trim()?.takeIf { it.isNotBlank() }?.let(::LazyImage)
                ?: if (request.headerUri != null) null else publisher.header,
            state = request.state?.let { parseEnum<EntityState>(it, "state") } ?: publisher.state,
        )
        PublisherRepository.replace(updated)
        return PublisherRepository.findById(id)?.toDto() ?: updated.toDto()
    }

    public fun deletePublisher(id: Int): ActionResultResponse {
        val publisher = PublisherRepository.findById(id) ?: throw NoSuchElementException("publisher not found: $id")
        val removedSubscriptions = SubscriptionRepository.findAll()
            .filter { it.publisherId == publisher.id }
            .count { subscription -> SubscriptionRepository.unsubscribe(subscription.subscriberId, subscription.publisherId) }
        PublisherTemplateRepository.removePublisherTemplate(publisher.id)
        PublisherCursorRepository.deleteByPublisherId(publisher.id)
        PublisherLiveStatusRepository.deleteByPublisherId(publisher.id)
        val removed = PublisherRepository.deleteById(publisher.id)
        return ActionResultResponse(
            changed = removed,
            message = if (removed) {
                "publisher removed: subscriptions=$removedSubscriptions"
            } else {
                "publisher unchanged"
            },
        )
    }

    public fun subscribers(): List<SubscriberDto> {
        return SubscriberRepository.findAll()
            .sortedWith(compareBy<Subscriber> { it.platformId }.thenBy { it.type.name }.thenBy { it.targetId })
            .map { it.toDto() }
    }

    public fun subscriberTargetPlatforms(): List<SubscriberTargetPlatformDto> {
        return pluginProvider()
            .filter { it.state == PluginState.ACTIVE }
            .mapNotNull { info ->
                val sink = info.instance as? MessageSinkPlugin ?: return@mapNotNull null
                val platformId = sink.targetPlatformId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SubscriberTargetPlatformDto(
                    platformId = platformId,
                    pluginId = info.descriptor.id,
                    pluginName = info.descriptor.name,
                    pluginState = info.state.name,
                    supportedTypes = sink.supportedTargetTypes.map { it.name }.sorted(),
                )
            }
            .sortedWith(compareBy<SubscriberTargetPlatformDto> { it.platformId }.thenBy { it.pluginId })
    }

    public suspend fun subscriberTargets(platformId: String?, type: String?): List<SubscriberTargetDto> {
        val normalizedPlatform = platformId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val targetType = type?.trim()?.takeIf { it.isNotBlank() }?.let { parseEnum<SubscriberType>(it, "type") }
        val targets = mutableListOf<SubscriberTargetDto>()
        pluginProvider()
            .filter { it.state == PluginState.ACTIVE }
            .forEach { info ->
                val sink = info.instance as? MessageSinkPlugin ?: return@forEach
                val sinkPlatform = sink.targetPlatformId.takeIf { it.isNotBlank() } ?: return@forEach
                if (normalizedPlatform != null && !sinkPlatform.equals(normalizedPlatform, ignoreCase = true)) {
                    return@forEach
                }
                if (targetType != null && sink.supportedTargetTypes.isNotEmpty() && targetType !in sink.supportedTargetTypes) {
                    return@forEach
                }
                targets += sink.listMessageTargets(targetType)
                    .filter { target ->
                        (normalizedPlatform == null || target.platformId.equals(normalizedPlatform, ignoreCase = true)) &&
                            (targetType == null || target.type == targetType)
                    }
                    .map { target -> target.toDto(info) }
            }
        return targets
            .sortedWith(compareBy<SubscriberTargetDto> { it.platformId }.thenBy { it.type }.thenBy { it.name }.thenBy { it.targetId })
    }

    public fun updateSubscriber(id: Int, request: UpdateSubscriberRequest): SubscriberDto {
        val subscriber = SubscriberRepository.findById(id) ?: throw NoSuchElementException("subscriber not found: $id")
        val updated = subscriber.copy(
            name = request.name?.trim()?.takeIf { it.isNotBlank() } ?: subscriber.name,
            state = request.state?.let { parseEnum<EntityState>(it, "state") } ?: subscriber.state,
        )
        SubscriberRepository.replace(updated)
        return SubscriberRepository.findById(id)?.toDto() ?: updated.toDto()
    }

    public fun deleteSubscriber(id: Int): ActionResultResponse {
        val subscriber = SubscriberRepository.findById(id) ?: throw NoSuchElementException("subscriber not found: $id")
        val removedSubscriptions = SubscriptionRepository.findAll()
            .filter { it.subscriberId == subscriber.id }
            .count { subscription -> SubscriptionRepository.unsubscribe(subscription.subscriberId, subscription.publisherId) }
        val removed = SubscriberRepository.deleteById(subscriber.id)
        return ActionResultResponse(
            changed = removed,
            message = if (removed) {
                "subscriber removed: subscriptions=$removedSubscriptions"
            } else {
                "subscriber unchanged"
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
        require(platform.isNotBlank()) { "publisherPlatform must not be blank" }
        require(externalId.isNotBlank()) { "publisherExternalId must not be blank" }

        val plugin = platformPluginResolver(platform)
            ?: throw NoSuchElementException("platform plugin not found: $platform")
        val profile = plugin.fetchPublisherProfile(externalId)
            ?: throw NoSuchElementException("publisher not found on $platform: $externalId")

        val autoFollowed = if (request.autoFollow) ensureFollowed(plugin, platform, externalId) else false
        val publisherUpsert = PublisherRepository.upsertProfile(profile.normalized())
        val subscriberUpsert = SubscriberRepository.upsert(
            platformId = request.subscriberPlatform.trim().lowercase().also {
                require(it.isNotBlank()) { "subscriberPlatform must not be blank" }
            },
            targetId = request.subscriberTargetId.trim().also {
                require(it.isNotBlank()) { "subscriberTargetId must not be blank" }
            },
            name = request.subscriberName?.trim()?.takeIf { it.isNotBlank() } ?: request.subscriberTargetId.trim(),
            type = parseEnum<SubscriberType>(request.subscriberType, "subscriberType"),
        )
        val subscriptionCreated = SubscriptionRepository.subscribe(
            subscriberId = subscriberUpsert.value.id,
            publisherId = publisherUpsert.value.id,
        )
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
            subscriptionCreated = subscriptionCreated,
            autoFollowed = autoFollowed,
        )
    }

    public suspend fun deleteSubscription(id: Int): ActionResultResponse {
        val subscription = SubscriptionRepository.findById(id)
            ?: throw NoSuchElementException("subscription not found: $id")
        val publisher = PublisherRepository.findById(subscription.publisherId)
        val removed = SubscriptionRepository.unsubscribe(subscription.subscriberId, subscription.publisherId)
        if (removed && publisher != null && configProvider().subscription.unfollowWhenNoSubscribers) {
            if (SubscriptionRepository.countByPublisherId(publisher.id) == 0L) {
                platformPluginResolver(publisher.platformId)?.unfollowPublisher(publisher.externalId)
            }
        }
        return ActionResultResponse(removed, if (removed) "subscription removed" else "subscription unchanged")
    }

    public fun filterRules(subscriptionId: Int?): List<DynamicFilterRuleDto> {
        val rules = if (subscriptionId == null) {
            DynamicFilterRuleRepository.findAll()
        } else {
            require(SubscriptionRepository.findById(subscriptionId) != null) {
                "subscription not found: $subscriptionId"
            }
            DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId)
        }
        return rules.sortedBy { it.id }.map { it.toDto() }
    }

    public fun createFilterRule(request: CreateFilterRuleRequest): DynamicFilterRuleDto {
        val ruleType = parseEnum<DynamicFilterRuleType>(request.ruleType, "ruleType")
        val result = when (ruleType) {
            DynamicFilterRuleType.ELEMENT -> {
                val element = parseEnum<DynamicElementType>(request.value, "value")
                DynamicFilterRuleRepository.addElementRule(request.subscriptionId, element)
            }
            DynamicFilterRuleType.CONTENT -> {
                val matcher = parseEnum<DynamicFilterMatcher>(request.matcher, "matcher")
                require(matcher == DynamicFilterMatcher.KEYWORD || matcher == DynamicFilterMatcher.REGEX) {
                    "content matcher must be KEYWORD or REGEX"
                }
                DynamicFilterRuleRepository.addContentRule(request.subscriptionId, matcher, request.value)
            }
        }
        return result.value.toDto()
    }

    public fun deleteFilterRule(id: Int): ActionResultResponse {
        val removed = DynamicFilterRuleRepository.removeById(id)
        if (!removed) throw NoSuchElementException("filter rule not found: $id")
        return ActionResultResponse(true, "filter rule removed")
    }

    public fun clearFilterRules(subscriptionId: Int): ActionResultResponse {
        require(SubscriptionRepository.findById(subscriptionId) != null) {
            "subscription not found: $subscriptionId"
        }
        val removed = DynamicFilterRuleRepository.clearBySubscriptionId(subscriptionId)
        return ActionResultResponse(removed > 0, "filter rules removed: count=$removed")
    }

    public fun templates(): TemplatesResponse {
        val config = configProvider()
        return TemplatesResponse(
            templates = config.templates
                .toSortedMap()
                .map { (name, body) -> TemplateDto(name, body) },
            bindings = PublisherTemplateRepository.findAll()
                .sortedWith(compareBy({ it.publisherId ?: 0 }, { it.platformId ?: "" }, { it.dynamicType ?: "" }))
                .map { rule ->
                    TemplateBindingDto(
                        id = rule.id,
                        publisherId = rule.publisherId,
                        platformId = rule.platformId,
                        dynamicType = rule.dynamicType,
                        templateName = rule.templateName,
                        priority = rule.priority,
                    )
                },
        )
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
            ?: throw NoSuchElementException("config not found: $id")
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
            ?: throw NoSuchElementException("config not found: $id")
        return updatePluginConfig(info, plugin, request)
    }

    public fun setPublisherTemplate(publisherId: Int, request: TemplateBindingRequest): ActionResultResponse {
        val templateName = request.templateName.trim()
        require(templateName.isNotBlank()) { "templateName must not be blank" }
        require(configProvider().templates.containsKey(templateName)) { "template not found: $templateName" }
        val changed = PublisherTemplateRepository.setPublisherTemplate(publisherId, templateName)
        return ActionResultResponse(changed, if (changed) "template binding updated" else "template binding unchanged")
    }

    public fun removePublisherTemplate(publisherId: Int): ActionResultResponse {
        val removed = PublisherTemplateRepository.removePublisherTemplate(publisherId)
        return ActionResultResponse(removed, if (removed) "template binding removed" else "template binding not found")
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
                configService.save(plugin.configId, next, plugin.configClass)
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
        return pluginProvider()
            .mapNotNull { info ->
                val configurable = info.instance as? ConfigurablePlugin<*> ?: return@mapNotNull null
                info to configurable
            }
            .sortedBy { (_, plugin) -> plugin.configId }
    }

    private suspend fun ensureFollowed(
        plugin: PlatformPublisherPlugin,
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
                        result.message ?: "failed to follow publisher on $platform",
                    )
                    FollowActionStatus.UNSUPPORTED -> throw IllegalStateException("follow action is unsupported on $platform")
                }
            }
            FollowState.UNSUPPORTED -> throw IllegalStateException("follow check is unsupported on $platform")
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
    capabilities = descriptor.capabilities.map { it.name }.sorted(),
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
    platformId = platformId,
    type = type.name,
    externalId = externalId,
    name = name,
    official = official,
    state = state.name,
    faceUri = face.uri,
    pendantUri = pendant?.uri,
    headerUri = header?.uri,
    createTime = createTime,
    createUser = createUser,
)

private fun Subscriber.toDto(): SubscriberDto = SubscriberDto(
    id = id,
    platformId = platformId,
    type = type.name,
    targetId = targetId,
    name = name,
    state = state.name,
    createTime = createTime,
    createUser = createUser,
)

private fun MessageTargetCandidate.toDto(info: PluginInfo): SubscriberTargetDto = SubscriberTargetDto(
    platformId = platformId,
    type = type.name,
    targetId = targetId,
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
    subscriber = subscribers[subscriberId]?.toDto(),
    publisher = publishers[publisherId]?.toDto(),
)

private fun DynamicFilterRule.toDto(): DynamicFilterRuleDto = DynamicFilterRuleDto(
    id = id,
    subscriptionId = subscriptionId,
    ruleType = ruleType.name,
    matcher = matcher.name,
    value = value,
    enabled = enabled,
    createdAtEpochSeconds = createdAtEpochSeconds,
)

private inline fun <reified T : Enum<T>> parseEnum(value: String, fieldName: String): T {
    val normalized = value.trim()
    return enumValues<T>().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        ?: throw IllegalArgumentException(
            "invalid $fieldName: $value, expected ${enumValues<T>().joinToString("|") { it.name }}",
        )
}

private fun PublisherProfile.normalized(): PublisherProfile = copy(
    platformId = platformId.lowercase(),
)
