package top.colter.dynamic.listener

import java.nio.file.Paths
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.filter.DynamicFilterEvaluator
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.repository.PublisherTemplateRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.link.LINK_PARSE_EVENT_LABEL

private val logger = loggerFor<DynamicListener>()

public class DynamicListener(
    config: MainDynamicConfig? = null,
    configProvider: (() -> MainDynamicConfig)? = null,
    private val configService: ConfigService = DefaultConfigService,
    private val templateRenderer: DynamicMessageTemplateRenderer = DynamicMessageTemplateRenderer(),
    imageLoader: DynamicImageLoader? = null,
    imageRenderer: DynamicImageRenderer? = null,
) : Listener<DynamicEvent> {
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

    override suspend fun onMessage(event: DynamicEvent) {
        val dynamic = event.dynamic
        val targets = resolveTargets(event)
        if (targets.isEmpty()) {
            logger.debug { "跳过动态：dynamicId=${dynamic.dynamicId}，原因=没有订阅目标" }
            return
        }

        val deliverableTargets = if (event.label == LINK_PARSE_EVENT_LABEL) {
            targets
        } else {
            applyFilters(dynamic, targets.filterSubscribedBeforeDynamic(dynamic))
        }
        if (deliverableTargets.isEmpty()) {
            logger.debug { "跳过动态：dynamicId=${dynamic.dynamicId}，原因=过滤后无目标" }
            return
        }

        logger.info { "收到动态：platform=${dynamic.platform.id}，dynamicId=${dynamic.dynamicId}，目标数=${deliverableTargets.size}" }

        val template = resolveTemplate(dynamic)
        val chain = buildMessageChain(template, dynamic)
        if (chain.isEmpty()) {
            logger.warn { "跳过动态：dynamicId=${dynamic.dynamicId}，原因=模板渲染为空" }
            return
        }

        val message = Message(
            id = messageId(dynamic),
            time = System.currentTimeMillis() / 1000,
            targets = deliverableTargets.map { it.subscriber.toMessageTarget() },
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

    private fun resolveTargets(event: DynamicEvent): List<DeliveryTarget> {
        event.target?.let { target ->
            if (target.state != EntityState.ACTIVE) return emptyList()
            return listOf(
                DeliveryTarget(
                    subscriber = target,
                    subscription = SubscriptionRepository.findBySubscriberAndPublisher(
                        subscriberId = target.id,
                        publisherId = event.dynamic.publisher.id,
                    ),
                )
            )
        }
        return SubscriptionRepository
            .findSubscriptionsWithSubscribersByPublisherId(event.dynamic.publisher.id)
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

    private fun List<DeliveryTarget>.filterSubscribedBeforeDynamic(dynamic: Dynamic): List<DeliveryTarget> {
        return filter { target ->
            val subscription = target.subscription ?: return@filter true
            dynamic.time >= subscription.createdAtEpochSeconds
        }
    }

    private suspend fun buildMessageChain(template: String, dynamic: Dynamic): List<MessageChain> {
        val drawImage = if (templateRenderer.requiresDraw(template)) renderImage(dynamic) else null
        return templateRenderer.render(template, dynamic, drawImage)
    }

    private suspend fun renderImage(dynamic: Dynamic): LazyImage? {
        return runCatching {
            runtimeImageLoader.load(dynamic)
            LazyImage(runtimeImageRenderer.render(dynamic).toString())
        }.onFailure {
            logger.warn(it) {
                "动态绘图失败，已降级为纯文本：platform=${dynamic.platform.id}，publisher=${dynamic.publisher.externalId}，dynamicId=${dynamic.dynamicId}"
            }
        }.getOrNull()
    }

    private fun resolveTemplate(dynamic: Dynamic): String {
        val runtimeConfig = runtimeConfigProvider()
        val templateName = PublisherTemplateRepository.findTemplateName(
            publisherId = dynamic.publisher.id,
            platformId = dynamic.platform.id,
            dynamicType = dynamicType(dynamic),
        )
        return templateName
            ?.let { runtimeConfig.templates[it] }
            ?: runtimeConfig.templates[MainDynamicConfig.DEFAULT_TEMPLATE_NAME]
            ?: MainDynamicConfig.DEFAULT_TEMPLATE
    }

    private fun dynamicType(dynamic: Dynamic): String {
        val media = dynamic.media
        return when {
            media?.video != null -> "video"
            !media?.pics.isNullOrEmpty() -> "image"
            media?.card != null || media?.smallCard != null || media?.miniCard != null -> "card"
            else -> "text"
        }
    }

    private fun messageId(dynamic: Dynamic): Long {
        return "${dynamic.platform.id}:${dynamic.publisher.id}:${dynamic.dynamicId}".hashCode().toLong() and Long.MAX_VALUE
    }

    private data class DeliveryTarget(
        val subscriber: Subscriber,
        val subscription: Subscription?,
    )
}
