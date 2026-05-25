package top.colter.dynamic.listener

import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.repository.PublisherTemplateRepository
import top.colter.dynamic.core.repository.SubscriptionRepository

public class DynamicListener(
    config: MainDynamicConfig? = null,
    private val configService: ConfigService = DefaultConfigService,
    private val templateRenderer: DynamicTemplateRenderer = DynamicTemplateRenderer(),
    private val imageLoader: DynamicImageLoader = UrlDynamicImageLoader(),
    private val imageRenderer: DynamicImageRenderer = FileDynamicImageRenderer(),
) : Listener<DynamicEvent> {
    private val config: MainDynamicConfig by lazy {
        config ?: configService.loadOrCreate(MainDynamicConfig.CONFIG_ID) { MainDynamicConfig() }
    }

    override suspend fun onMessage(event: DynamicEvent) {
        val dynamic = event.dynamic
        val subscribers = resolveSubscribers(event)
        if (subscribers.isEmpty()) {
            println("dynamic event skipped: ${dynamic.dynamicId}, reason=no_subscriber")
            return
        }

        println("dynamic event received: ${dynamic.dynamicId}")

        val message = Message(
            id = messageId(dynamic),
            time = System.currentTimeMillis() / 1000,
            targets = subscribers.map { it.toMessageTarget() },
            chain = listOf(buildMessageChain(dynamic)),
        )
        MessageDeliveryRepository.createPending(message)

        MessageEvent(
            source = "main",
            message = message,
        ).broadcast()
    }

    private fun resolveSubscribers(event: DynamicEvent): List<Subscriber> {
        event.target?.let { return listOf(it).filter { subscriber -> subscriber.state == EntityState.ACTIVE } }
        return SubscriptionRepository
            .findSubscribersByPublisherId(event.dynamic.publisher.id)
            .filter { it.state == EntityState.ACTIVE }
    }

    private fun buildMessageChain(dynamic: Dynamic): MessageChain {
        val contents = mutableListOf<MessageContent>()
        renderImage(dynamic)?.let { imagePath ->
            contents += MessageContent.Image(
                fallbackText = "",
                image = LazyImage(imagePath),
            )
        }
        val text = templateRenderer.render(resolveTemplate(dynamic), dynamic)
        if (text.isNotBlank()) {
            contents += MessageContent.Text(text)
        }
        return MessageChain(contents)
    }

    private fun renderImage(dynamic: Dynamic): String? {
        return runCatching {
            imageLoader.load(dynamic)
            imageRenderer.render(dynamic).toString()
        }.onFailure {
            println("dynamic draw failed: ${dynamic.dynamicId}, error=${it.message}")
        }.getOrNull()
    }

    private fun resolveTemplate(dynamic: Dynamic): String {
        val templateName = PublisherTemplateRepository.findTemplateName(
            publisherId = dynamic.publisher.id,
            platformId = dynamic.platform.id,
            dynamicType = dynamicType(dynamic),
        )
        return templateName
            ?.let { config.templates[it] }
            ?: config.templates[MainDynamicConfig.DEFAULT_TEMPLATE_NAME]
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
}
