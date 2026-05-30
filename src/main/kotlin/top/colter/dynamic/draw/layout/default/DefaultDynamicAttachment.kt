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


internal fun Layout.drawDynamicAttachments(
    attachments: List<DynamicAttachment>,
    config: DrawConfig,
    mode: DynamicRenderMode = DynamicRenderMode.ROOT,
) {
    attachments.forEach { attachment ->
        when (attachment) {
            is ImageAttachment -> drawDynamicImages(attachment.images, config)
            is VideoAttachment -> drawDynamicVideo(attachment, config, mode)
            is CardAttachment -> drawDynamicCard(attachment, config)
            else -> Unit
        }
    }
}


private fun Layout.drawDynamicImages(pics: List<ImageItem>, config: DrawConfig) {
    val imgList = pics.map { it to config.image(it.image) }
    val imgModifier = Modifier().background(config.theme.cardColor).border(2.dp, 10.dp, config.theme.borderColor).shadows(Shadow.ELEVATION_1)
    if (imgList.size == 1) imgModifier.fillMaxWidth()
    val lineCount = if (imgList.size == 1) 1 else if (imgList.size == 2 || imgList.size == 4) 2 else 3
    Grid(
        maxLineCount = lineCount,
        space = 15.dp,
        lockRatio = imgList.size != 1,
        modifier = Modifier().fillMaxWidth()
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


private fun Layout.drawDynamicVideo(video: VideoAttachment, config: DrawConfig, mode: DynamicRenderMode) {
    val cover = video.cover ?: return
    if (mode == DynamicRenderMode.FORWARD) {
        MediaSmall(
            cover = config.image(cover),
            title = video.title,
            desc = video.description,
            duration = video.durationSeconds?.toDurationText().orEmpty(),
            badge = video.badge.orEmpty(),
            accentColor = config.theme.primaryColor,
            cardColor = config.theme.cardColor,
            borderColor = config.theme.borderColor,
            secondaryTextColor = config.theme.secondaryTextColor,
        )
    } else {
        Media(
            cover = config.image(cover),
            title = video.title,
            desc = video.description,
            duration = video.durationSeconds?.toDurationText().orEmpty(),
            badge = video.badge.orEmpty(),
            info = video.metricText("play", "播放") + " " + video.metricText("danmaku", "弹幕"),
            accentColor = config.theme.primaryColor,
            cardColor = config.theme.cardColor,
            borderColor = config.theme.borderColor,
            secondaryTextColor = config.theme.secondaryTextColor,
        )
    }
}


private fun Layout.drawDynamicCard(card: CardAttachment, config: DrawConfig) {
    val cover = card.cover ?: return
    Media(
        cover = config.image(cover),
        title = card.title,
        desc = card.description,
        badge = card.badge.orEmpty(),
        coverRatio = card.coverRatio ?: Ratio.COVER_1,
        accentColor = config.theme.primaryColor,
        cardColor = config.theme.cardColor,
        borderColor = config.theme.borderColor,
        secondaryTextColor = config.theme.secondaryTextColor,
    )
}

private fun Layout.drawDynamicSmallCard(card: CardAttachment, config: DrawConfig) {
    val cover = card.cover ?: return
    MediaSmall(
        cover = config.image(cover),
        title = card.title,
        desc = card.description,
        badge = card.badge.orEmpty(),
        coverRatio = card.coverRatio ?: Ratio.COVER_1,
        accentColor = config.theme.primaryColor,
        cardColor = config.theme.cardColor,
        borderColor = config.theme.borderColor,
        secondaryTextColor = config.theme.secondaryTextColor,
    )
}

private fun Layout.drawDynamicMiniCard(card: CardAttachment, config: DrawConfig) {
    val cover = card.cover ?: return
    MediaMini(
        cover = config.image(cover),
        title = card.title,
        desc = card.description,
        badge = card.badge.orEmpty(),
        coverRatio = card.coverRatio ?: Ratio.COVER_1,
        accentColor = config.theme.primaryColor,
        cardColor = config.theme.cardColor,
        borderColor = config.theme.borderColor,
        secondaryTextColor = config.theme.secondaryTextColor,
    )
}

private fun VideoAttachment.metricText(key: String, suffix: String): String {
    val value = metrics.firstOrNull { it.key == key }?.display.orEmpty()
    return if (value.isBlank()) "" else "$value$suffix"
}

private fun Long.toDurationText(): String {
    val minutes = this / 60
    val seconds = this % 60
    return "%02d:%02d".format(minutes, seconds)
}
