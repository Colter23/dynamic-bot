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
import top.colter.dynamic.util.formatMetricInfo

public data class LinkPreviewTemplateRenderResult(
    val batches: List<MessageBatch>,
    val drawImage: MediaRef? = null,
    val diagnostics: LinkPreviewTemplateDiagnostics = LinkPreviewTemplateDiagnostics(),
)

public data class LinkPreviewTemplateDiagnostics(
    val missingPlaceholders: Set<String> = emptySet(),
    val clearedFragments: Int = 0,
    val previewSkippedVideoPlaceholders: Boolean = false,
)

public class LinkPreviewTemplateRenderer(
    private val previewRenderer: LinkPreviewRenderer,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    public suspend fun renderPreview(
        template: String,
        preview: LinkPreview,
    ): List<MessageBatch> {
        return renderPreviewResult(template, preview).batches
    }

    public suspend fun renderPreviewResult(
        template: String,
        preview: LinkPreview,
    ): LinkPreviewTemplateRenderResult {
        return renderPlanPreviewResult(plan(template), preview)
    }

    internal fun plan(template: String): LinkPreviewTemplatePlan {
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

    internal suspend fun renderPlanPreview(
        plan: LinkPreviewTemplatePlan,
        preview: LinkPreview,
    ): List<MessageBatch> {
        return renderPlanPreviewResult(plan, preview).batches
    }

    internal suspend fun renderPlanPreviewResult(
        plan: LinkPreviewTemplatePlan,
        preview: LinkPreview,
    ): LinkPreviewTemplateRenderResult {
        val context = LinkPreviewTemplateContext(
            preview = preview,
            time = nowEpochSeconds(),
            previewOnly = true,
        )
        if (plan.requiresVideo) {
            context.missingPlaceholders += "video"
            context.previewSkippedVideoPlaceholders = true
        }
        return LinkPreviewTemplateRenderResult(
            batches = renderSteps(plan.immediateSteps, context),
            drawImage = context.drawImage?.copy(kind = MediaKind.IMAGE),
            diagnostics = context.diagnostics(),
        )
    }

    internal suspend fun renderPlanVideo(
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
                previewOnly = false,
            ),
        )
    }

    internal suspend fun renderPlanVideoFallback(
        plan: LinkPreviewTemplatePlan,
        preview: LinkPreview,
    ): List<MessageBatch> {
        return renderSteps(
            steps = plan.videoSteps,
            context = LinkPreviewTemplateContext(
                preview = preview,
                time = nowEpochSeconds(),
                previewOnly = false,
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
            current.normalizedOrNull(context)?.let { batches += it }
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
                    .normalizedOrNull(context)
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
                val cover = context.preview.cover
                if (cover == null) {
                    appendMissingPlaceholder(contents, key, context)
                    return
                }
                contents += MessageContent.Image(
                    fallbackText = "",
                    image = cover.copy(kind = MediaKind.IMAGE),
                    altText = context.preview.title.takeIf { it.isNotBlank() },
                )
            }
            "video" -> {
                val video = context.video
                if (video == null) {
                    if (context.previewOnly) context.previewSkippedVideoPlaceholders = true
                    appendMissingPlaceholder(contents, key, context)
                    return
                }
                contents += MessageContent.Video(
                    fallbackText = "",
                    video = video.video.copy(kind = MediaKind.VIDEO),
                    altText = video.title.takeIf { it.isNotBlank() }
                        ?: context.preview.title.takeIf { it.isNotBlank() },
                )
            }
            else -> {
                val value = context.textValue(key)?.takeIf { it.isNotBlank() }
                if (value == null) {
                    if (context.previewOnly && key == "size") context.previewSkippedVideoPlaceholders = true
                    appendMissingPlaceholder(contents, key, context)
                } else {
                    appendText(contents, value)
                }
            }
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
            "stats" -> preview.metrics.formatMetricInfo()
            "size" -> video?.fileSizeBytes?.formatMegabytes()
            else -> preview.metrics.firstOrNull { it.key == key }?.display
                ?: preview.metrics.firstOrNull { it.key == key }?.raw?.toString()
        }
    }

    internal data class LinkPreviewTemplateContext(
        val preview: LinkPreview,
        val video: LinkVideoDownloadResult? = null,
        val time: Long,
        val previewOnly: Boolean,
    ) {
        val name: String = preview.publisher?.name?.takeIf { it.isNotBlank() } ?: preview.platformId.value
        val uid: String = preview.publisher?.externalId?.takeIf { it.isNotBlank() } ?: preview.id
        val missingPlaceholders: MutableSet<String> = linkedSetOf()
        var drawAttempted: Boolean = false
        var drawImage: MediaRef? = null
        var clearedFragments: Int = 0
        var previewSkippedVideoPlaceholders: Boolean = false

        fun diagnostics(): LinkPreviewTemplateDiagnostics {
            return LinkPreviewTemplateDiagnostics(
                missingPlaceholders = missingPlaceholders.toSet(),
                clearedFragments = clearedFragments,
                previewSkippedVideoPlaceholders = previewSkippedVideoPlaceholders,
            )
        }
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

private fun List<MessageContent>.normalizedOrNull(context: LinkPreviewTemplateRenderer.LinkPreviewTemplateContext): MessageBatch? {
    val normalized = cleanupMissingPlaceholderText(context)
        .trimBoundaryText()
        .filterNot { it is MessageContent.Text && it.fallbackText.isEmpty() }
    return if (normalized.isEmpty()) null else MessageBatch(normalized)
}

private fun List<MessageContent>.cleanupMissingPlaceholderText(
    context: LinkPreviewTemplateRenderer.LinkPreviewTemplateContext,
): List<MessageContent> {
    if (none { it is MessageContent.Text && MISSING_PLACEHOLDER_MARKER in it.fallbackText }) return this
    return map { content ->
        if (content is MessageContent.Text) {
            val cleaned = content.fallbackText.removeMissingPlaceholderLines()
            if (cleaned != content.fallbackText) context.clearedFragments += 1
            content.copy(fallbackText = cleaned)
        } else {
            content
        }
    }
}

private fun String.removeMissingPlaceholderLines(): String {
    if (MISSING_PLACEHOLDER_MARKER !in this) return this
    val result = StringBuilder(length)
    var start = 0
    while (start <= length) {
        val end = indexOf('\n', start).takeIf { it >= 0 } ?: length
        val line = substring(start, end)
        val cleaned = line.removeMissingPlaceholderMarkers()
            .trimDanglingPlaceholderSeparators()
        val dropLine = MISSING_PLACEHOLDER_MARKER in line && cleaned.isDanglingPlaceholderLine()
        if (!dropLine) {
            result.append(cleaned)
            if (end < length) result.append('\n')
        }
        if (end == length) break
        start = end + 1
    }
    return result.toString()
}

private fun String.removeMissingPlaceholderMarkers(): String {
    var value = this
    while (true) {
        val markerIndex = value.indexOf(MISSING_PLACEHOLDER_MARKER)
        if (markerIndex < 0) return value
        val before = value.substring(0, markerIndex).removeDanglingPlaceholderSuffix()
        val after = value.substring(markerIndex + MISSING_PLACEHOLDER_MARKER.length)
        value = before + after
    }
}

private fun String.removeDanglingPlaceholderSuffix(): String {
    if (isEmpty()) return this
    var end = length
    while (end > 0 && this[end - 1].isWhitespace()) end--
    if (end == 0) return ""

    if (this[end - 1] == '：' || this[end - 1] == ':') {
        var start = end - 1
        while (start > 0 && this[start - 1].isWhitespace()) start--
        while (start > 0 && !this[start - 1].isMissingPlaceholderLabelBoundary()) start--
        while (start > 0 && this[start - 1].isMissingPlaceholderLabelBoundary()) start--
        return substring(0, start)
    }

    if (!this[end - 1].isDanglingPlaceholderSeparator()) return this
    var start = end
    while (start > 0 && this[start - 1].isDanglingPlaceholderSeparator()) start--
    return substring(0, start)
}

private fun String.trimDanglingPlaceholderSeparators(): String {
    var start = 0
    var end = length
    while (start < end && this[start].isDanglingPlaceholderSeparator()) start++
    while (end > start && this[end - 1].isDanglingPlaceholderSeparator()) end--
    return substring(start, end)
}

private fun String.isDanglingPlaceholderLine(): Boolean {
    val value = trim()
    if (value.isBlank()) return true
    return value.endsWith('：') ||
        value.endsWith(':') ||
        value.all { it.isWhitespace() || it in DANGLING_PLACEHOLDER_PUNCTUATION }
}

private fun Char.isDanglingPlaceholderSeparator(): Boolean {
    return isWhitespace() || this in DANGLING_PLACEHOLDER_SEPARATORS
}

private fun Char.isMissingPlaceholderLabelBoundary(): Boolean {
    return isWhitespace() || this in MISSING_PLACEHOLDER_LABEL_BOUNDARIES
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

private fun appendMissingPlaceholder(
    contents: MutableList<MessageContent>,
    key: String,
    context: LinkPreviewTemplateRenderer.LinkPreviewTemplateContext,
) {
    context.missingPlaceholders += key
    appendText(contents, MISSING_PLACEHOLDER_MARKER)
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

private const val MISSING_PLACEHOLDER_MARKER = "\u0000LINK_PREVIEW_TEMPLATE_MISSING\u0000"
private val DANGLING_PLACEHOLDER_SEPARATORS: Set<Char> = setOf(
    '：',
    ':',
    '，',
    ',',
    '。',
    '.',
    '；',
    ';',
    '、',
    '/',
    '\\',
    '|',
    '-',
    '_',
    '·',
)
private val MISSING_PLACEHOLDER_LABEL_BOUNDARIES: Set<Char> = DANGLING_PLACEHOLDER_SEPARATORS - setOf('：', ':') + setOf(
    '(',
    ')',
    '（',
    '）',
    '[',
    ']',
    '【',
    '】',
    '<',
    '>',
    '《',
    '》',
)
private val DANGLING_PLACEHOLDER_PUNCTUATION: Set<Char> = setOf(
    '：',
    ':',
    '，',
    ',',
    '。',
    '.',
    '；',
    ';',
    '、',
    '/',
    '\\',
    '|',
    '-',
    '_',
    '·',
    '!',
    '！',
    '?',
    '？',
    '(',
    ')',
    '（',
    '）',
    '[',
    ']',
    '【',
    '】',
    '<',
    '>',
    '《',
    '》',
)
