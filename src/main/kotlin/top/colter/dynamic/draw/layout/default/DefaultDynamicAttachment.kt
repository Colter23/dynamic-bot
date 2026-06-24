package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.*
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.layout.default.component.Media
import top.colter.dynamic.draw.layout.default.component.MediaCardColors
import top.colter.dynamic.draw.layout.default.component.MediaLabelText
import top.colter.dynamic.draw.layout.default.component.MediaMini
import top.colter.dynamic.draw.layout.default.component.MediaSmall
import top.colter.dynamic.draw.layout.default.component.mediaCardColors
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*

private val attachmentSpacing: Dp = 20.dp
private val mediaCardSpacing: Dp = 28.dp
private const val singleLongImagePreviewRatio: Float = 1f / 2f

internal fun Layout.drawDynamicBlocks(
    blocks: List<DynamicBlock>,
    config: DrawConfig,
    mode: DynamicRenderMode = DynamicRenderMode.ROOT,
    bottomSpacing: Dp = 0.dp,
) {
    blocks.forEachIndexed { index, block ->
        val blockBottomSpacing = blockBottomSpacing(
            block = block,
            nextBlock = blocks.getOrNull(index + 1),
            isLast = index == blocks.lastIndex,
            bottomSpacing = bottomSpacing,
        )
        val modifier = Modifier()
            .fillMaxWidth()
            .margin(bottom = blockBottomSpacing)

        when (block) {
            is TextBlock -> drawDynamicContent(block.content, config, bottomSpacing = blockBottomSpacing)
            is ImageGridBlock -> drawDynamicImages(block.images, config, modifier)
            is MediaCardBlock -> drawDynamicMediaCard(block, config, modifier)
            is PollBlock -> drawDynamicPoll(block, config, modifier)
            is RepostBlock -> block.embedded?.let { origin ->
                Column(modifier = modifier) {
                    DefaultDynamicView(
                        update = origin,
                        config = config,
                        mode = DynamicRenderMode.FORWARD,
                        topSpacing = 0.dp,
                    )
                }
            }
        }
    }
}

private fun blockBottomSpacing(
    block: DynamicBlock,
    nextBlock: DynamicBlock?,
    isLast: Boolean,
    bottomSpacing: Dp,
): Dp {
    if (isLast) return bottomSpacing
    return if (block is MediaCardBlock && nextBlock is MediaCardBlock) {
        mediaCardSpacing
    } else {
        attachmentSpacing
    }
}


private fun Layout.drawDynamicImages(
    pics: List<ImageItem>,
    config: DrawConfig,
    modifier: Modifier = Modifier().fillMaxWidth(),
) {
    val imgList = pics.map { it to config.image(it.image) }
    val colors = mediaCardColors(config.theme)
    val imgModifier = Modifier().background(colors.cardColor).border(2.dp, 10.dp, colors.borderColor).shadows(Shadow.ELEVATION_1)
    if (imgList.size == 1) imgModifier.fillMaxWidth()
    val lineCount = if (imgList.size == 1) 1 else if (imgList.size == 2 || imgList.size == 4) 2 else 3
    Grid(
        maxLineCount = lineCount,
        space = 15.dp,
        lockRatio = imgList.size != 1,
        modifier = modifier
    ) {
        for ((pic, image) in imgList) {
            val longImage = pic.isLongImage(image)
            DynamicImageTile(
                image = image,
                badge = pic.badge,
                lineCount = lineCount,
                ratio = when {
                    imgList.size == 1 && longImage -> singleLongImagePreviewRatio
                    imgList.size == 1 -> 0f
                    else -> Ratio.SQUARE
                },
                cropTop = longImage,
                colors = colors,
                modifier = imgModifier
            )
        }
    }
}

