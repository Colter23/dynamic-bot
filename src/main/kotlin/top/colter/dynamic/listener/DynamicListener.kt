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
import top.colter.dynamic.link.LINK_PARSE_EVENT_LABEL

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
        imageRenderer ?: FileDynamicImageRenderer(Paths.get(startupConfig.imageCache.renderedRoot))
    }

    override suspend fun onMessage(event: DynamicEvent) {
        val dynamic = event.dynamic
        val targets = resolveTargets(event)
        if (targets.isEmpty()) {
            println("dynamic event skipped: ${dynamic.dynamicId}, reason=no_subscriber")
            return
        }

        val deliverableTargets = if (event.label == LINK_PARSE_EVENT_LABEL) {
            targets
        } else {
            applyFilters(dynamic, targets.filterSubscribedBeforeDynamic(dynamic))
        }
        if (deliverableTargets.isEmpty()) {
            println("dynamic event skipped: ${dynamic.dynamicId}, reason=filtered")
            return
        }

        println("dynamic event received: ${dynamic.dynamicId}")

        val template = resolveTemplate(dynamic)
        val chain = buildMessageChain(template, dynamic)
        if (chain.isEmpty()) {
            println("dynamic event skipped: ${dynamic.dynamicId}, reason=empty_message")
            return
        }

        val message = Message(
            id = messageId(dynamic),
            time = System.currentTimeMillis() / 1000,
            targets = deliverableTargets.map { it.subscriber.toMessageTarget() },
            chain = chain,
        )
        MessageDeliveryRepository.createPending(message)

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
                println(
                    "dynamic event target filtered: ${dynamic.dynamicId}, " +
                        "subscriberId=${target.subscriber.id}, subscriptionId=${target.subscription?.id}"
                )
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
            System.err.println(
                "dynamic draw failed: platformId=${dynamic.platform.id}, publisherId=${dynamic.publisher.id}, " +
                    "publisherExternalId=${dynamic.publisher.externalId}, dynamicId=${dynamic.dynamicId}, " +
                    "error=${it::class.qualifiedName}: ${it.message}"
            )
            it.printStackTrace(System.err)
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
