package top.colter.dynamic.listener

import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.PublisherPlatform
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.DrawDynamic


class DynamicListener: Listener<DynamicEvent> {
    override suspend fun onMessage(event: DynamicEvent) {

        PublisherRepository.findById()

        val draw = DrawDynamic(event.dynamic, DrawConfig(PublisherPlatform("", "", "", "")))



    }
}