private fun Layout.DynamicImageTile(
    image: Image,
    badge: String? = null,
    lineCount: Int,
    ratio: Float = 0f,
    cropTop: Boolean = false,
    colors: MediaCardColors,
    modifier: Modifier,
) = Box(modifier = modifier) {
    val imgModifier = Modifier().border(2.dp, 10.dp, colors.coverBorderColor)

    // 原比例图片限高，长图预览使用固定比例，避免等比缩放成左侧窄条。
    if (ratio == 0f && image.height > image.width * 2) imgModifier.maxHeight(2000.dp)

    // 绘制图片：长图取顶部，普通图片居中裁剪。
    Image(
        image = image,
        ratio = ratio,
        cropAlignment = if (cropTop) LayoutAlignment.TOP else LayoutAlignment.CENTER,
        modifier = imgModifier,
    )

    // 绘制右下角标签
    if (!badge.isNullOrBlank()) {
        val badgeFontSize = (36 - 6 * lineCount).dp
        val badgeLineHeight = (48 - 8 * lineCount).dp
        Box(
            alignment = LayoutAlignment.RIGHT_BOTTOM,
            modifier = Modifier()
                .margin((25 - 5 * lineCount).dp)
                .padding(horizontal = (20 - 3 * lineCount).dp)
                .background(color = colors.overlayPillColor)
                .radius((13 - 2 * lineCount).dp)
        ) {
            MediaLabelText(
                text = badge,
                fontSize = badgeFontSize,
                lineHeight = badgeLineHeight,
                color = colors.overlayTextColor,
                maxWidth = 500.dp,
            )
        }
    }
}

private fun ImageItem.isLongImage(image: Image): Boolean {
    val width = width ?: image.width
    val height = height ?: image.height
    return width > 0 && height > width * 2
}

private fun Layout.drawDynamicMediaCard(
    block: MediaCardBlock,
    config: DrawConfig,
    modifier: Modifier = Modifier().fillMaxWidth(),
) {
    val card = block.card
    val cover = card.cover?.let(config::image)
    val duration = card.durationSeconds?.toDurationText().orEmpty()
    val colors = mediaCardColors(config.theme)
    val info = card.info ?: listOf(card.metricText("play", "播放"), card.metricText("danmaku", "弹幕"))
        .filter { it.isNotBlank() }
        .joinToString(" ")
    when (block.style) {
        MediaCardStyle.LARGE -> {
            if (cover == null) {
                MediaSmall(
                    cover = null,
                    title = card.title,
                    desc = card.description,
                    duration = duration,
                    badge = card.badge.orEmpty(),
                    colors = colors,
                    modifier = modifier,
                )
            } else {
                Media(
                    cover = cover,
                    title = card.title,
                    desc = card.description,
                    duration = duration,
                    badge = card.badge.orEmpty(),
                    info = info,
                    coverRatio = card.coverRatio ?: Ratio.COVER_1,
                    colors = colors,
                    modifier = modifier,
                )
            }
        }
        MediaCardStyle.SMALL -> MediaSmall(
            cover = cover,
            title = card.title,
            desc = card.description,
            duration = duration,
            badge = card.badge.orEmpty(),
            coverRatio = card.coverRatio ?: Ratio.COVER_2,
            colors = colors,
            modifier = modifier,
        )
        MediaCardStyle.MINI -> MediaMini(
            cover = cover,
            title = card.title,
            desc = card.description,
            badge = card.badge.orEmpty(),
            coverRatio = card.coverRatio ?: Ratio.COVER_2,
            colors = colors,
            modifier = modifier,
        )
    }
}

private fun Layout.drawDynamicPoll(
    poll: PollBlock,
    config: DrawConfig,
    modifier: Modifier = Modifier().fillMaxWidth(),
) {
    val options = poll.options.joinToString("\n") { option ->
        listOfNotNull(option.text, option.displayVotes).joinToString(" ")
    }.ifBlank { poll.status.name }
    MediaSmall(
        cover = null,
        title = poll.title,
        desc = options,
        badge = "投票",
        colors = mediaCardColors(config.theme),
        modifier = modifier,
    )
}

private fun DynamicMediaCard.metricText(key: String, suffix: String): String {
    val value = metrics.firstOrNull { it.key == key }?.display.orEmpty()
    return if (value.isBlank()) "" else "$value$suffix"
}

private fun Long.toDurationText(): String {
    val minutes = this / 60
    val seconds = this % 60
    return "%02d:%02d".format(minutes, seconds)
}
