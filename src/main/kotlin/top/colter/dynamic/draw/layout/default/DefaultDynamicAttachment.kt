package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.*
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.layout.default.component.Media
import top.colter.dynamic.draw.layout.default.component.MediaMini
import top.colter.dynamic.draw.layout.default.component.MediaSmall
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*

private val attachmentSpacing: Dp = 20.dp

internal fun Layout.drawDynamicBlocks(
    blocks: List<DynamicBlock>,
    config: DrawConfig,
    mode: DynamicRenderMode = DynamicRenderMode.ROOT,
    bottomSpacing: Dp = 0.dp,
) {
    blocks.forEachIndexed { index, block ->
        val modifier = Modifier()
            .fillMaxWidth()
            .margin(bottom = if (index < blocks.lastIndex) attachmentSpacing else bottomSpacing)

        when (block) {
            is TextBlock -> drawDynamicContent(block.content, config, bottomSpacing = if (index < blocks.lastIndex) attachmentSpacing else bottomSpacing)
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


private fun Layout.drawDynamicImages(
    pics: List<ImageItem>,
    config: DrawConfig,
    modifier: Modifier = Modifier().fillMaxWidth(),
) {
    val imgList = pics.map { it to config.image(it.image) }
    val imgModifier = Modifier().background(config.theme.cardColor).border(2.dp, 10.dp, config.theme.borderColor).shadows(Shadow.ELEVATION_1)
    if (imgList.size == 1) imgModifier.fillMaxWidth()
    val lineCount = if (imgList.size == 1) 1 else if (imgList.size == 2 || imgList.size == 4) 2 else 3
    Grid(
        maxLineCount = lineCount,
        space = 15.dp,
        lockRatio = imgList.size != 1,
        modifier = modifier
    ) {
        for ((pic, image) in imgList) {
            DynamicImageTile(
                image = image,
                badge = pic.badge,
                lineCount = lineCount,
                ratio = if (imgList.size == 1) 0f else Ratio.SQUARE,
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
    modifier: Modifier,
) = Box(modifier = modifier) {
    val imgModifier = Modifier().border(2.dp, 10.dp)

    // 图片限高，最高为绘图宽度的两倍 2000dp
    if (image.height > image.width * 2) imgModifier.maxHeight(2000.dp)

    // 绘制图片
    Image(image = image, ratio = ratio, modifier = imgModifier)

    // 绘制右下角标签
    if (!badge.isNullOrBlank()) {
        Box(
            alignment = LayoutAlignment.RIGHT_BOTTOM,
            modifier = Modifier()
                .margin((25 - 5 * lineCount).dp)
                .padding(horizontal = (20 - 3 * lineCount).dp, vertical = (4 - 1 * lineCount).dp)
                .background(color = Color.BLACK.withAlpha(0.5f))
                .border(0.dp, (13 - 2 * lineCount).dp)
        ) {
            Text(
                text = badge,
                fontSize = (36 - 6 * lineCount).dp,
                color = Color.WHITE,
                modifier = Modifier().maxWidth(500.dp)
            )
        }
    }
}


private fun Layout.drawDynamicMediaCard(
    block: MediaCardBlock,
    config: DrawConfig,
    modifier: Modifier = Modifier().fillMaxWidth(),
) {
    val card = block.card
    val cover = card.cover?.let(config::image)
    val duration = card.durationSeconds?.toDurationText().orEmpty()
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
                    accentColor = config.theme.primaryColor,
                    cardColor = config.theme.cardColor,
                    borderColor = config.theme.borderColor,
                    titleColor = config.theme.textColor,
                    secondaryTextColor = config.theme.secondaryTextColor,
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
                    accentColor = config.theme.primaryColor,
                    cardColor = config.theme.cardColor,
                    borderColor = config.theme.borderColor,
                    titleColor = config.theme.textColor,
                    secondaryTextColor = config.theme.secondaryTextColor,
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
            accentColor = config.theme.primaryColor,
            cardColor = config.theme.cardColor,
            borderColor = config.theme.borderColor,
            titleColor = config.theme.textColor,
            secondaryTextColor = config.theme.secondaryTextColor,
            modifier = modifier,
        )
        MediaCardStyle.MINI -> MediaMini(
            cover = cover,
            title = card.title,
            desc = card.description,
            badge = card.badge.orEmpty(),
            coverRatio = card.coverRatio ?: Ratio.COVER_2,
            accentColor = config.theme.primaryColor,
            cardColor = config.theme.cardColor,
            borderColor = config.theme.borderColor,
            titleColor = config.theme.textColor,
            secondaryTextColor = config.theme.secondaryTextColor,
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
        accentColor = config.theme.primaryColor,
        cardColor = config.theme.cardColor,
        borderColor = config.theme.borderColor,
        titleColor = config.theme.textColor,
        secondaryTextColor = config.theme.secondaryTextColor,
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
