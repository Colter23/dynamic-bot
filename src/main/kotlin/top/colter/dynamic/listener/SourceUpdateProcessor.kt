package top.colter.dynamic.listener

import java.util.UUID
import kotlinx.coroutines.CancellationException
import top.colter.dynamic.MainConfigForms
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.MessageDeliveryPolicy
import top.colter.dynamic.core.data.MessageImportance
import top.colter.dynamic.core.data.MessageRecordPolicy
import top.colter.dynamic.core.data.OutboundMessageKind
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.MessageEvent
import top.colter.dynamic.core.event.PublisherPersistenceMode
import top.colter.dynamic.core.event.SourceUpdateDeliveryMode
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.deliveryMode
import top.colter.dynamic.core.plugin.OutboundMessagePublishRequest
import top.colter.dynamic.draw.DefaultDynamicDrawService
import top.colter.dynamic.draw.DynamicDrawService
import top.colter.dynamic.filter.DynamicFilterEvaluator
import top.colter.dynamic.repository.DynamicFilterRuleRepository
import top.colter.dynamic.repository.PublisherLiveRecordRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SourceUpdateSnapshotRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.repository.isDeliveryAllowed
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.message.OutboundMessagePublishResult
import top.colter.dynamic.message.OutboundMessageService

private val logger = loggerFor<SourceUpdateProcessor>()

