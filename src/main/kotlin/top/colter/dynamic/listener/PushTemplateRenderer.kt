package top.colter.dynamic.listener

import top.colter.dynamic.core.data.DynamicBlock
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicPayload
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
import top.colter.dynamic.template.MessageTemplateEngine
import top.colter.dynamic.template.TemplateSender
import top.colter.dynamic.template.TemplateStep
import top.colter.dynamic.core.link.LinkVideoDownloadResult

public class PushTemplateRenderer {
    public fun requiresDraw(template: String, update: SourceUpdate): Boolean {
        val payload = update.payload
        return when (payload) {
            is DynamicPayload -> DRAW_PLACEHOLDER in template
            is LivePayload -> update.eventType == SourceEventType.LIVE_STARTED && DRAW_PLACEHOLDER in template
        }
    }

    public fun render(
        template: String,
        update: SourceUpdate,
        drawImage: MediaRef?,
        mentionAll: Boolean = false,
        video: LinkVideoDownloadResult? = null,
        steps: List<TemplateStep> = MessageTemplateEngine.compile(template).steps,
        missing: (String) -> Unit = {},
        unknown: (String) -> Unit = {},
    ): List<MessageBatch> {
        return MessageTemplateEngine.render(
            steps,
            TemplateSender(update.publisher.externalId, update.publisher.name, update.publisher.avatar, update.occurredAtEpochSeconds),
            resolve = { key, mentionAllAllowed ->
                val contents = mutableListOf<MessageContent>()
                when (key) {
                    "atAll" -> if (mentionAll && mentionAllAllowed) contents += MessageContent.MentionAll(fallbackText = "")
                    "video" -> video?.let { contents += MessageContent.Video(fallbackText = "", video = it.video, altText = it.title) }
                    "size" -> video?.let { appendText(contents, "%.1f MB".format(java.util.Locale.ROOT, it.fileSizeBytes / 1048576.0)) }
                    "id" -> appendText(contents, update.key.externalId)
                    "url" -> appendText(contents, update.link.orEmpty())
                    else -> when (val payload = update.payload) {
                        is DynamicPayload -> appendDynamicPlaceholder(contents, "", key, update, payload, drawImage)
                        is LivePayload -> appendLivePlaceholder(contents, "", key, update, payload, drawImage)
                    }
                }
                contents.takeIf { it.any { item -> item !is MessageContent.Text || item.fallbackText.isNotBlank() } }
            },
            missing = missing,
            unknown = unknown,
        )
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
            "images" -> collectDynamicBlocks(update, dynamic).filterIsInstance<ImageGridBlock>().forEach { block ->
                block.images.forEach {
                    contents += MessageContent.Image(fallbackText = "", image = it.image, altText = it.alt)
                }
            }
            "links" -> appendText(contents, collectAdditionalLinks(update, dynamic).joinToString("\n"))
            else -> appendText(contents, dynamicTextValue(key, update, dynamic) ?: placeholder)
        }
    }

    private fun dynamicTextValue(
        key: String,
        update: SourceUpdate,
        dynamic: DynamicPayload,
    ): String? {
        return when (key) {
            "name" -> update.publisher.name
            "uid" -> update.publisher.externalId
            "did" -> update.key.externalId
            "time" -> update.occurredAtEpochSeconds.formatTime()
            "content" -> formatDynamicContent(update, dynamic)
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

    private fun formatDynamicContent(update: SourceUpdate, dynamic: DynamicPayload): String {
        return formatDynamicContent(update, dynamic, mutableSetOf(update.key.stableValue()))
    }

    private fun formatDynamicContent(
        update: SourceUpdate,
        dynamic: DynamicPayload,
        visitedUpdateKeys: MutableSet<String>,
    ): String {
        val sections = mutableListOf<String>()
        val currentText = StringBuilder()

        fun flushText() {
            val text = currentText.toString()
            if (text.isNotBlank()) {
                sections += text
            }
            currentText.clear()
        }

        dynamic.blocks.forEach { block ->
            when (block) {
                is TextBlock -> {
                    val text = block.content.plainText
                    if (text.isNotBlank()) {
                        if (currentText.isNotEmpty()) {
                            currentText.append('\n')
                        }
                        currentText.append(text)
                    }
                }
                is RepostBlock -> {
                    flushText()
                    val embedded = block.embedded ?: return@forEach
                    if (!visitedUpdateKeys.add(embedded.key.stableValue())) return@forEach
                    val payload = embedded.payload as? DynamicPayload ?: return@forEach
                    val repostContent = formatDynamicContent(embedded, payload, visitedUpdateKeys)
                        .trim('\r', '\n')
                        .takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val publisherName = embedded.publisher.name.trim().ifBlank { embedded.publisher.externalId }
                    sections += "转发自 $publisherName：\n$repostContent"
                }
                else -> Unit
            }
        }
        flushText()

        return sections.joinToString("\n\n")
    }

    private fun collectDynamicBlocks(update: SourceUpdate, dynamic: DynamicPayload): List<DynamicBlock> {
        return collectDynamicBlocks(dynamic, mutableSetOf(update.key.stableValue()))
    }

    private fun collectDynamicBlocks(
        dynamic: DynamicPayload,
        visitedUpdateKeys: MutableSet<String>,
    ): List<DynamicBlock> {
        val blocks = mutableListOf<DynamicBlock>()
        dynamic.blocks.forEach { block ->
            blocks += block
            if (block is RepostBlock) {
                val embedded = block.embedded ?: return@forEach
                if (!visitedUpdateKeys.add(embedded.key.stableValue())) return@forEach
                val payload = embedded.payload as? DynamicPayload ?: return@forEach
                blocks += collectDynamicBlocks(payload, visitedUpdateKeys)
            }
        }
        return blocks
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

    public companion object {
        public fun hasMentionAllPlaceholder(template: String): Boolean {
            return MessageTemplateEngine.compile(template).steps.any { !it.forward && "atAll" in it.fields }
        }

        public fun validateForwardBlockSyntax(template: String) {
            MessageTemplateEngine.compile(template)
        }

        private const val DRAW_PLACEHOLDER: String = "{draw}"
        private const val SECONDS_PER_MINUTE: Long = 60
        private const val SECONDS_PER_HOUR: Long = 60 * SECONDS_PER_MINUTE
        private const val SECONDS_PER_DAY: Long = 24 * SECONDS_PER_HOUR
    }
}
