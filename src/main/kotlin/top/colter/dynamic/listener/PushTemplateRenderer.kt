package top.colter.dynamic.listener

import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.ForwardNode
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.util.formatTime

public class PushTemplateRenderer {
    public fun requiresDraw(template: String, update: SourceUpdate): Boolean {
        val payload = update.payload
        return when (payload) {
            is DynamicPayload -> DRAW_PLACEHOLDER in template
            is LivePayload -> update.eventType == SourceEventType.LIVE_STARTED && DRAW_PLACEHOLDER in template
        }
    }

    public fun render(template: String, update: SourceUpdate, drawImage: MediaRef?): List<MessageBatch> {
        val splitConversation = update.eventType != SourceEventType.LIVE_ENDED
        return renderTemplate(
            template = template,
            update = update,
            splitConversation = splitConversation,
            appendPlaceholder = { contents, placeholder, key ->
                when (val payload = update.payload) {
                    is DynamicPayload -> appendDynamicPlaceholder(contents, placeholder, key, update, payload, drawImage)
                    is LivePayload -> appendLivePlaceholder(contents, placeholder, key, update, payload, drawImage)
                }
            },
        )
    }

    private fun renderTemplate(
        template: String,
        update: SourceUpdate,
        splitConversation: Boolean,
        appendPlaceholder: (MutableList<MessageContent>, String, String) -> Unit,
    ): List<MessageBatch> {
        val batches = mutableListOf<MessageBatch>()
        val current = mutableListOf<MessageContent>()

        fun flush() {
            MessageBatch(current.toList()).normalizedOrNull()?.let { batches += it }
            current.clear()
        }

        parseTemplateSegments(template).forEach { segment ->
            when (segment) {
                is TemplateSegment.Text -> appendTextSegment(
                    contents = current,
                    template = segment.value,
                    splitConversation = splitConversation,
                    flush = ::flush,
                    appendPlaceholder = appendPlaceholder,
                )
                is TemplateSegment.Forward -> renderForwardContent(segment.value, update, appendPlaceholder)
                    ?.let { current += it }
            }
        }
        flush()
        return batches
    }

    private fun appendTextSegment(
        contents: MutableList<MessageContent>,
        template: String,
        splitConversation: Boolean,
        flush: () -> Unit,
        appendPlaceholder: (MutableList<MessageContent>, String, String) -> Unit,
    ) {
        if (!splitConversation) {
            appendRenderedFragment(contents, template, appendPlaceholder)
            return
        }

        template.split(CHAIN_SEPARATOR).forEachIndexed { index, fragment ->
            if (index > 0) flush()
            appendRenderedFragment(contents, fragment, appendPlaceholder)
        }
    }

    private fun appendRenderedFragment(
        contents: MutableList<MessageContent>,
        template: String,
        appendPlaceholder: (MutableList<MessageContent>, String, String) -> Unit,
    ) {
        val rendered = renderBatch(template.replace(LINE_BREAK, "\n"), appendPlaceholder)
        contents += rendered.content
    }

    private fun renderForwardContent(
        template: String,
        update: SourceUpdate,
        appendPlaceholder: (MutableList<MessageContent>, String, String) -> Unit,
    ): MessageContent.Forward? {
        val nodes = template.split(CHAIN_SEPARATOR)
            .mapNotNull { nodeTemplate ->
                renderBatch(nodeTemplate.replace(LINE_BREAK, "\n"), appendPlaceholder)
                    .normalizedOrNull()
                    ?.let { batch ->
                        ForwardNode(
                            senderId = update.publisher.externalId,
                            senderName = update.publisher.name,
                            senderAvatar = update.publisher.avatar,
                            time = update.occurredAtEpochSeconds,
                            batches = listOf(batch),
                        )
                    }
            }
        if (nodes.isEmpty()) return null

        val title = "${update.publisher.name} 的原始内容"
        return MessageContent.Forward(
            fallbackText = "[合并转发] $title",
            title = title,
            summary = "共 ${nodes.size} 条内容",
            sourceName = update.publisher.name,
            sourceAvatar = update.publisher.avatar,
            nodes = nodes,
        )
    }

    private fun renderBatch(
        template: String,
        appendPlaceholder: (MutableList<MessageContent>, String, String) -> Unit,
    ): MessageBatch {
        val contents = mutableListOf<MessageContent>()
        var currentIndex = 0

        PLACEHOLDER_REGEX.findAll(template).forEach { match ->
            appendText(contents, template.substring(currentIndex, match.range.first))
            appendPlaceholder(contents, match.value, match.groupValues[1])
            currentIndex = match.range.last + 1
        }
        appendText(contents, template.substring(currentIndex))

        return MessageBatch(contents)
    }

