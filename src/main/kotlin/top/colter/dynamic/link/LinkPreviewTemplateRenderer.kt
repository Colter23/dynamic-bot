package top.colter.dynamic.link

import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.ForwardNode
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkVideoDownloadResult
import top.colter.dynamic.draw.LinkPreviewRenderer
import top.colter.dynamic.util.formatTime

internal class LinkPreviewTemplateRenderer(
    private val previewRenderer: LinkPreviewRenderer,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun plan(template: String): LinkPreviewTemplatePlan {
        val steps = parseTemplateSegments(normalizeTemplateEscapes(template)).flatMap { segment ->
            when (segment) {
                is TemplateSegment.Text -> segment.value
                    .split(CHAIN_SEPARATOR)
                    .map { TemplateSegment.Text(it) }
                is TemplateSegment.Forward -> listOf(segment)
            }
        }.map { segment ->
            LinkPreviewTemplateStep(
                segment = segment,
                requiresVideo = segment.value.contains(VIDEO_PLACEHOLDER),
                forward = segment is TemplateSegment.Forward,
            )
        }
        return LinkPreviewTemplatePlan(
            immediateSteps = steps.filterNot { it.requiresVideo },
            videoSteps = steps.filter { it.requiresVideo },
        )
    }

    suspend fun renderPlanPreview(
        plan: LinkPreviewTemplatePlan,
        preview: LinkPreview,
    ): List<MessageBatch> {
        return renderSteps(plan.immediateSteps, LinkPreviewTemplateContext(preview = preview, time = nowEpochSeconds()))
    }

    suspend fun renderPlanVideo(
        plan: LinkPreviewTemplatePlan,
        preview: LinkPreview,
        video: LinkVideoDownloadResult,
    ): List<MessageBatch> {
        return renderSteps(
            steps = plan.videoSteps,
            context = LinkPreviewTemplateContext(
                preview = preview,
                video = video,
                time = nowEpochSeconds(),
            ),
        )
    }

    private suspend fun renderSteps(
        steps: List<LinkPreviewTemplateStep>,
        context: LinkPreviewTemplateContext,
    ): List<MessageBatch> {
        val batches = mutableListOf<MessageBatch>()
        val current = mutableListOf<MessageContent>()

        fun flush() {
            current.normalizedOrNull()?.let { batches += it }
            current.clear()
        }

        steps.forEach { step ->
            when (val segment = step.segment) {
                is TemplateSegment.Text -> {
                    current += renderFragment(segment.value.replace(LINE_BREAK, "\n"), context)
                    flush()
                }
                is TemplateSegment.Forward -> renderForwardContent(segment.value, context)
                    ?.let { forward ->
                        flush()
                        current += forward
                        flush()
                    }
            }
        }
        flush()
        return batches
    }

    private suspend fun renderForwardContent(
        template: String,
        context: LinkPreviewTemplateContext,
    ): MessageContent.Forward? {
        val nodes = template.split(CHAIN_SEPARATOR)
            .mapNotNull { nodeTemplate ->
                renderFragment(nodeTemplate.replace(LINE_BREAK, "\n"), context)
                    .normalizedOrNull()
                    ?.let { batch ->
                        ForwardNode(
                            senderId = context.uid,
                            senderName = context.name,
                            senderAvatar = context.preview.publisher?.avatar,
                            time = context.time,
                            batches = listOf(batch),
                        )
                    }
            }
        if (nodes.isEmpty()) return null

        val title = context.preview.title.ifBlank { context.preview.url }
        return MessageContent.Forward(
            fallbackText = "[合并转发] $title",
            title = title,
            summary = "共 ${nodes.size} 条内容",
            sourceName = context.name,
            sourceAvatar = context.preview.publisher?.avatar,
            nodes = nodes,
        )
    }

    private suspend fun renderFragment(
        template: String,
        context: LinkPreviewTemplateContext,
    ): List<MessageContent> {
        val contents = mutableListOf<MessageContent>()
        var currentIndex = 0
        PLACEHOLDER_REGEX.findAll(template).forEach { match ->
            appendText(contents, template.substring(currentIndex, match.range.first))
            appendPlaceholder(contents, match.value, match.groupValues[1], context)
            currentIndex = match.range.last + 1
        }
        appendText(contents, template.substring(currentIndex))
        return contents
    }

    private suspend fun appendPlaceholder(
        contents: MutableList<MessageContent>,
        placeholder: String,
        key: String,
        context: LinkPreviewTemplateContext,
    ) {
        when (key) {
            "draw" -> {
                val image = context.drawImage() ?: return
                contents += MessageContent.Image(
                    fallbackText = "",
                    image = image.copy(kind = MediaKind.IMAGE),
                    altText = context.preview.title.takeIf { it.isNotBlank() },
                )
            }
            "cover" -> {
                val cover = context.preview.cover ?: return
                contents += MessageContent.Image(
                    fallbackText = "",
                    image = cover.copy(kind = MediaKind.IMAGE),
                    altText = context.preview.title.takeIf { it.isNotBlank() },
                )
            }
            "video" -> {
                val video = context.video ?: return
                contents += MessageContent.Video(
                    fallbackText = "",
                    video = video.video.copy(kind = MediaKind.VIDEO),
                    altText = video.title.takeIf { it.isNotBlank() }
                        ?: context.preview.title.takeIf { it.isNotBlank() },
                )
            }
            else -> appendText(contents, context.textValue(key) ?: placeholder)
        }
    }

    private suspend fun LinkPreviewTemplateContext.drawImage(): MediaRef? {
        if (!drawAttempted) {
            drawAttempted = true
            drawImage = previewRenderer.render(preview)
        }
        return drawImage
    }

    private fun LinkPreviewTemplateContext.textValue(key: String): String? {
        return when (key) {
            "name" -> name
            "uid" -> uid
            "id" -> preview.id
            "kind" -> preview.badge ?: preview.kind.label()
            "title" -> preview.title
            "content" -> preview.description
            "link", "url" -> preview.url
            "time" -> time.formatTime()
            "duration" -> preview.durationSeconds?.formatDuration()
            "stats" -> preview.metrics.infoText()
            "size" -> video?.fileSizeBytes?.formatMegabytes()
            else -> preview.metrics.firstOrNull { it.key == key }?.display
                ?: preview.metrics.firstOrNull { it.key == key }?.raw?.toString()
        }
    }

    private data class LinkPreviewTemplateContext(
        val preview: LinkPreview,
        val video: LinkVideoDownloadResult? = null,
        val time: Long,
    ) {
        val name: String = preview.publisher?.name?.takeIf { it.isNotBlank() } ?: preview.platformId.value
        val uid: String = preview.publisher?.externalId?.takeIf { it.isNotBlank() } ?: preview.id
        var drawAttempted: Boolean = false
        var drawImage: MediaRef? = null
    }

    internal sealed interface TemplateSegment {
        val value: String

        data class Text(override val value: String) : TemplateSegment
        data class Forward(override val value: String) : TemplateSegment
    }

    internal companion object {
        fun validate(template: String) {
            parseTemplateSegments(normalizeTemplateEscapes(template))
        }

        private fun normalizeTemplateEscapes(template: String): String {
            return WRAPPED_ESCAPE_MARKER_REGEX.replace(template) {
                "\\${it.groupValues[1]}"
            }
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

        private const val FORWARD_START: String = "{>>}"
        private const val FORWARD_END: String = "{<<}"
        private const val LINE_BREAK: String = "\\n"
        private const val CHAIN_SEPARATOR: String = "\\r"
        private const val VIDEO_PLACEHOLDER: String = "{video}"
        private val PLACEHOLDER_REGEX: Regex = Regex("\\{([a-zA-Z0-9_]+)\\}")
        private val WRAPPED_ESCAPE_MARKER_REGEX: Regex = Regex("""\\+[ \t]*\r?\n[ \t]*([nr])""")
    }
}

internal data class LinkPreviewTemplatePlan(
    val immediateSteps: List<LinkPreviewTemplateStep>,
    val videoSteps: List<LinkPreviewTemplateStep>,
) {
    val requiresVideo: Boolean
        get() = videoSteps.isNotEmpty()

    val requiresVideoInForward: Boolean
        get() = videoSteps.any { it.forward }
}

internal data class LinkPreviewTemplateStep(
    val segment: LinkPreviewTemplateRenderer.TemplateSegment,
    val requiresVideo: Boolean,
    val forward: Boolean,
)

internal fun LinkPreview.fallbackText(): String {
    return buildString {
        append(title.ifBlank { url })
        description.trim().takeIf { it.isNotBlank() }?.let {
            appendLine()
            append(it)
        }
        metrics
            .mapNotNull { metric -> metric.display?.takeIf { it.isNotBlank() }?.let { "${metric.key}: $it" } }
            .takeIf { it.isNotEmpty() }
            ?.let {
                appendLine()
                append(it.joinToString(" / "))
            }
        url.takeIf { it.isNotBlank() }?.let {
            appendLine()
            append(it)
        }
    }
}

private fun List<MessageContent>.normalizedOrNull(): MessageBatch? {
    val normalized = trimBoundaryText()
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

private fun appendText(contents: MutableList<MessageContent>, value: String) {
    if (value.isEmpty()) return
    val lastText = contents.lastOrNull() as? MessageContent.Text
    if (lastText == null) {
        contents += MessageContent.Text(value)
    } else {
        contents[contents.lastIndex] = lastText.copy(fallbackText = lastText.fallbackText + value)
    }
}

private fun String.label(): String {
    return when (this) {
        LinkKinds.VIDEO -> "视频"
        LinkKinds.LIVE -> "直播"
        LinkKinds.USER -> "用户"
        LinkKinds.DYNAMIC -> "动态"
        else -> "链接"
    }
}

private fun List<DynamicMetric>.infoText(): String {
    return mapNotNull { metric ->
        metric.display?.takeIf { it.isNotBlank() }?.let { display ->
            when (metric.key) {
                "play" -> "${display}播放"
                "danmaku" -> "${display}弹幕"
                "like" -> "${display}点赞"
                "online" -> "${display}在线"
                else -> display
            }
        }
    }.joinToString(" / ")
}

private fun Long.formatDuration(): String {
    val total = coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (seconds > 0 || isEmpty()) add("${seconds}s")
    }.joinToString(" ")
}

private fun Long.formatMegabytes(): String {
    val megabytes = this / (1024.0 * 1024.0)
    return if (megabytes >= 10.0) {
        "%.0f MB".format(megabytes)
    } else {
        "%.1f MB".format(megabytes).replace(".0 MB", " MB")
    }
}
