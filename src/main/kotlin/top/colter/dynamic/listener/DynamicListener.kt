package top.colter.dynamic.listener

import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.broadcast

public class DynamicListener : Listener<DynamicEvent> {
    override suspend fun onMessage(event: DynamicEvent) {
        val publisher = event.dynamic.publisher

        println("接收到动态：" + event.dynamic.dynamicId)

        MessageEvent(
            source = "main",
            message = Message(
                platform = event.target.platform,
                name = publisher.name ?: "unknown",
                uid = publisher.userId ?: "",
                did = event.dynamic.dynamicId,
                time = event.dynamic.time.toString(),
                content = event.dynamic.content?.text ?: event.dynamic.title ?: "",
                link = event.dynamic.link,
                image = "",
                draw = "",
            )
        ).broadcast()
    }
}
