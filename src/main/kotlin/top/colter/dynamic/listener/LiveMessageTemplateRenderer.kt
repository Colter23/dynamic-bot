package top.colter.dynamic.listener

import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.LiveChange
import top.colter.dynamic.core.data.LiveStatusUpdate
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.util.formatTime

public class LiveMessageTemplateRenderer {
    public fun requiresDraw(template: String, live: LiveStatusUpdate): Boolean {
        return live.change == LiveChange.STARTED && DRAW_PLACEHOLDER in template
    }

    public fun render(template: String, live: LiveStatusUpdate, drawImage: LazyImage?): List<MessageChain> {
        return template
            .split(CHAIN_SEPARATOR)
            .map { renderChain(it.replace(LINE_BREAK, "\n"), live, drawImage) }
            .mapNotNull { it.normalizedOrNull() }
    }

    private fun renderChain(template: String, live: LiveStatusUpdate, drawImage: LazyImage?): MessageChain {
        val contents = mutableListOf<MessageContent>()
        var currentIndex = 0

        PLACEHOLDER_REGEX.findAll(template).forEach { match ->
            appendText(contents, template.substring(currentIndex, match.range.first))
            appendPlaceholder(contents, match.value, match.groupValues[1], live, drawImage)
            currentIndex = match.range.last + 1
        }
        appendText(contents, template.substring(currentIndex))

        return MessageChain(contents)
    }

    private fun appendPlaceholder(
        contents: MutableList<MessageContent>,
        placeholder: String,
        key: String,
        live: LiveStatusUpdate,
        drawImage: LazyImage?,
    ) {
        when (key) {
            "draw" -> if (live.change == LiveChange.STARTED) {
                drawImage?.let { contents += MessageContent.Image(fallbackText = "", image = it) }
            } else {
                appendText(contents, placeholder)
            }
            "cover" -> live.cover?.let { contents += MessageContent.Image(fallbackText = "", image = it) }
            else -> appendText(contents, textValue(key, live) ?: placeholder)
        }
    }

    private fun textValue(key: String, live: LiveStatusUpdate): String? {
        return when (key) {
            "name" -> live.publisher.name
            "uid" -> live.publisher.externalId
            "rid" -> live.roomId
            "time" -> (live.startedAt ?: live.time).formatTime()
            "title" -> live.title
            "area" -> live.area.orEmpty()
            "link" -> live.link
            "startTime" -> live.startedAt?.formatTime().orEmpty()
            "endTime" -> (live.endedAt ?: live.time).formatTime()
            "duration" -> live.durationText()
            else -> null
        }
    }

    private fun LiveStatusUpdate.durationText(): String {
        val start = startedAt ?: return ""
        val end = endedAt ?: time
        val totalSeconds = (end - start).coerceAtLeast(0)
        val days = totalSeconds / SECONDS_PER_DAY
        val hours = (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return buildList {
            if (days > 0) add("${days}天")
            if (hours > 0) add("${hours}小时")
            if (minutes > 0) add("${minutes}分")
            if (seconds > 0 || isEmpty()) add("${seconds}秒")
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

    private fun MessageChain.normalizedOrNull(): MessageChain? {
        val normalized = content
            .trimBoundaryText()
            .filterNot { it is MessageContent.Text && it.fallbackText.isEmpty() }
        return if (normalized.isEmpty()) null else MessageChain(normalized)
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
