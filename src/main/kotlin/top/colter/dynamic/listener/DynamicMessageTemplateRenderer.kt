package top.colter.dynamic.listener

import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.draw.tools.formatTime

public class DynamicMessageTemplateRenderer {
    public fun requiresDraw(template: String): Boolean {
        return DRAW_PLACEHOLDER in template
    }

    public fun render(template: String, dynamic: Dynamic, drawImage: LazyImage?): List<MessageChain> {
        return template
            .split(CHAIN_SEPARATOR)
            .map { renderChain(it.replace(LINE_BREAK, "\n"), dynamic, drawImage) }
            .mapNotNull { it.normalizedOrNull() }
    }

    private fun renderChain(template: String, dynamic: Dynamic, drawImage: LazyImage?): MessageChain {
        val contents = mutableListOf<MessageContent>()
        var currentIndex = 0

        PLACEHOLDER_REGEX.findAll(template).forEach { match ->
            appendText(contents, template.substring(currentIndex, match.range.first))
            appendPlaceholder(contents, match.value, match.groupValues[1], dynamic, drawImage)
            currentIndex = match.range.last + 1
        }
        appendText(contents, template.substring(currentIndex))

        return MessageChain(contents)
    }

    private fun appendPlaceholder(
        contents: MutableList<MessageContent>,
        placeholder: String,
        key: String,
        dynamic: Dynamic,
        drawImage: LazyImage?,
    ) {
        when (key) {
            "draw" -> drawImage?.let { contents += MessageContent.Image(fallbackText = "", image = it) }
            "images" -> dynamic.media?.pics.orEmpty().forEach {
                contents += MessageContent.Image(fallbackText = "", image = it.pic)
            }
            "links" -> appendText(contents, collectAdditionalLinks(dynamic).joinToString("\n"))
            else -> appendText(contents, textValue(key, dynamic) ?: placeholder)
        }
    }

    private fun textValue(key: String, dynamic: Dynamic): String? {
        return when (key) {
            "name" -> dynamic.publisher.name
            "uid" -> dynamic.publisher.externalId
            "did" -> dynamic.dynamicId
            "platform" -> dynamic.platform.name
            "time" -> dynamic.time.formatTime()
            "content" -> dynamic.content?.text.orEmpty()
            "link" -> dynamic.link
            else -> null
        }
    }

    private fun collectAdditionalLinks(dynamic: Dynamic): List<String> {
        val links = linkedSetOf<String>()
        dynamic.media?.video?.link?.addIfUseful(links, dynamic.link)
        dynamic.media?.card.addLinkIfUseful(links, dynamic.link)
        dynamic.media?.smallCard.addLinkIfUseful(links, dynamic.link)
        dynamic.media?.miniCard.addLinkIfUseful(links, dynamic.link)
        dynamic.content?.contentNodes
            .orEmpty()
            .filterIsInstance<DynamicContentNodeLink>()
            .forEach { it.url.addIfUseful(links, dynamic.link) }
        return links.toList()
    }

    private fun DynamicMediaCard?.addLinkIfUseful(links: MutableSet<String>, mainLink: String) {
        this?.link.addIfUseful(links, mainLink)
    }

    private fun String?.addIfUseful(links: MutableSet<String>, mainLink: String) {
        val value = this?.trim().orEmpty()
        if (value.isNotBlank() && value != mainLink) {
            links += value
        }
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
        private val PLACEHOLDER_REGEX: Regex = Regex("\\{([a-zA-Z0-9_]+)\\}")
    }
}