    private fun appendDynamicPlaceholder(
        contents: MutableList<MessageContent>,
        placeholder: String,
        key: String,
        update: SourceUpdate,
        dynamic: DynamicPayload,
        drawImage: MediaRef?,
    ) {
        when (key) {
            "draw" -> drawImage?.let { contents += MessageContent.Image(fallbackText = "", image = it) }
            "images" -> dynamic.blocks.filterIsInstance<ImageGridBlock>().forEach { block ->
                block.images.forEach {
                    contents += MessageContent.Image(fallbackText = "", image = it.image, altText = it.alt)
                }
            }
            "links" -> appendText(contents, collectAdditionalLinks(update, dynamic).joinToString("\n"))
            else -> appendText(contents, dynamicTextValue(key, update, dynamic) ?: placeholder)
        }
    }

    private fun dynamicTextValue(key: String, update: SourceUpdate, dynamic: DynamicPayload): String? {
        return when (key) {
            "name" -> update.publisher.name
            "uid" -> update.publisher.externalId
            "did" -> update.key.externalId
            "time" -> update.occurredAtEpochSeconds.formatTime()
            "content" -> dynamic.blocks
                .filterIsInstance<TextBlock>()
                .joinToString("\n") { it.content.plainText }
            "link" -> update.link
            else -> null
        }
    }

    private fun appendLivePlaceholder(
        contents: MutableList<MessageContent>,
        placeholder: String,
        key: String,
        update: SourceUpdate,
        live: LivePayload,
        drawImage: MediaRef?,
    ) {
        when (key) {
            "draw" -> if (update.eventType == SourceEventType.LIVE_STARTED) {
                drawImage?.let { contents += MessageContent.Image(fallbackText = "", image = it) }
            } else {
                appendText(contents, placeholder)
            }
            "cover" -> if (update.eventType == SourceEventType.LIVE_STARTED) {
                live.cover?.let { contents += MessageContent.Image(fallbackText = "", image = it) }
            } else {
                appendText(contents, placeholder)
            }
            else -> appendText(contents, liveTextValue(key, update, live) ?: placeholder)
        }
    }

    private fun liveTextValue(key: String, update: SourceUpdate, live: LivePayload): String? {
        return when (key) {
            "name" -> update.publisher.name
            "uid" -> update.publisher.externalId
            "rid" -> live.roomId
            "time" -> if (update.eventType == SourceEventType.LIVE_STARTED) {
                (live.startedAtEpochSeconds ?: update.occurredAtEpochSeconds).formatTime()
            } else {
                null
            }
            "title" -> live.title
            "area" -> live.area.orEmpty()
            "link" -> update.link
            "startTime" -> if (update.eventType == SourceEventType.LIVE_ENDED) live.startedAtEpochSeconds?.formatTime().orEmpty() else null
            "endTime" -> if (update.eventType == SourceEventType.LIVE_ENDED) {
                (live.endedAtEpochSeconds ?: update.occurredAtEpochSeconds).formatTime()
            } else {
                null
            }
            "duration" -> if (update.eventType == SourceEventType.LIVE_ENDED) durationText(update, live) else null
            else -> null
        }
    }

    private fun collectAdditionalLinks(update: SourceUpdate, dynamic: DynamicPayload): List<String> {
        val links = linkedSetOf<String>()
        dynamic.blocks.forEach { block ->
            block.link.addIfUseful(links, update.link)
            when (block) {
                is TextBlock -> collectContentLinks(block, links, update.link)
                is MediaCardBlock -> block.card.link.addIfUseful(links, update.link)
                is RepostBlock -> {
                    block.embedded?.let { embedded ->
                        (embedded.payload as? DynamicPayload)
                            ?.let { payload -> collectAdditionalLinks(embedded, payload) }
                            ?.forEach { it.addIfUseful(links, update.link) }
                    }
                }
                else -> Unit
            }
        }
        return links.toList()
    }

    private fun collectContentLinks(block: TextBlock, links: MutableSet<String>, mainLink: String?) {
        block.content.nodes.forEach {
            when (it) {
                is DynamicContentNodeLink -> it.url.addIfUseful(links, mainLink)
                is DynamicContentNodeMention -> it.url.addIfUseful(links, mainLink)
                is DynamicContentNodeTag -> it.url.addIfUseful(links, mainLink)
                else -> Unit
            }
        }
    }

