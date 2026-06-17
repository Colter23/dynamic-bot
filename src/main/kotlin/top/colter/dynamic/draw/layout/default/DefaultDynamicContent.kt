package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.Image
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.dynamic.core.data.DynamicBlock
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNode
import top.colter.dynamic.core.data.DynamicContentIcon
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
import top.colter.skiko.layout.AutoSizeRichText
import top.colter.skiko.layout.Layout
import top.colter.skiko.margin
import top.colter.skiko.px
import top.colter.skiko.withAlpha

private const val SHORT_CONTENT_CHARS = 16
private const val NORMAL_CONTENT_CHARS = 120
private const val LONG_CONTENT_CHARS = 420
private const val MAX_CONTENT_FONT_SIZE = 48f
private const val NORMAL_CONTENT_FONT_SIZE = 36f
private const val MIN_CONTENT_FONT_SIZE = 30f
private const val SHORT_EMOJI_ONLY_COUNT = 4
private const val NORMAL_EMOJI_ONLY_COUNT = 12
private const val MAX_EMOJI_ONLY_FONT_SIZE = 96f
private const val NORMAL_EMOJI_ONLY_FONT_SIZE = 76f
private const val MIN_EMOJI_ONLY_FONT_SIZE = 56f
private const val IMAGE_EMOJI_FONT_SIZE_SCALE = 1.16f
private const val TITLE_TO_CONTENT_RATIO = 1.2f
private const val FONT_SIZE_STEP = 0.5f
private val minTitleFontSize = 38.dp
private val maxTitleFontSize = 56.dp

internal fun Layout.drawDynamicContent(
    content: DynamicContent,
    config: DrawConfig,
    bottomSpacing: Dp = 0.dp,
) {
    if (content.nodes.isEmpty()) return

    val contentParts = content.resolveParts(config)
    val fontSizeRange = dynamicContentFontSizeRange(content)
    AutoSizeRichText(
        minFontSize = fontSizeRange.min,
        maxFontSize = fontSizeRange.max,
        fontSizeStep = FONT_SIZE_STEP.dp,
        intrinsicAlignment = Alignment.JUSTIFY,
        modifier = Modifier().margin(bottom = bottomSpacing).fillMaxWidth(),
    ) { fontSize ->
        buildDynamicContentParagraph(contentParts, config, fontSize)
    }
}

internal fun Layout.drawDynamicTitle(
    title: String,
    blocks: List<DynamicBlock>,
    config: DrawConfig,
    bottomSpacing: Dp = 0.dp,
) {
    val fontSizeRange = dynamicTitleFontSizeRange(blocks)
    AutoSizeRichText(
        minFontSize = fontSizeRange.min,
        maxFontSize = fontSizeRange.max,
        fontSizeStep = FONT_SIZE_STEP.dp,
        maxLinesCount = 2,
        modifier = Modifier().margin(bottom = bottomSpacing),
    ) { fontSize ->
        val style = TextStyle()
            .setColor(config.theme.textColor)
            .setFontSize(fontSize.px)
        RichParagraphBuilder(style)
            .addText(title, style)
            .build()
    }
}

private fun buildDynamicContentParagraph(
    parts: List<DynamicContentPart>,
    config: DrawConfig,
    fontSize: Dp,
) = RichParagraphBuilder(
    TextStyle()
        .setColor(config.theme.textColor)
        .setFontSize(fontSize.px)
).also { paragraph ->
    val style = TextStyle()
        .setColor(config.theme.textColor)
        .setFontSize(fontSize.px)
    val linkStyle = TextStyle()
        .setColor(config.theme.linkColor)
        .setFontSize(fontSize.px)

    parts.forEach { part ->
        when (part) {
            is DynamicContentPart.Text -> paragraph.addText(part.text, if (part.link) linkStyle else style)
            is DynamicContentPart.Emoji -> paragraph.addEmoji(part.text, part.image, linkStyle, IMAGE_EMOJI_FONT_SIZE_SCALE)
            is DynamicContentPart.Icon -> {
                paragraph.addEmoji(part.alt, part.image, linkStyle)
                paragraph.addText(" ", linkStyle)
            }
        }
    }
}.build()

