package top.colter.dynamic.listener

import java.util.UUID
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.MessageEvent
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.draw.DefaultDynamicDrawService
import top.colter.dynamic.draw.DynamicDrawService
import top.colter.dynamic.filter.DynamicFilterEvaluator
import top.colter.dynamic.repository.DynamicFilterRuleRepository
import top.colter.dynamic.repository.MessageEnqueueResult
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.link.LINK_PARSE_EVENT_LABEL

private val logger = loggerFor<SourceUpdateProcessor>()

public class SourceUpdateProcessor(
    config: MainDynamicConfig? = null,
    configProvider: (() -> MainDynamicConfig)? = null,
    private val configService: ConfigService = YamlConfigService(),
    private val eventBus: EventBus = EventBus(),
    private val templateRenderer: PushTemplateRenderer = PushTemplateRenderer(),
    drawService: DynamicDrawService? = null,
    private val broadcastMessages: Boolean = true,
    private val onDeliveriesQueued: suspend () -> Unit = {},
) {
    private val fixedConfig: MainDynamicConfig by lazy {
        config ?: configService.loadOrCreate(MainDynamicConfig.CONFIG_ID) { MainDynamicConfig() }
    }
    private val runtimeConfigProvider: () -> MainDynamicConfig = configProvider ?: { fixedConfig }
    private val runtimeDrawService: DynamicDrawService by lazy {
        drawService ?: DefaultDynamicDrawService(configProvider = runtimeConfigProvider)
    }

    public suspend fun process(request: SourceUpdatePublishRequest): SourceUpdatePublishResult {
        return runCatching {
            logger.info {
                "开始处理来源更新：source=${request.sourcePlugin}，event=${request.update.eventType.value}，update=${request.update.key.stableValue()}"
            }
            when (request.update.payload) {
                is DynamicPayload -> handleDynamic(request, request.update)
                is LivePayload -> handleLive(request, request.update)
            }
        }.getOrElse { error ->
            logger.error(error) { "来源更新处理失败：update=${request.update.key.stableValue()}" }
            SourceUpdatePublishResult.failed(error.message ?: "来源更新处理失败")
        }
    }

    private suspend fun handleDynamic(request: SourceUpdatePublishRequest, update: SourceUpdate): SourceUpdatePublishResult {
        val (normalizedUpdate, storedPublisher) = normalizePublisher(update)
        val targets = resolveTargets(request.deliveryTarget, storedPublisher)
        if (targets.isEmpty()) {
            logger.info { "来源更新无可投递目标：update=${normalizedUpdate.key.stableValue()}" }
            return SourceUpdatePublishResult.ignored("没有可投递目标")
        }

        val deliverableTargets = if (request.deliveryTag == LINK_PARSE_EVENT_LABEL) {
            targets
        } else {
            applySubscriptionRules(normalizedUpdate, targets.filterSubscribedBefore(normalizedUpdate.occurredAtEpochSeconds))
        }
        logger.info {
            "来源更新订阅匹配完成：update=${normalizedUpdate.key.stableValue()}，候选目标=${targets.size}，可投递=${deliverableTargets.size}"
        }
        if (deliverableTargets.isEmpty()) {
            logger.info { "来源更新被订阅规则或过滤规则拦截：update=${normalizedUpdate.key.stableValue()}" }
            return SourceUpdatePublishResult.ignored("所有目标均未订阅该事件、被过滤或订阅时间晚于动态时间")
        }

        val chain = buildMessageBatches(resolveDynamicTemplate(), normalizedUpdate, storedPublisher)
        return publishMessage(
            update = normalizedUpdate,
            targets = deliverableTargets,
            batches = chain,
            skipReason = "update=${normalizedUpdate.key.stableValue()}",
            messageIdNonce = request.linkParseMessageIdNonce(),
        )
    }

    private suspend fun handleLive(request: SourceUpdatePublishRequest, update: SourceUpdate): SourceUpdatePublishResult {
        val (normalizedUpdate, storedPublisher) = normalizePublisher(update)
        val targets = resolveTargets(request.deliveryTarget, storedPublisher)
            .filterSubscribedBefore(normalizedUpdate.occurredAtEpochSeconds)
            .let { applySubscriptionRules(normalizedUpdate, it) }
        logger.info {
            "直播来源更新订阅匹配完成：update=${normalizedUpdate.key.stableValue()}，可投递=${targets.size}"
        }
        if (targets.isEmpty()) {
            logger.info { "直播来源更新无可投递目标：update=${normalizedUpdate.key.stableValue()}" }
            return SourceUpdatePublishResult.ignored("没有可投递目标")
        }

        val chain = buildMessageBatches(resolveLiveTemplate(normalizedUpdate), normalizedUpdate, storedPublisher)
        return publishMessage(
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
            avatarBadgeKey = incoming.avatarBadgeKey,
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

    private fun applySubscriptionRules(update: SourceUpdate, targets: List<DeliveryTarget>): List<DeliveryTarget> {
        val policyMatchedTargets = targets.filter { target ->
            target.subscription?.policy?.accepts(update) ?: true
        }
        if (update.payload !is DynamicPayload) return policyMatchedTargets

        val subscriptionIds = policyMatchedTargets
            .mapNotNull { it.subscription?.id }
        if (subscriptionIds.isEmpty()) return policyMatchedTargets

        val rulesBySubscriptionId = DynamicFilterRuleRepository.findBySubscriptionIds(subscriptionIds)
        return policyMatchedTargets.filter { target ->
            val subscription = target.subscription ?: return@filter true
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

    private suspend fun buildMessageBatches(
        template: String,
        update: SourceUpdate,
        storedPublisher: Publisher?,
    ): List<MessageBatch> {
        val drawImage = if (templateRenderer.requiresDraw(template, update)) {
            renderImage(update, storedPublisher)
        } else {
            null
        }
        return templateRenderer.render(template, update, drawImage)
    }

    private suspend fun renderImage(update: SourceUpdate, storedPublisher: Publisher?): MediaRef? {
        return runCatching {
            runtimeDrawService.render(update, storedPublisher)
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

    private suspend fun publishMessage(
        update: SourceUpdate,
        targets: List<DeliveryTarget>,
        batches: List<MessageBatch>,
        skipReason: String,
        messageIdNonce: String? = null,
    ): SourceUpdatePublishResult {
        if (batches.isEmpty()) {
            logger.warn { "跳过来源更新：$skipReason，渲染后的消息为空" }
            return SourceUpdatePublishResult.ignored("渲染后的消息为空")
        }

        val (mentionAllTargets, normalTargets) = targets.partition { it.shouldMentionAll(update) }
        val results = listOfNotNull(
            publishMessageVariant(update, normalTargets, batches, "default", messageIdNonce),
            publishMessageVariant(update, mentionAllTargets, batches.withMentionAllAtTail(), "mention_all", messageIdNonce),
        )
        val newDeliveryCount = results.sumOf { it.newDeliveries.size }
        return when {
            newDeliveryCount > 0 -> {
                logger.info {
                    "来源更新已创建投递任务：update=${update.key.stableValue()}，消息变体=${results.size}，新增投递=$newDeliveryCount"
                }
                onDeliveriesQueued()
                SourceUpdatePublishResult.enqueued(newDeliveryCount)
            }
            results.isNotEmpty() -> {
                logger.info { "来源更新投递任务已存在：update=${update.key.stableValue()}" }
                SourceUpdatePublishResult.duplicate()
            }
            else -> SourceUpdatePublishResult.ignored("没有可投递目标")
        }
    }

    private fun publishMessageVariant(
        update: SourceUpdate,
        targets: List<DeliveryTarget>,
        batches: List<MessageBatch>,
        renderVariant: String,
        messageIdNonce: String?,
    ): MessageEnqueueResult? {
        if (targets.isEmpty()) return null

        val message = Message(
            id = buildMessageId(update, renderVariant, messageIdNonce),
            time = System.currentTimeMillis() / 1000,
            sourceUpdateKey = update.key,
            renderVariant = renderVariant,
            targets = targets.map { it.subscriber.address },
            batches = batches,
        )
        val result = MessageDeliveryRepository.enqueue(message)
        if (result.newDeliveries.isNotEmpty()) {
            logger.info {
                "消息已入队：messageId=${message.id}，variant=$renderVariant，新增投递=${result.newDeliveries.size}，已存在=${result.existingDeliveries.size}，目标=${message.targets.targetSummary()}"
            }
        } else {
            logger.debug {
                "消息入队跳过重复投递：messageId=${message.id}，variant=$renderVariant，已存在=${result.existingDeliveries.size}"
            }
        }
        if (broadcastMessages && result.newDeliveries.isNotEmpty()) {
            val broadcastMessage = message.copy(targets = result.newDeliveries.map { it.target })
            MessageEvent(sourcePlugin = "main", message = broadcastMessage).let { eventBus.broadcast(it) }
        }
        return result
    }

    private fun SourceUpdatePublishRequest.linkParseMessageIdNonce(): String? {
        if (deliveryTag != LINK_PARSE_EVENT_LABEL) return null
        return "link-parse:${System.currentTimeMillis()}:${UUID.randomUUID()}"
    }

    private fun buildMessageId(
        update: SourceUpdate,
        renderVariant: String,
        nonce: String?,
    ): String {
        val base = "${update.key.stableValue()}:$renderVariant"
        return if (nonce == null) base else "$base:$nonce"
    }

    private fun DeliveryTarget.shouldMentionAll(update: SourceUpdate): Boolean {
        if (subscriber.kind != TargetKind.GROUP) return false
        val policy = subscription?.policy ?: return false
        return policy.shouldMentionAll(update)
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

    private fun List<top.colter.dynamic.core.data.TargetAddress>.targetSummary(): String {
        val visible = take(5).joinToString(",") { it.stableValue() }
        return if (size > 5) "$visible...+${size - 5}" else visible
    }

    private data class DeliveryTarget(
        val subscriber: Subscriber,
        val subscription: top.colter.dynamic.core.data.Subscription?,
    )
}