public class SourceUpdateProcessor(
    config: MainDynamicConfig? = null,
    configProvider: (() -> MainDynamicConfig)? = null,
    private val configService: ConfigService = YamlConfigService(),
    private val eventBus: EventBus = EventBus(),
    private val templateRenderer: PushTemplateRenderer = PushTemplateRenderer(),
    drawService: DynamicDrawService? = null,
    private val broadcastMessages: Boolean = true,
    onDeliveriesQueued: suspend () -> Unit = {},
    private val outboundMessageService: OutboundMessageService = OutboundMessageService(
        onMessagesQueued = onDeliveriesQueued,
    ),
) {
    private val fixedConfig: MainDynamicConfig by lazy {
        config ?: configService.loadOrCreate(MainDynamicConfig.CONFIG_ID, MainConfigForms.migrations) {
            MainDynamicConfig()
        }
    }
    private val runtimeConfigProvider: () -> MainDynamicConfig = configProvider ?: { fixedConfig }
    private val runtimeDrawService: DynamicDrawService by lazy {
        drawService ?: DefaultDynamicDrawService(configProvider = runtimeConfigProvider)
    }

    public suspend fun process(request: SourceUpdatePublishRequest): SourceUpdatePublishResult {
        return try {
            logger.debug {
                "开始处理来源更新：source=${request.sourcePlugin}，event=${request.update.eventType.value}，update=${request.update.key.stableValue()}"
            }
            when (request.update.payload) {
                is DynamicPayload -> handleDynamic(request, request.update)
                is LivePayload -> handleLive(request, request.update)
            }
        } catch (e: CancellationException) {
            // 取消要原样向上抛：这里吞掉会让插件停止和检测超时都打不断这条链路。
            throw e
        } catch (error: Throwable) {
            logger.error(error) { "来源更新处理失败：update=${request.update.key.stableValue()}" }
            SourceUpdatePublishResult.failed(error.message ?: "来源更新处理失败")
        }
    }

    private suspend fun handleDynamic(request: SourceUpdatePublishRequest, update: SourceUpdate): SourceUpdatePublishResult {
        val (normalizedUpdate, storedPublisher) = normalizePublisher(update, request.publisherPersistenceMode)
        val deliveryMode = request.deliveryMode
        val requireActiveTarget = !deliveryMode.isOnDemandPreview
        val targets = resolveTargets(
            target = request.deliveryTarget,
            targetAddress = request.deliveryTargetAddress,
            publisher = storedPublisher,
            requireActiveTarget = requireActiveTarget,
        )
        if (targets.isEmpty()) {
            logger.info { "来源更新无可投递目标：update=${normalizedUpdate.key.stableValue()}" }
            return SourceUpdatePublishResult.ignored("没有可投递目标")
        }

        val deliverableTargets = if (deliveryMode.isOnDemandPreview) {
            targets
        } else {
            applySubscriptionRules(normalizedUpdate, targets.filterSubscribedBefore(normalizedUpdate.occurredAtEpochSeconds))
        }
        logger.debug {
            "来源更新订阅匹配完成：update=${normalizedUpdate.key.stableValue()}，候选目标=${targets.size}，可投递=${deliverableTargets.size}"
        }
        if (deliverableTargets.isEmpty()) {
            logger.info { "来源更新被订阅规则或过滤规则拦截：update=${normalizedUpdate.key.stableValue()}" }
            return SourceUpdatePublishResult.ignored("所有目标均未订阅该事件、被过滤或订阅时间晚于动态时间")
        }

        val chain = buildMessageBatches(resolveDynamicTemplate(), normalizedUpdate, storedPublisher)
        return publishMessage(
            sourcePlugin = request.sourcePlugin,
            update = normalizedUpdate,
            targets = deliverableTargets,
            batches = chain,
            skipReason = "update=${normalizedUpdate.key.stableValue()}",
            messageIdNonce = request.onDemandPreviewMessageIdNonce(deliveryMode),
            correlationId = request.correlationId,
            requireActiveTarget = requireActiveTarget,
            deliveryMode = deliveryMode,
        )
    }

    private suspend fun handleLive(request: SourceUpdatePublishRequest, update: SourceUpdate): SourceUpdatePublishResult {
        val (normalizedUpdate, storedPublisher) = normalizePublisher(update, request.publisherPersistenceMode)
        storedPublisher?.let { publisher ->
            PublisherLiveRecordRepository.recordLiveEvent(publisher.id, normalizedUpdate)
        }
        val targets = resolveTargets(request.deliveryTarget, request.deliveryTargetAddress, storedPublisher)
            .filterSubscribedBefore(normalizedUpdate.occurredAtEpochSeconds)
            .let { applySubscriptionRules(normalizedUpdate, it) }
        logger.debug {
            "直播来源更新订阅匹配完成：update=${normalizedUpdate.key.stableValue()}，可投递=${targets.size}"
        }
        if (targets.isEmpty()) {
            logger.info { "直播来源更新无可投递目标：update=${normalizedUpdate.key.stableValue()}" }
            return SourceUpdatePublishResult.ignored("没有可投递目标")
        }

        val chain = buildMessageBatches(resolveLiveTemplate(normalizedUpdate), normalizedUpdate, storedPublisher)
        return publishMessage(
            sourcePlugin = request.sourcePlugin,
            update = normalizedUpdate,
            targets = targets,
            batches = chain,
            skipReason = "update=${normalizedUpdate.key.stableValue()}",
            correlationId = request.correlationId,
        )
    }

    private fun normalizePublisher(
        update: SourceUpdate,
        persistenceMode: PublisherPersistenceMode,
    ): Pair<SourceUpdate, Publisher?> {
        val incoming = update.publisher
        val stored = PublisherRepository.findByKey(incoming.key) ?: return update to null
        val normalizedPublisher = stored.copy(
            key = incoming.key,
            name = incoming.name.ifBlank { stored.name },
            avatarBadgeKey = incoming.avatarBadgeKey ?: stored.avatarBadgeKey,
            avatar = incoming.avatar.takeIf { it.uri.isNotBlank() } ?: stored.avatar,
            pendant = incoming.pendant ?: stored.pendant,
            banner = incoming.banner ?: stored.banner,
        )
        if (persistenceMode == PublisherPersistenceMode.UPSERT && normalizedPublisher != stored) {
            PublisherRepository.replace(normalizedPublisher)
        }
        return update.copy(publisher = normalizedPublisher.toInfo()) to normalizedPublisher
    }

    private fun resolveTargets(
        target: Subscriber?,
        targetAddress: TargetAddress?,
        publisher: Publisher?,
        requireActiveTarget: Boolean = true,
    ): List<DeliveryTarget> {
        target?.let {
            if (!it.canReceiveDelivery(requireActiveTarget)) return emptyList()
            return listOf(
                DeliveryTarget(
                    address = it.address,
                    subscriber = it,
                    subscription = publisher?.let { stored ->
                        SubscriptionRepository.findBySubscriberAndPublisher(it.id, stored.id)
                    },
                ),
            )
        }
        targetAddress?.let {
            val subscriber = SubscriberRepository.findByAddress(it)
            if (subscriber != null) {
                if (!subscriber.canReceiveDelivery(requireActiveTarget)) return emptyList()
                return listOf(
                    DeliveryTarget(
                        address = subscriber.address,
                        subscriber = subscriber,
                        subscription = publisher?.let { stored ->
                            SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, stored.id)
                        },
                    ),
                )
            }
            return listOf(DeliveryTarget(address = it, subscriber = null, subscription = null))
        }
        if (publisher == null) return emptyList()
        return SubscriptionRepository
            .findSubscriptionsWithSubscribersByPublisherId(publisher.id)
            .filter { it.subscriber.isDeliveryAllowed }
            .map { DeliveryTarget(address = it.subscriber.address, subscriber = it.subscriber, subscription = it.subscription) }
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
        // 更新时间未知/无效（来源未提供 pubTs/time）时不应用订阅时间闸门，否则会对所有真实订阅误丢动态。
        if (updateTime <= 0L) return this
        return filter { target ->
            val subscription = target.subscription ?: return@filter true
            updateTime >= subscription.createdAtEpochSeconds
        }
    }

    private suspend fun buildMessageBatches(
        template: String,
        update: SourceUpdate,
        storedPublisher: Publisher?,
    ): RenderedPushBatches {
        val drawImage = if (templateRenderer.requiresDraw(template, update)) {
            renderImage(update, storedPublisher)
        } else {
            null
        }
        val normalBatches = templateRenderer.render(template, update, drawImage)
        val mentionAllBatches = if (PushTemplateRenderer.hasMentionAllPlaceholder(template)) {
            templateRenderer.render(template, update, drawImage, mentionAll = true)
        } else {
            normalBatches.withMentionAllAtTail()
        }
        return RenderedPushBatches(normal = normalBatches, mentionAll = mentionAllBatches)
    }

    private suspend fun renderImage(update: SourceUpdate, storedPublisher: Publisher?): MediaRef? {
        return try {
            runtimeDrawService.render(update, storedPublisher)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn(t) { "绘图失败，回退为文本消息：update=${update.key.stableValue()}" }
            null
        }
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
        sourcePlugin: String,
        update: SourceUpdate,
        targets: List<DeliveryTarget>,
        batches: RenderedPushBatches,
        skipReason: String,
        messageIdNonce: String? = null,
        correlationId: String? = null,
        requireActiveTarget: Boolean = true,
        deliveryMode: SourceUpdateDeliveryMode = SourceUpdateDeliveryMode.SUBSCRIPTION,
    ): SourceUpdatePublishResult {
        val (mentionAllTargets, normalTargets) = if (deliveryMode.isOnDemandPreview) {
            emptyList<DeliveryTarget>() to targets
        } else {
            targets.partition { it.shouldMentionAll(update) }
        }
        if ((normalTargets.isEmpty() || batches.normal.isEmpty()) &&
            (mentionAllTargets.isEmpty() || batches.mentionAll.isEmpty())
        ) {
            logger.warn { "跳过来源更新：$skipReason，渲染后的消息为空" }
            return SourceUpdatePublishResult.ignored("渲染后的消息为空")
        }

        SourceUpdateSnapshotRepository.upsert(sourcePlugin, update)

        val results = listOfNotNull(
            publishMessageVariant(
                sourcePlugin,
                update,
                normalTargets,
                batches.normal,
                "default",
                messageIdNonce,
                correlationId,
                requireActiveTarget,
                deliveryMode,
            ),
            publishMessageVariant(
                sourcePlugin,
                update,
                mentionAllTargets,
                batches.mentionAll,
                "mention_all",
                messageIdNonce,
                correlationId,
                requireActiveTarget,
                deliveryMode,
            ),
        )
        val newDeliveryCount = results.sumOf { it.newDeliveries.size }
        return when {
            newDeliveryCount > 0 -> {
                logger.info {
                    "来源更新已创建投递任务：update=${update.key.stableValue()}，消息变体=${results.size}，新增投递=$newDeliveryCount"
                }
                SourceUpdatePublishResult.enqueued(newDeliveryCount)
            }
            results.isNotEmpty() -> {
                logger.debug { "来源更新投递任务已存在：update=${update.key.stableValue()}" }
                SourceUpdatePublishResult.duplicate()
            }
            else -> SourceUpdatePublishResult.ignored("没有可投递目标")
        }
    }

    private suspend fun publishMessageVariant(
        sourcePlugin: String,
        update: SourceUpdate,
        targets: List<DeliveryTarget>,
        batches: List<MessageBatch>,
        renderVariant: String,
        messageIdNonce: String?,
        correlationId: String?,
        requireActiveTarget: Boolean = true,
        deliveryMode: SourceUpdateDeliveryMode = SourceUpdateDeliveryMode.SUBSCRIPTION,
    ): OutboundMessagePublishResult? {
        if (targets.isEmpty() || batches.isEmpty()) return null

        val result = outboundMessageService.publish(
            OutboundMessagePublishRequest(
                sourcePlugin = sourcePlugin,
                messageId = buildMessageId(update, renderVariant, messageIdNonce),
                sourceUpdateKey = update.key,
                targets = targets.map { it.address },
                batches = batches,
                renderVariant = renderVariant,
                kind = deliveryMode.outboundMessageKind(),
                importance = deliveryMode.messageImportance(),
                recordPolicy = deliveryMode.recordPolicy(),
                correlationId = correlationId?.trim()?.takeIf { it.isNotBlank() },
                deliveryPolicy = MessageDeliveryPolicy(requireActiveTarget = requireActiveTarget),
            ),
        )
        if (result.newDeliveries.isNotEmpty()) {
            logger.debug {
                "消息已入队：messageId=${result.message.id}，variant=$renderVariant，新增投递=${result.newDeliveries.size}，已存在=${result.existingDeliveries.size}，目标=${result.message.targets.targetSummary()}"
            }
        } else {
            logger.debug {
                "消息入队跳过重复投递：messageId=${result.message.id}，variant=$renderVariant，已存在=${result.existingDeliveries.size}"
            }
        }
        if (broadcastMessages && result.newDeliveries.isNotEmpty()) {
            val broadcastMessage = result.message.copy(targets = result.newDeliveries.map { it.target })
            MessageEvent(sourcePlugin = "main", message = broadcastMessage).let { eventBus.broadcast(it) }
        }
        return result
    }

    private fun SourceUpdatePublishRequest.onDemandPreviewMessageIdNonce(
        deliveryMode: SourceUpdateDeliveryMode,
    ): String? {
        if (!deliveryMode.isOnDemandPreview) return null
        val tag = deliveryTag?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "$tag:${System.currentTimeMillis()}:${UUID.randomUUID()}"
    }

    private fun SourceUpdateDeliveryMode.outboundMessageKind(): OutboundMessageKind {
        return when (this) {
            SourceUpdateDeliveryMode.SUBSCRIPTION -> OutboundMessageKind.SOURCE_UPDATE
            SourceUpdateDeliveryMode.LINK_PREVIEW -> OutboundMessageKind.LINK_RESULT
            SourceUpdateDeliveryMode.MANUAL_QUERY -> OutboundMessageKind.INTERACTION_REPLY
        }
    }

    private fun SourceUpdateDeliveryMode.messageImportance(): MessageImportance {
        return when (this) {
            SourceUpdateDeliveryMode.SUBSCRIPTION,
            SourceUpdateDeliveryMode.MANUAL_QUERY -> MessageImportance.NORMAL
            SourceUpdateDeliveryMode.LINK_PREVIEW -> MessageImportance.LOW
        }
    }

    private fun SourceUpdateDeliveryMode.recordPolicy(): MessageRecordPolicy {
        return when (this) {
            SourceUpdateDeliveryMode.SUBSCRIPTION -> MessageRecordPolicy.Durable
            SourceUpdateDeliveryMode.LINK_PREVIEW,
            SourceUpdateDeliveryMode.MANUAL_QUERY -> MessageRecordPolicy.Transient(
                retentionSeconds = LINK_RESULT_RETENTION_SECONDS,
            )
        }
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
        if (address.kind != TargetKind.GROUP) return false
        val policy = subscription?.policy ?: return false
        return policy.shouldMentionAll(update)
    }

    private fun Subscriber.canReceiveDelivery(requireActiveTarget: Boolean): Boolean {
        if (state.blocksInbound) return false
        return !requireActiveTarget || state.allowsActiveDelivery
    }

    private fun List<MessageBatch>.withMentionAllAtTail(): List<MessageBatch> {
        if (isEmpty()) return this
        val result = toMutableList()
        val last = result.last()
        result[result.lastIndex] = last.copy(
            content = last.content.withMentionAllOnNewLine(),
        )
        return result
    }

    private fun List<MessageContent>.withMentionAllOnNewLine(): List<MessageContent> {
        val result = toMutableList()
        when (val last = result.lastOrNull()) {
            is MessageContent.Text -> {
                result[result.lastIndex] = last.copy(fallbackText = last.fallbackText.ensureTrailingLineBreak())
            }
            null -> Unit
            else -> result += MessageContent.Text("\n")
        }
        result += MessageContent.MentionAll(fallbackText = "")
        return result
    }

    private fun String.ensureTrailingLineBreak(): String {
        if (endsWith('\n') || endsWith('\r')) return this
        return "$this\n"
    }

    private fun List<top.colter.dynamic.core.data.TargetAddress>.targetSummary(): String {
        val visible = take(5).joinToString(",") { it.stableValue() }
        return if (size > 5) "$visible...+${size - 5}" else visible
    }

    private data class DeliveryTarget(
        val address: TargetAddress,
        val subscriber: Subscriber?,
        val subscription: top.colter.dynamic.core.data.Subscription?,
    )

    private data class RenderedPushBatches(
        val normal: List<MessageBatch>,
        val mentionAll: List<MessageBatch>,
    )

    private companion object {
        private const val LINK_RESULT_RETENTION_SECONDS: Long = 7L * 24L * 60L * 60L
    }
}