private fun DynamicContent.resolveParts(config: DrawConfig): List<DynamicContentPart> {
    return buildList {
        nodes.forEach {
            when (it) {
                is DynamicContentNodeText -> add(DynamicContentPart.Text(it.text))
                is DynamicContentNodeLink -> {
                    val icon = it.icon
                    icon?.resolveImage(config)?.let { image ->
                        add(DynamicContentPart.Icon(icon.alt, image))
                    }
                    add(DynamicContentPart.Text(it.text, link = true))
                }
                is DynamicContentNodeMention -> add(DynamicContentPart.Text(it.text, link = true))
                is DynamicContentNodeTag -> {
                    val icon = it.icon
                    icon?.resolveImage(config)?.let { image ->
                        add(DynamicContentPart.Icon(icon.alt, image))
                    }
                    add(DynamicContentPart.Text(it.text, link = true))
                }
                is DynamicContentNodeEmoji -> {
                    val image = it.image
                    if (image == null) {
                        add(DynamicContentPart.Text(it.text))
                    } else {
                        add(DynamicContentPart.Emoji(it.text, config.image(image)))
                    }
                }
            }
        }
    }
}

private sealed class DynamicContentPart {
    data class Text(val text: String, val link: Boolean = false) : DynamicContentPart()
    data class Emoji(val text: String, val image: Image) : DynamicContentPart()
    data class Icon(val alt: String, val image: Image) : DynamicContentPart()
}

private fun DynamicContentIcon.resolveImage(config: DrawConfig): Image? {
    return config.platformAssetImage(platformId, assetKey)
}

private data class DynamicFontSizeRange(
    val min: Dp,
    val max: Dp,
)

private fun dynamicContentFontSizeRange(content: DynamicContent): DynamicFontSizeRange {
    val charCount = content.displayCharCount()
    return if (content.isImageEmojiOnly()) {
        dynamicImageEmojiOnlyFontSizeRange(charCount)
    } else {
        dynamicContentFontSizeRange(charCount)
    }
}

private fun dynamicContentFontSizeRange(charCount: Int): DynamicFontSizeRange {
    val base = dynamicContentBaseFontSize(charCount)
    val min = (base - dynamicContentFontSizeDrop(charCount).dp)
        .coerceAtLeast(MIN_CONTENT_FONT_SIZE.dp)
    val max = (base + dynamicContentFontSizeBoost(charCount).dp)
        .coerceAtMost(MAX_CONTENT_FONT_SIZE.dp)
    return DynamicFontSizeRange(
        min = min.coerceAtMost(max),
        max = max,
    )
}

