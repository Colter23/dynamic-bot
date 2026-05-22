package top.colter.dynamic.listener

import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.repository.PublisherTemplateRepository
import top.colter.dynamic.core.repository.SubscribeRepository

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

        MessageEvent(
            source = "main",
            message = Message(
                id = messageId(dynamic),
                time = System.currentTimeMillis() / 1000,
                subscriber = subscribers,
                chain = listOf(buildMessageChain(dynamic)),
            ),
        ).broadcast()
    }

    private fun resolveSubscribers(event: DynamicEvent): List<Subscriber> {
        event.target?.let { return listOf(it).filter { subscriber -> subscriber.state == 1 } }
        return SubscribeRepository
            .findSubscribersByPublisherId(event.dynamic.publisher.id.toString())
            .filter { it.state == 1 }
    }

    private fun buildMessageChain(dynamic: Dynamic): MessageChain {
        val contents = mutableListOf<MessageContent>()
        renderImage(dynamic)?.let { imagePath ->
            contents += MessageContent.Image(
                text = "",
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
        val templateName = PublisherTemplateRepository.findTemplateNameByPublisherId(dynamic.publisher.id.toString())
        return templateName
            ?.let { config.templates[it] }
            ?: config.templates[MainDynamicConfig.DEFAULT_TEMPLATE_NAME]
            ?: MainDynamicConfig.DEFAULT_TEMPLATE
    }

    private fun messageId(dynamic: Dynamic): Long {
        return "${dynamic.platform.id}:${dynamic.publisher.id}:${dynamic.dynamicId}".hashCode().toLong() and Long.MAX_VALUE
    }
}
