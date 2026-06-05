package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.paragraph.TextStyle
import top.colter.dynamic.core.data.DynamicBlock
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.draw.DrawConfig
import top.colter.skiko.Dp
import top.colter.skiko.Modifier
import top.colter.skiko.data.RichParagraphBuilder
import top.colter.skiko.dp
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.RichText
import top.colter.skiko.margin
import top.colter.skiko.px

private const val SHORT_CONTENT_CHARS = 16
private const val NORMAL_CONTENT_CHARS = 120
private const val LONG_CONTENT_CHARS = 420
private const val MAX_CONTENT_FONT_SIZE = 48f
private const val NORMAL_CONTENT_FONT_SIZE = 36f
private const val MIN_CONTENT_FONT_SIZE = 30f
private const val TITLE_TO_CONTENT_RATIO = 1.2f
private val minTitleFontSize = 38.dp
private val maxTitleFontSize = 56.dp

internal fun Layout.drawDynamicContent(
    content: DynamicContent,
    config: DrawConfig,
    bottomSpacing: Dp = 0.dp,
) {
    if (content.nodes.isEmpty()) return

    val fontSize = dynamicContentFontSize(content)
    val style = TextStyle()
        .setColor(config.theme.textColor)
        .setFontSize(fontSize.px)
    val linkStyle = TextStyle()
        .setColor(config.theme.linkColor)
        .setFontSize(fontSize.px)
    val paragraph = RichParagraphBuilder(style)

    content.nodes.forEach {
        when (it) {
            is DynamicContentNodeText -> paragraph.addText(it.text, style)
            is DynamicContentNodeLink -> paragraph.addText(it.text, linkStyle)
            is DynamicContentNodeMention -> paragraph.addText(it.text, linkStyle)
            is DynamicContentNodeTag -> paragraph.addText(it.text, linkStyle)
            is DynamicContentNodeEmoji -> {
                val image = it.image
                if (image == null) {
                    paragraph.addText(it.text, style)
                } else {
                    paragraph.addEmoji(it.text, config.image(image), linkStyle)
                }
            }
        }
    }

    RichText(
        paragraph = paragraph.build(),
        modifier = Modifier().margin(bottom = bottomSpacing).fillMaxWidth(),
    )
}

private fun dynamicContentFontSize(content: DynamicContent): Dp {
    return dynamicContentFontSize(content.displayCharCount())
}

private fun dynamicContentFontSize(charCount: Int): Dp {
    val size = when {
        charCount <= SHORT_CONTENT_CHARS -> MAX_CONTENT_FONT_SIZE
        charCount <= NORMAL_CONTENT_CHARS -> interpolate(
            start = MAX_CONTENT_FONT_SIZE,
            end = NORMAL_CONTENT_FONT_SIZE,
            progress = (charCount - SHORT_CONTENT_CHARS).toFloat() / (NORMAL_CONTENT_CHARS - SHORT_CONTENT_CHARS),
        )
        charCount <= LONG_CONTENT_CHARS -> interpolate(
            start = NORMAL_CONTENT_FONT_SIZE,
            end = MIN_CONTENT_FONT_SIZE,
            progress = (charCount - NORMAL_CONTENT_CHARS).toFloat() / (LONG_CONTENT_CHARS - NORMAL_CONTENT_CHARS),
        )
        else -> MIN_CONTENT_FONT_SIZE
    }
    return size.dp
}

internal fun dynamicTitleFontSize(blocks: List<DynamicBlock>): Dp {
    val bodyCharCount = blocks
        .filterIsInstance<TextBlock>()
        .sumOf { it.content.displayCharCount() }
    val bodyFontSize = dynamicContentFontSize(bodyCharCount)
    return (bodyFontSize * TITLE_TO_CONTENT_RATIO)
        .coerceAtLeast(minTitleFontSize)
        .coerceAtMost(maxTitleFontSize)
}

private fun DynamicContent.displayCharCount(): Int {
    val text = plainText.trim()
    return if (text.isEmpty()) 0 else text.codePointCount(0, text.length)
}

private fun interpolate(start: Float, end: Float, progress: Float): Float {
    val clampedProgress = progress.coerceIn(0f, 1f)
    return start + (end - start) * clampedProgress
}