    private fun String?.addIfUseful(links: MutableSet<String>, mainLink: String?) {
        val value = this?.trim().orEmpty()
        if (value.isNotBlank() && value != mainLink) {
            links += value
        }
    }

    private fun durationText(update: SourceUpdate, live: LivePayload): String {
        val start = live.startedAtEpochSeconds ?: return ""
        val end = live.endedAtEpochSeconds ?: update.occurredAtEpochSeconds
        val totalSeconds = (end - start).coerceAtLeast(0)
        val days = totalSeconds / SECONDS_PER_DAY
        val hours = (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return buildList {
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (seconds > 0 || isEmpty()) add("${seconds}s")
        }.joinToString(" ")
    }

    private fun appendText(contents: MutableList<MessageContent>, value: String) {
        if (value.isEmpty()) return
        val lastText = contents.lastOrNull() as? MessageContent.Text
        if (lastText == null) {
            contents += MessageContent.Text(value)
        } else {
            contents[contents.lastIndex] = lastText.copy(fallbackText = lastText.fallbackText + value)
        }
    }

    private fun MessageBatch.normalizedOrNull(): MessageBatch? {
        val normalized = content
            .trimBoundaryText()
            .filterNot { it is MessageContent.Text && it.fallbackText.isEmpty() }
        return if (normalized.isEmpty()) null else MessageBatch(normalized)
    }

    private fun List<MessageContent>.trimBoundaryText(): List<MessageContent> {
        if (isEmpty()) return this
        val result = toMutableList()
        (result.firstOrNull() as? MessageContent.Text)?.let {
            result[0] = it.copy(fallbackText = it.fallbackText.trimStart('\r', '\n'))
        }
        (result.lastOrNull() as? MessageContent.Text)?.let {
            result[result.lastIndex] = it.copy(fallbackText = it.fallbackText.trimEnd('\r', '\n'))
        }
        return result
    }

    private sealed interface TemplateSegment {
        data class Text(val value: String) : TemplateSegment
        data class Forward(val value: String) : TemplateSegment
    }

    public companion object {
        public fun validateForwardBlockSyntax(template: String) {
            parseTemplateSegments(template)
        }

        private fun parseTemplateSegments(template: String): List<TemplateSegment> {
            val segments = mutableListOf<TemplateSegment>()
            var index = 0

            while (index < template.length) {
                val nextStart = template.indexOf(FORWARD_START, startIndex = index)
                val nextEnd = template.indexOf(FORWARD_END, startIndex = index)
                require(nextEnd == -1 || (nextStart != -1 && nextStart < nextEnd)) {
                    "合并转发模板结束标记 $FORWARD_END 缺少开始标记 $FORWARD_START"
                }

                if (nextStart == -1) {
                    segments += TemplateSegment.Text(template.substring(index))
                    break
                }

                if (nextStart > index) {
                    segments += TemplateSegment.Text(template.substring(index, nextStart))
                }

                val contentStart = nextStart + FORWARD_START.length
                val close = template.indexOf(FORWARD_END, startIndex = contentStart)
                require(close != -1) {
                    "合并转发模板开始标记 $FORWARD_START 缺少结束标记 $FORWARD_END"
                }
                val nestedStart = template.indexOf(FORWARD_START, startIndex = contentStart)
                require(nestedStart == -1 || nestedStart > close) {
                    "合并转发模板不支持嵌套 $FORWARD_START"
                }

                segments += TemplateSegment.Forward(template.substring(contentStart, close))
                index = close + FORWARD_END.length
            }

            return segments
        }

        private const val DRAW_PLACEHOLDER: String = "{draw}"
        private const val FORWARD_START: String = "{>>}"
        private const val FORWARD_END: String = "{<<}"
        private const val LINE_BREAK: String = "\\n"
        private const val CHAIN_SEPARATOR: String = "\\r"
        private const val SECONDS_PER_MINUTE: Long = 60
        private const val SECONDS_PER_HOUR: Long = 60 * SECONDS_PER_MINUTE
        private const val SECONDS_PER_DAY: Long = 24 * SECONDS_PER_HOUR
        private val PLACEHOLDER_REGEX: Regex = Regex("\\{([a-zA-Z0-9_]+)\\}")
    }
}
