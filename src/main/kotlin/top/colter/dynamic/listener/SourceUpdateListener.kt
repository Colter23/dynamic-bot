package top.colter.dynamic.listener

import java.nio.file.Paths
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.YamlConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.CardAttachment
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MentionMode
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.EventBus
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.SourceUpdateEvent
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
    private val configService: ConfigService = YamlConfigService(),
    private val eventBus: EventBus = EventBus(),
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
        when (event.update.payload) {
            is DynamicPayload -> handleDynamic(event, event.update)
            is LivePayload -> handleLive(event, event.update)
        }
    }

    private suspend fun handleDynamic(event: SourceUpdateEvent, update: SourceUpdate) {
        val (normalizedUpdate, storedPublisher) = normalizePublisher(update)
        val targets = resolveTargets(event.deliveryTarget, storedPublisher)
        if (targets.isEmpty()) return

        val deliverableTargets = if (event.deliveryTag == LINK_PARSE_EVENT_LABEL) {
            targets
        } else {
            applyFilters(normalizedUpdate, targets.filterSubscribedBefore(normalizedUpdate.occurredAtEpochSeconds))
        }
        if (deliverableTargets.isEmpty()) return

        val chain = buildMessageBatches(resolveDynamicTemplate(), normalizedUpdate)
        publishMessage(
            update = normalizedUpdate,
            targets = deliverableTargets,
            batches = chain,
            skipReason = "update=${normalizedUpdate.key.stableValue()}",
        )
    }

    private suspend fun handleLive(event: SourceUpdateEvent, update: SourceUpdate) {
        val (normalizedUpdate, storedPublisher) = normalizePublisher(update)
        val targets = resolveTargets(event.deliveryTarget, storedPublisher)
            .filterSubscribedBefore(normalizedUpdate.occurredAtEpochSeconds)
        if (targets.isEmpty()) return

        val chain = buildMessageBatches(resolveLiveTemplate(normalizedUpdate), normalizedUpdate)
        publishMessage(
            update = normalizedUpdate,
            targets = targets,
            batches = chain,
            skipReason = "update=${normalizedUpdate.key.stableValue()}",
        )
    }

    private fun normalizePublisher(update: SourceUpdate): Pair<SourceUpdate, Publisher?> {
        val incoming = update.publisher
        val stored = PublisherRepository.findByKey(incoming.key) ?: return update to null
        val normalizedPublisher = stored.copy(
            key = incoming.key,
            name = incoming.name,
            official = incoming.official,
            state = incoming.state,
            avatar = incoming.avatar,
            pendant = incoming.pendant,
            banner = incoming.banner ?: stored.banner,
        )
        if (normalizedPublisher != stored) {
            PublisherRepository.replace(normalizedPublisher)
        }
        return update.copy(publisher = normalizedPublisher.toInfo()) to normalizedPublisher
    }

    private fun resolveTargets(target: Subscriber?, publisher: Publisher?): List<DeliveryTarget> {
        target?.let {
            if (it.state != EntityState.ACTIVE) return emptyList()
            return listOf(
                DeliveryTarget(
                    subscriber = it,
                    subscription = publisher?.let { stored ->
                        SubscriptionRepository.findBySubscriberAndPublisher(it.id, stored.id)
                    },
                ),
            )
        }
        if (publisher == null) return emptyList()
        return SubscriptionRepository
            .findSubscriptionsWithSubscribersByPublisherId(publisher.id)
            .filter { it.subscriber.state == EntityState.ACTIVE }
            .map { DeliveryTarget(subscriber = it.subscriber, subscription = it.subscription) }
    }

    private fun applyFilters(update: SourceUpdate, targets: List<DeliveryTarget>): List<DeliveryTarget> {
        val policyMatchedTargets = targets.filter { target ->
            target.subscription?.policy?.updateSelectors?.any { selector -> selector.matches(update) } ?: true
        }
        val subscriptionIds = policyMatchedTargets
            .filter { it.subscription?.policy?.filtersEnabled != false }
            .mapNotNull { it.subscription?.id }
        if (subscriptionIds.isEmpty()) return policyMatchedTargets

        val rulesBySubscriptionId = DynamicFilterRuleRepository.findBySubscriptionIds(subscriptionIds)
        return policyMatchedTargets.filter { target ->
            val subscription = target.subscription ?: return@filter true
            if (!subscription.policy.filtersEnabled) return@filter true
            val rules = rulesBySubscriptionId[subscription.id].orEmpty()
            rules.isEmpty() || !DynamicFilterEvaluator.isBlocked(update, rules)
        }
    }

    private fun List<DeliveryTarget>.filterSubscribedBefore(updateTime: Long): List<DeliveryTarget> {
        return filter { target ->
            val subscription = target.subscription ?: return@filter true
            updateTime >= subscription.createdAtEpochSeconds
        }
    }

    private suspend fun buildMessageBatches(template: String, update: SourceUpdate): List<MessageBatch> {
        val drawImage = if (templateRenderer.requiresDraw(template, update)) renderImage(update) else null
        return templateRenderer.render(template, update, drawImage)
    }

    private suspend fun renderImage(update: SourceUpdate): MediaRef? {
        val drawableUpdate = if (update.payload is LivePayload) update.toDrawableDynamicUpdate() else update
        return runCatching {
            runtimeImageLoader.load(drawableUpdate)
            MediaRef(uri = runtimeImageRenderer.render(drawableUpdate).toString(), kind = MediaKind.IMAGE)
        }.onFailure {
            logger.warn(it) { "绘图失败，回退为文本消息：update=${update.key.stableValue()}" }
        }.getOrNull()
    }

    private fun resolveDynamicTemplate(): String {
        return runtimeConfigProvider().templates.dynamic
    }

    private fun resolveLiveTemplate(update: SourceUpdate): String {
        update.payload as? LivePayload ?: return runtimeConfigProvider().templates.dynamic
        val templates = runtimeConfigProvider().templates
        return when (update.eventType) {
            SourceEventType.LIVE_STARTED -> templates.liveStarted
            SourceEventType.LIVE_ENDED -> templates.liveEnded
            else -> templates.dynamic
        }
    }

    private fun publishMessage(
        update: SourceUpdate,
        targets: List<DeliveryTarget>,
        batches: List<MessageBatch>,
        skipReason: String,
    ) {
        if (batches.isEmpty()) {
            logger.warn { "跳过来源更新：$skipReason，渲染后的消息为空" }
            return
        }

        val (mentionAllTargets, normalTargets) = targets.partition { it.shouldMentionAll(update) }
        publishMessageVariant(update, normalTargets, batches, "default")
        publishMessageVariant(update, mentionAllTargets, batches.withMentionAllAtTail(), "mention_all")
    }

    private fun publishMessageVariant(
        update: SourceUpdate,
        targets: List<DeliveryTarget>,
        batches: List<MessageBatch>,
        renderVariant: String,
    ) {
        if (targets.isEmpty()) return

        val message = Message(
            id = "${update.key.stableValue()}:$renderVariant",
            time = System.currentTimeMillis() / 1000,
            sourceUpdateKey = update.key,
            renderVariant = renderVariant,
            targets = targets.map { it.subscriber.address },
            batches = batches,
        )
        MessageDeliveryRepository.createPending(message)
        MessageEvent(sourcePlugin = "main", message = message).let { eventBus.broadcast(it) }
    }

    private fun DeliveryTarget.shouldMentionAll(update: SourceUpdate): Boolean {
        if (subscriber.kind != TargetKind.GROUP) return false
        val policy = subscription?.policy ?: return false
        return policy.mentionRules.any { rule ->
            rule.mode == MentionMode.MENTION_ALL && rule.selector.matches(update)
        }
    }

    private fun List<MessageBatch>.withMentionAllAtTail(): List<MessageBatch> {
        if (isEmpty()) return this
        val result = toMutableList()
        val last = result.last()
        result[result.lastIndex] = last.copy(
            content = last.content + MessageContent.MentionAll(fallbackText = ""),
        )
        return result
    }

    private fun SourceUpdate.toDrawableDynamicUpdate(): SourceUpdate {
        val live = payload as? LivePayload ?: return this
        val liveTitle = live.title.ifBlank { "Live" }
        val contentText = listOfNotNull(
            liveTitle,
            live.area?.takeIf { it.isNotBlank() },
            link?.takeIf { it.isNotBlank() },
        ).joinToString("\n")
        val card = live.cover?.let {
            CardAttachment(
                id = live.roomId,
                cardKind = "live",
                title = liveTitle,
                description = live.area.orEmpty(),
                badge = "LIVE",
                cover = it,
                link = link,
            )
        }

        return copy(
            payload = DynamicPayload(
                labels = listOf(DynamicLabel("LIVE")),
                title = liveTitle,
                content = DynamicContent.text(contentText),
                attachments = card?.let { listOf(it) }.orEmpty(),
            ),
        )
    }

    private data class DeliveryTarget(
        val subscriber: Subscriber,
        val subscription: top.colter.dynamic.core.data.Subscription?,
    )
}