private fun dynamicContentBaseFontSize(charCount: Int): Dp {
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

private fun dynamicImageEmojiOnlyFontSizeRange(emojiCount: Int): DynamicFontSizeRange {
    val max = when {
        emojiCount <= SHORT_EMOJI_ONLY_COUNT -> MAX_EMOJI_ONLY_FONT_SIZE
        emojiCount <= NORMAL_EMOJI_ONLY_COUNT -> interpolate(
            start = MAX_EMOJI_ONLY_FONT_SIZE,
            end = NORMAL_EMOJI_ONLY_FONT_SIZE,
            progress = (emojiCount - SHORT_EMOJI_ONLY_COUNT).toFloat() / (NORMAL_EMOJI_ONLY_COUNT - SHORT_EMOJI_ONLY_COUNT),
        )
        else -> MIN_EMOJI_ONLY_FONT_SIZE
    }
    val min = (max - 16f).coerceAtLeast(MAX_CONTENT_FONT_SIZE)
    return DynamicFontSizeRange(
        min = min.dp,
        max = max.dp,
    )
}

private fun dynamicTitleFontSizeRange(blocks: List<DynamicBlock>): DynamicFontSizeRange {
    val bodyCharCount = blocks
        .filterIsInstance<TextBlock>()
        .sumOf { it.content.displayCharCount() }
    val bodyRange = dynamicContentFontSizeRange(bodyCharCount)
    val min = (bodyRange.min * TITLE_TO_CONTENT_RATIO)
        .coerceAtLeast(minTitleFontSize)
        .coerceAtMost(maxTitleFontSize)
    val max = (bodyRange.max * TITLE_TO_CONTENT_RATIO)
        .coerceAtLeast(minTitleFontSize)
        .coerceAtMost(maxTitleFontSize)
    return DynamicFontSizeRange(
        min = min.coerceAtMost(max),
        max = max,
    )
}

private fun dynamicContentFontSizeDrop(charCount: Int): Float {
    return when {
        charCount <= SHORT_CONTENT_CHARS -> 0f
        charCount <= NORMAL_CONTENT_CHARS -> interpolate(
            start = 2f,
            end = 6f,
            progress = (charCount - SHORT_CONTENT_CHARS).toFloat() / (NORMAL_CONTENT_CHARS - SHORT_CONTENT_CHARS),
        )
        charCount <= LONG_CONTENT_CHARS -> 8f
        else -> 0f
    }
}

private fun dynamicContentFontSizeBoost(charCount: Int): Float {
    return when {
        charCount <= SHORT_CONTENT_CHARS -> 0f
        charCount <= NORMAL_CONTENT_CHARS -> interpolate(
            start = 0f,
            end = 1.5f,
            progress = (charCount - SHORT_CONTENT_CHARS).toFloat() / (NORMAL_CONTENT_CHARS - SHORT_CONTENT_CHARS),
        )
        else -> 1f
    }
}

private fun DynamicContent.displayCharCount(): Int {
    val firstVisibleIndex = nodes.indexOfFirst { it.hasVisibleContent() }
    if (firstVisibleIndex < 0) return 0
    val lastVisibleIndex = nodes.indexOfLast { it.hasVisibleContent() }

    return nodes.withIndex().sumOf { (index, node) ->
        if (index !in firstVisibleIndex..lastVisibleIndex) {
            0
        } else {
            node.displayCharCount(
                trimStart = index == firstVisibleIndex,
                trimEnd = index == lastVisibleIndex,
            )
        }
    }
}

private fun DynamicContentNode.hasVisibleContent(): Boolean {
    return when (this) {
        is DynamicContentNodeEmoji -> image != null || text.isNotBlank()
        else -> text.isNotBlank()
    }
}

private fun DynamicContentNode.displayCharCount(
    trimStart: Boolean = false,
    trimEnd: Boolean = false,
): Int {
    return when (this) {
        is DynamicContentNodeEmoji -> if (image != null) {
            1
        } else {
            text.trimForDisplayCount(trimStart, trimEnd).codePointCount()
        }
        else -> text.trimForDisplayCount(trimStart, trimEnd).codePointCount()
    }
}

private fun DynamicContent.isImageEmojiOnly(): Boolean {
    val visibleNodes = nodes.filter { it.hasVisibleContent() }
    return visibleNodes.isNotEmpty() && visibleNodes.all { it is DynamicContentNodeEmoji && it.image != null }
}

private fun String.trimForDisplayCount(trimStart: Boolean, trimEnd: Boolean): String {
    var value = this
    if (trimStart) value = value.trimStart()
    if (trimEnd) value = value.trimEnd()
    return value
}

private fun String.codePointCount(): Int = codePointCount(0, length)

private fun interpolate(start: Float, end: Float, progress: Float): Float {
    val clampedProgress = progress.coerceIn(0f, 1f)
    return start + (end - start) * clampedProgress
}
