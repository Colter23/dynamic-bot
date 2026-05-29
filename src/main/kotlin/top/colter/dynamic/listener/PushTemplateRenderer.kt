package top.colter.dynamic.listener

import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.ImageAttachment
import top.colter.dynamic.core.data.LiveChange
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.util.formatTime

public class PushTemplateRenderer {
    public fun requiresDraw(template: String, update: SourceUpdate): Boolean {
        val payload = update.payload
        return when (payload) {
            is DynamicPayload -> DRAW_PLACEHOLDER in template
            is LivePayload -> payload.change == LiveChange.STARTED && DRAW_PLACEHOLDER in template
            else -> false
        }
    }

    public fun render(template: String, update: SourceUpdate, drawImage: MediaRef?): List<MessageBatch> {
        val splitConversation = (update.payload as? LivePayload)?.change != LiveChange.ENDED
        return renderTemplate(
            template = template,
            splitConversation = splitConversation,
            appendPlaceholder = { contents, placeholder, key ->
                when (val payload = update.payload) {
                    is DynamicPayload -> appendDynamicPlaceholder(contents, placeholder, key, update, payload, drawImage)
                    is LivePayload -> appendLivePlaceholder(contents, placeholder, key, update, payload, drawImage)
                    else -> appendText(contents, placeholder)
                }
            },
        )
    }

    private fun renderTemplate(
        template: String,
        splitConversation: Boolean,
        appendPlaceholder: (MutableList<MessageContent>, String, String) -> Unit,
    ): List<MessageBatch> {
        val fragments = if (splitConversation) template.split(CHAIN_SEPARATOR) else listOf(template)
        return fragments
            .map { renderBatch(it.replace(LINE_BREAK, "\n"), appendPlaceholder) }
            .mapNotNull { it.normalizedOrNull() }
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
            "images" -> dynamic.attachments.filterIsInstance<ImageAttachment>().forEach { attachment ->
                attachment.images.forEach {
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
            "content" -> dynamic.content?.plainText.orEmpty()
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
            "draw" -> if (live.change == LiveChange.STARTED) {
                drawImage?.let { contents += MessageContent.Image(fallbackText = "", image = it) }
            } else {
                appendText(contents, placeholder)
            }
            "cover" -> if (live.change == LiveChange.STARTED) {
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
            "time" -> if (live.change == LiveChange.STARTED) {
                (live.startedAtEpochSeconds ?: update.occurredAtEpochSeconds).formatTime()
            } else {
                null
            }
            "title" -> live.title
            "area" -> live.area.orEmpty()
            "link" -> update.link
            "startTime" -> if (live.change == LiveChange.ENDED) live.startedAtEpochSeconds?.formatTime().orEmpty() else null
            "endTime" -> if (live.change == LiveChange.ENDED) {
                (live.endedAtEpochSeconds ?: update.occurredAtEpochSeconds).formatTime()
            } else {
                null
            }
            "duration" -> if (live.change == LiveChange.ENDED) durationText(update, live) else null
            else -> null
        }
    }

    private fun collectAdditionalLinks(update: SourceUpdate, dynamic: DynamicPayload): List<String> {
        val links = linkedSetOf<String>()
        dynamic.attachments.forEach { attachment ->
            attachment.link.addIfUseful(links, update.link)
        }
        dynamic.content?.nodes
            .orEmpty()
            .forEach {
                when (it) {
                    is DynamicContentNodeLink -> it.url.addIfUseful(links, update.link)
                    is DynamicContentNodeMention -> it.url.addIfUseful(links, update.link)
                    is DynamicContentNodeTag -> it.url.addIfUseful(links, update.link)
                    else -> Unit
                }
            }
        return links.toList()
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
            result[0] = it.copy(fallbackText = it.fallbackText.trimStart('\n'))
        }
        (result.lastOrNull() as? MessageContent.Text)?.let {
            result[result.lastIndex] = it.copy(fallbackText = it.fallbackText.trimEnd('\n'))
        }
        return result
    }

    private companion object {
        private const val DRAW_PLACEHOLDER: String = "{draw}"
        private const val LINE_BREAK: String = "\\n"
        private const val CHAIN_SEPARATOR: String = "\\r"
        private const val SECONDS_PER_MINUTE: Long = 60
        private const val SECONDS_PER_HOUR: Long = 60 * SECONDS_PER_MINUTE
        private const val SECONDS_PER_DAY: Long = 24 * SECONDS_PER_HOUR
        private val PLACEHOLDER_REGEX: Regex = Regex("\\{([a-zA-Z0-9_]+)\\}")
    }
}
