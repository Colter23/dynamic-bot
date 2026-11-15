package top.colter.dynamic.link

import kotlinx.coroutines.CancellationException
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkVideoDownloadResult
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.LinkPreviewRenderer
import top.colter.dynamic.listener.PushTemplateRenderer
import top.colter.dynamic.template.CompiledMessageTemplate
import top.colter.dynamic.template.MessageTemplateEngine
import top.colter.dynamic.template.TemplateSender
import top.colter.dynamic.template.TemplateStep
import top.colter.dynamic.util.formatMetricInfo
import top.colter.dynamic.util.formatTime

private val templateLogger = loggerFor<LinkPreviewTemplateRenderer>()

public data class LinkPreviewTemplateRenderResult(
    val batches: List<MessageBatch>,
    val drawImage: MediaRef? = null,
    val diagnostics: LinkPreviewTemplateDiagnostics = LinkPreviewTemplateDiagnostics(),
)

public data class LinkPreviewTemplateDiagnostics(
    val missingPlaceholders: Set<String> = emptySet(),
    val clearedFragments: Int = 0,
    val previewSkippedVideoPlaceholders: Boolean = false,
    val unknownPlaceholders: Set<String> = emptySet(),
    val drawFailed: Boolean = false,
)

/** 链接字段适配器；语法、分批及合并转发统一由 MessageTemplateEngine 处理。 */
public class LinkPreviewTemplateRenderer(
    private val previewRenderer: LinkPreviewRenderer,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    public suspend fun renderPreview(template: String, preview: LinkPreview): List<MessageBatch> =
        renderPreviewResult(template, preview).batches

    public suspend fun renderPreviewResult(template: String, preview: LinkPreview): LinkPreviewTemplateRenderResult =
        renderPlanPreviewResult(plan(template), preview)

    internal fun plan(template: String): LinkPreviewTemplatePlan = LinkPreviewTemplatePlan(MessageTemplateEngine.compile(template))

    internal fun session(
        preview: LinkPreview,
        update: SourceUpdate? = null,
        drawUpdate: (suspend () -> MediaRef?)? = null,
    ): Session = Session(preview, update, drawUpdate)

    internal suspend fun renderPlanPreviewResult(plan: LinkPreviewTemplatePlan, preview: LinkPreview): LinkPreviewTemplateRenderResult {
        val session = session(preview)
        val batches = session.render(plan.compiled.steps)
        return LinkPreviewTemplateRenderResult(batches, session.drawImage,
            session.diagnostics().copy(previewSkippedVideoPlaceholders = plan.requiresVideo))
    }

    internal inner class Session(
        private val preview: LinkPreview,
        private val update: SourceUpdate?,
        private val drawUpdate: (suspend () -> MediaRef?)?,
    ) {
        private var drawAttempted = false
        private var drawFailed = false
        var drawImage: MediaRef? = null
            private set
        private val missing = linkedSetOf<String>()
        private val unknown = linkedSetOf<String>()
        private val time = update?.occurredAtEpochSeconds ?: preview.publishedAtEpochSeconds ?: nowEpochSeconds()
        private val sender = TemplateSender(
            preview.publisher?.externalId ?: preview.id, preview.publisher?.name ?: preview.platformId.value,
            preview.publisher?.avatar, time, preview.title,
        )

        suspend fun render(steps: List<TemplateStep>, video: LinkVideoDownloadResult? = null): List<MessageBatch> {
            if (!drawAttempted && steps.any { "draw" in it.fields }) {
                drawAttempted = true
                drawImage = try {
                    if (update != null) drawUpdate?.invoke() else previewRenderer.render(preview.copy(publishedAtEpochSeconds = time))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    drawFailed = true
                    templateLogger.warn { "链接预览绘图失败，继续渲染文本：platform=${preview.platformId.value}，id=${preview.id}，类型=${error::class.simpleName}" }
                    null
                }
            }
            if (update != null) return PushTemplateRenderer().render("", update, drawImage, video = video, steps = steps,
                missing = { missing += it }, unknown = { unknown += it })
            return MessageTemplateEngine.render(steps, sender, resolve = { key, _ ->
                when (key) {
                    "draw" -> drawImage?.let { listOf(MessageContent.Image("", it.copy(kind = MediaKind.IMAGE))) }
                    "cover" -> preview.cover?.let { listOf(MessageContent.Image("", it.copy(kind = MediaKind.IMAGE))) }
                    "video" -> video?.let { listOf(MessageContent.Video("", it.video.copy(kind = MediaKind.VIDEO), altText = it.title.ifBlank { preview.title })) }
                    "atAll" -> emptyList()
                    else -> textValue(key, video)?.takeIf { it.isNotBlank() }?.let { listOf(MessageContent.Text(it)) }
                }
            }, missing = { missing += it }, unknown = { unknown += it })
        }

        fun diagnostics(): LinkPreviewTemplateDiagnostics = LinkPreviewTemplateDiagnostics(
            missingPlaceholders = missing.toSet(), clearedFragments = missing.size, unknownPlaceholders = unknown.toSet(),
            drawFailed = drawFailed,
        )

        private fun textValue(key: String, video: LinkVideoDownloadResult?): String? = when (key) {
            "name" -> sender.name
            "uid" -> sender.id
            "id", "did", "rid" -> preview.id
            "kind" -> preview.badge ?: when (preview.kind) {
                top.colter.dynamic.core.link.LinkKinds.VIDEO -> "视频"
                top.colter.dynamic.core.link.LinkKinds.LIVE -> "直播"
                top.colter.dynamic.core.link.LinkKinds.USER -> "用户"
                top.colter.dynamic.core.link.LinkKinds.DYNAMIC -> "动态"
                else -> "链接"
            }
            "title" -> preview.title
            "content" -> preview.description
            "link", "url" -> preview.url
            "time" -> time.formatTime()
            "duration" -> preview.durationSeconds?.let { "${it / 60}m ${it % 60}s" }
            "stats" -> preview.metrics.formatMetricInfo()
            "size" -> video?.let { "%.1f MB".format(java.util.Locale.ROOT, it.fileSizeBytes / 1048576.0) }
            else -> preview.metrics.firstOrNull { it.key == key }?.let { it.display ?: it.raw?.toString() }
        }
    }

    internal companion object {
        fun validate(template: String) { MessageTemplateEngine.compile(template) }
    }
}

internal data class LinkPreviewTemplatePlan(val compiled: CompiledMessageTemplate) {
    val immediateSteps: List<TemplateStep> get() = compiled.steps.take(compiled.boundary)
    val videoSteps: List<TemplateStep> get() = compiled.steps.drop(compiled.boundary)
    val requiresVideo: Boolean get() = compiled.requestsVideo
    val requiresVideoInForward: Boolean get() = videoSteps.any { it.forward && "video" in it.fields }
}

internal fun LinkPreview.fallbackText(): String = buildString {
    append(title.ifBlank { url })
    description.trim().takeIf { it.isNotBlank() }?.let { appendLine(); append(it) }
    metrics.mapNotNull { metric -> metric.display?.takeIf { it.isNotBlank() }?.let { "${metric.key}: $it" } }
        .takeIf { it.isNotEmpty() }?.let { appendLine(); append(it.joinToString(" / ")) }
    url.takeIf { it.isNotBlank() }?.let { appendLine(); append(it) }
}
