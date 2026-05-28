package top.colter.dynamic.listener

import java.nio.file.Paths
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicMedia
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.LiveChange
import top.colter.dynamic.core.data.LiveStatusUpdate
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionAtAllType
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.SourceUpdateEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.filter.DynamicFilterEvaluator
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.link.LINK_PARSE_EVENT_LABEL

private val logger = loggerFor<SourceUpdateListener>()

public class SourceUpdateListener(
    config: MainDynamicConfig? = null,
    configProvider: (() -> MainDynamicConfig)? = null,
    private val configService: ConfigService = DefaultConfigService,
    private val templateRenderer: PushTemplateRenderer = PushTemplateRenderer(),
    imageLoader: DynamicImageLoader? = null,
    imageRenderer: DynamicImageRenderer? = null,
) : Listener<SourceUpdateEvent> {
    private val fixedConfig: MainDynamicConfig by lazy {
        config ?: configService.loadOrCreate(MainDynamicConfig.CONFIG_ID) { MainDynamicConfig() }
    }
    private val runtimeConfigProvider: () -> MainDynamicConfig = configProvider ?: { fixedConfig }
    private val startupConfig: MainDynamicConfig by lazy { runtimeConfigProvider() }
    private val runtimeImageLoader: DynamicImageLoader by lazy {
        imageLoader ?: CachedDynamicImageLoader(startupConfig.imageCache)
    }
    private val runtimeImageRenderer: DynamicImageRenderer by lazy {
        imageRenderer ?: FileDynamicImageRenderer(
            outputDir = Paths.get(startupConfig.imageCache.renderedRoot),
            drawSettingsProvider = { runtimeConfigProvider().draw },
        )
    }

    override suspend fun onMessage(event: SourceUpdateEvent) {
        when (val update = event.update) {
            is Dynamic -> handleDynamic(event, update)
            is LiveStatusUpdate -> handleLive(event, update)
            else -> logger.warn {
                "跳过发布更新：不支持的更新类型=${update::class.qualifiedName}"
            }
        }
    }

    private suspend fun handleDynamic(event: SourceUpdateEvent, dynamic: Dynamic) {
        val normalizedDynamic = normalizeDynamicPublisher(dynamic)
        val targets = resolveTargets(event.target, normalizedDynamic.publisher)
        if (targets.isEmpty()) {
            logger.debug { "跳过动态：dynamicId=${normalizedDynamic.dynamicId}，原因=没有推送目标" }
            return
        }

        val deliverableTargets = if (event.label == LINK_PARSE_EVENT_LABEL) {
            targets
        } else {
            applyFilters(normalizedDynamic, targets.filterSubscribedBefore(normalizedDynamic.time))
        }
        if (deliverableTargets.isEmpty()) {
            logger.debug { "跳过动态：dynamicId=${normalizedDynamic.dynamicId}，原因=过滤后没有推送目标" }
            return
        }

        logger.info {
            "收到动态：platform=${normalizedDynamic.platform.id}，dynamicId=${normalizedDynamic.dynamicId}，目标数=${deliverableTargets.size}"
        }

        val template = resolveDynamicTemplate()
        val chain = buildDynamicMessageChain(template, normalizedDynamic)
        publishMessage(
            messageId = dynamicMessageId(normalizedDynamic),
            time = System.currentTimeMillis() / 1000,
            targets = deliverableTargets,
            chain = chain,
            skipReason = "dynamicId=${normalizedDynamic.dynamicId}",
            atAllMatchTypes = atAllMatchTypes(event, normalizedDynamic),
        )
    }

    private fun normalizeDynamicPublisher(dynamic: Dynamic): Dynamic {
        val incoming = dynamic.publisher
        val stored = findStoredPublisher(incoming) ?: return dynamic
        val normalizedPublisher = stored.copy(
            platformId = incoming.platformId,
            type = incoming.type,
            externalId = incoming.externalId,
            name = incoming.name,
            official = incoming.official,
            state = incoming.state,
            face = incoming.face,
            pendant = incoming.pendant,
            header = incoming.header ?: stored.header,
        )
        if (normalizedPublisher != stored) {
            PublisherRepository.replace(normalizedPublisher)
            logger.debug {
                "发布者信息已同步：publisherId=${normalizedPublisher.id}，uid=${normalizedPublisher.externalId}"
            }
        }
        return dynamic.copy(publisher = normalizedPublisher)
    }

    private fun findStoredPublisher(publisher: Publisher): Publisher? {
        if (publisher.id > 0) {
            PublisherRepository.findById(publisher.id)?.let { return it }
        }
        if (publisher.platformId.isBlank() || publisher.externalId.isBlank()) return null
        return PublisherRepository.findByPlatformAndExternalId(publisher.platformId, publisher.externalId)
    }

    private suspend fun handleLive(event: SourceUpdateEvent, live: LiveStatusUpdate) {
        val targets = resolveTargets(event.target, live.publisher).filterSubscribedBefore(live.time)
        if (targets.isEmpty()) {
            logger.debug { "跳过直播更新：roomId=${live.roomId}，状态变化=${live.change}，原因=没有推送目标" }
            return
        }

        logger.info {
            "收到直播更新：platform=${live.platform.id}，roomId=${live.roomId}，状态变化=${live.change}，目标数=${targets.size}"
        }

        val template = resolveLiveTemplate(live)
        val chain = buildLiveMessageChain(template, live)
        publishMessage(
            messageId = liveMessageId(live),
            time = System.currentTimeMillis() / 1000,
            targets = targets,
            chain = chain,
            skipReason = "roomId=${live.roomId}, change=${live.change}",
            atAllMatchTypes = atAllMatchTypes(event, live),
        )
    }

    private fun resolveTargets(target: Subscriber?, publisher: Publisher): List<DeliveryTarget> {
        target?.let {
            if (it.state != EntityState.ACTIVE) return emptyList()
            return listOf(
                DeliveryTarget(
                    subscriber = it,
                    subscription = SubscriptionRepository.findBySubscriberAndPublisher(
                        subscriberId = it.id,
                        publisherId = publisher.id,
                    ),
                )
            )
        }
        return SubscriptionRepository
            .findSubscriptionsWithSubscribersByPublisherId(publisher.id)
            .filter { it.subscriber.state == EntityState.ACTIVE }
            .map { DeliveryTarget(subscriber = it.subscriber, subscription = it.subscription) }
    }

    private fun applyFilters(dynamic: Dynamic, targets: List<DeliveryTarget>): List<DeliveryTarget> {
        val subscriptionIds = targets.mapNotNull { it.subscription?.id }
        if (subscriptionIds.isEmpty()) return targets

        val rulesBySubscriptionId = DynamicFilterRuleRepository.findBySubscriptionIds(subscriptionIds)
        return targets.filter { target ->
            val rules = target.subscription
                ?.let { rulesBySubscriptionId[it.id] }
                .orEmpty()
            val blocked = rules.isNotEmpty() && DynamicFilterEvaluator.isBlocked(dynamic, rules)
            if (blocked) {
                logger.debug {
                    "动态目标被过滤：dynamicId=${dynamic.dynamicId}，subscriberId=${target.subscriber.id}，subscriptionId=${target.subscription?.id}"
                }
            }
            !blocked
        }
    }

    private fun List<DeliveryTarget>.filterSubscribedBefore(updateTime: Long): List<DeliveryTarget> {
        return filter { target ->
            val subscription = target.subscription ?: return@filter true
            updateTime >= subscription.createdAtEpochSeconds
        }
    }

    private suspend fun buildDynamicMessageChain(template: String, dynamic: Dynamic): List<MessageChain> {
        val drawImage = if (templateRenderer.requiresDraw(template, dynamic)) renderImage(dynamic) else null
        return templateRenderer.render(template, dynamic, drawImage)
    }

    private suspend fun buildLiveMessageChain(template: String, live: LiveStatusUpdate): List<MessageChain> {
        val drawImage = if (templateRenderer.requiresDraw(template, live)) {
            renderImage(live.toDrawableDynamic())
        } else {
            null
        }
        return templateRenderer.render(template, live, drawImage)
    }

    private suspend fun renderImage(dynamic: Dynamic): LazyImage? {
        return runCatching {
            runtimeImageLoader.load(dynamic)
            LazyImage(runtimeImageRenderer.render(dynamic).toString())
        }.onFailure {
            logger.warn(it) {
                "绘图失败，已降级为文本：platform=${dynamic.platform.id}，publisher=${dynamic.publisher.externalId}，dynamicId=${dynamic.dynamicId}"
            }
        }.getOrNull()
    }

    private fun resolveDynamicTemplate(): String {
        return runtimeConfigProvider().templates.dynamic
    }

    private fun resolveLiveTemplate(live: LiveStatusUpdate): String {
        val templates = runtimeConfigProvider().templates
        return when (live.change) {
            LiveChange.STARTED -> templates.liveStarted
            LiveChange.ENDED -> templates.liveEnded
        }
    }

    private fun publishMessage(
        messageId: Long,
        time: Long,
        targets: List<DeliveryTarget>,
        chain: List<MessageChain>,
        skipReason: String,
        atAllMatchTypes: Set<SubscriptionAtAllType> = emptySet(),
    ) {
        if (chain.isEmpty()) {
            logger.warn { "跳过发布更新：$skipReason，原因=模板渲染为空" }
            return
        }

        val (atAllTargets, normalTargets) = targets.partition { it.shouldMentionAll(atAllMatchTypes) }
        publishMessageVariant(
            messageId = messageId,
            time = time,
            targets = normalTargets,
            chain = chain,
        )
        publishMessageVariant(
            messageId = messageId,
            time = time,
            targets = atAllTargets,
            chain = chain.withMentionAllAtTail(),
        )
    }

    private fun publishMessageVariant(
        messageId: Long,
        time: Long,
        targets: List<DeliveryTarget>,
        chain: List<MessageChain>,
    ) {
        if (targets.isEmpty()) return

        val message = Message(
            id = messageId,
            time = time,
            targets = targets.map { it.subscriber.toMessageTarget() },
            chain = chain,
        )
        MessageDeliveryRepository.createPending(message)
        logger.debug {
            "推送消息已生成：messageId=${message.id}，消息段数=${message.chain.size}，目标数=${message.targets.size}"
        }

        MessageEvent(
            source = "main",
            message = message,
        ).broadcast()
    }

    private fun DeliveryTarget.shouldMentionAll(matchTypes: Set<SubscriptionAtAllType>): Boolean {
        if (matchTypes.isEmpty()) return false
        if (subscriber.type != SubscriberType.GROUP) return false
        return subscription?.atAllTypes.orEmpty().any { it in matchTypes }
    }

    private fun atAllMatchTypes(event: SourceUpdateEvent, dynamic: Dynamic): Set<SubscriptionAtAllType> {
        if (event.label == LINK_PARSE_EVENT_LABEL) return emptySet()
        return buildSet {
            add(SubscriptionAtAllType.DYNAMIC)
            if (dynamic.media?.video != null) add(SubscriptionAtAllType.VIDEO)
        }
    }

    private fun atAllMatchTypes(event: SourceUpdateEvent, live: LiveStatusUpdate): Set<SubscriptionAtAllType> {
        if (event.label == LINK_PARSE_EVENT_LABEL) return emptySet()
        return if (live.change == LiveChange.STARTED) setOf(SubscriptionAtAllType.LIVE) else emptySet()
    }

    private fun List<MessageChain>.withMentionAllAtTail(): List<MessageChain> {
        if (isEmpty()) return this
        val result = toMutableList()
        val last = result.last()
        result[result.lastIndex] = last.copy(
            content = last.content + MessageContent.MentionAll(fallbackText = ""),
        )
        return result
    }

    private fun dynamicMessageId(dynamic: Dynamic): Long {
        return positiveHash("${dynamic.platform.id}:${dynamic.publisher.id}:${dynamic.dynamicId}")
    }

    private fun liveMessageId(live: LiveStatusUpdate): Long {
        return positiveHash(live.updateId)
    }

    private fun positiveHash(value: String): Long {
        return value.hashCode().toLong() and Long.MAX_VALUE
    }

    private fun LiveStatusUpdate.toDrawableDynamic(): Dynamic {
        val liveTitle = title.ifBlank { "直播" }
        val contentText = listOfNotNull(
            liveTitle,
            area?.takeIf { it.isNotBlank() },
            link.takeIf { it.isNotBlank() },
        ).joinToString("\n")
        val card = cover?.let {
            DynamicMediaCard(
                id = roomId,
                type = "live",
                title = liveTitle,
                description = area.orEmpty(),
                badge = "直播中",
                cover = it,
                link = link,
            )
        }

        return Dynamic(
            platform = platform,
            dynamicId = updateId,
            publisher = publisher,
            time = startedAt ?: time,
            link = link,
            notice = "直播中",
            title = liveTitle,
            content = DynamicContent(
                text = contentText,
                contentNodes = listOf(DynamicContentNodeText(contentText)),
            ),
            media = card?.let { DynamicMedia(card = it) },
        )
    }

    private data class DeliveryTarget(
        val subscriber: Subscriber,
        val subscription: Subscription?,
    )
}
