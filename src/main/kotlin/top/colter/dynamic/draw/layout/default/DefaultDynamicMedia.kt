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


internal fun Layout.drawDynamicMedia(
    media: DynamicMedia,
    config: DrawConfig,
    mode: DynamicRenderMode = DynamicRenderMode.ROOT,
) {

    media.pics?.let { pics -> drawDynamicMediaPic(pics, config) }
    media.video?.let { video -> drawDynamicMediaVideo(video, config, mode) }
//    media.article?.let { article -> drawDynamicMediaArticle(article) }

    media.card?.let { card -> drawDynamicMediaCard(card, config) }
    media.smallCard?.let { card -> drawDynamicMediaSmallCard(card, config) }
    media.miniCard?.let { card -> drawDynamicMediaMiniCard(card, config) }

}


private fun Layout.drawDynamicMediaPic(pics: List<DynamicMediaPic>, config: DrawConfig) {
    val imgList = pics.map { it to config.image(it.pic) }
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
            DynamicMediaPicItem(
                image = image,
                badge = pic.badge,
                lineCount = lineCount,
                ratio = if (imgList.size == 1) 0f else Ratio.SQUARE,
                modifier = imgModifier
            )
        }
    }
}

private fun Layout.DynamicMediaPicItem(
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


private fun Layout.drawDynamicMediaVideo(video: DynamicMediaVideo, config: DrawConfig, mode: DynamicRenderMode) {
    if (mode == DynamicRenderMode.FORWARD) {
        MediaSmall(
            cover = config.image(video.cover),
            title = video.title,
            desc = video.description,
            duration = video.duration,
            badge = video.badge,
            accentColor = config.theme.primaryColor,
            cardColor = config.theme.cardColor,
            borderColor = config.theme.borderColor,
            secondaryTextColor = config.theme.secondaryTextColor,
        )
    } else {
        Media(
            cover = config.image(video.cover),
            title = video.title,
            desc = video.description,
            duration = video.duration,
            badge = video.badge,
            info = "${video.stats.play}播放 ${video.stats.danmaku}弹幕",
            accentColor = config.theme.primaryColor,
            cardColor = config.theme.cardColor,
            borderColor = config.theme.borderColor,
            secondaryTextColor = config.theme.secondaryTextColor,
        )
    }
}


private fun Layout.drawDynamicMediaCard(card: DynamicMediaCard, config: DrawConfig) {
    Media(
        cover = config.image(card.cover),
        title = card.title,
        desc = card.description,
        badge = card.badge,
        coverRatio = card.coverRatio ?: Ratio.COVER_1,
        accentColor = config.theme.primaryColor,
        cardColor = config.theme.cardColor,
        borderColor = config.theme.borderColor,
        secondaryTextColor = config.theme.secondaryTextColor,
    )
}

private fun Layout.drawDynamicMediaSmallCard(card: DynamicMediaCard, config: DrawConfig) {
    MediaSmall(
        cover = config.image(card.cover),
        title = card.title,
        desc = card.description,
        badge = card.badge,
        coverRatio = card.coverRatio ?: Ratio.COVER_1,
        accentColor = config.theme.primaryColor,
        cardColor = config.theme.cardColor,
        borderColor = config.theme.borderColor,
        secondaryTextColor = config.theme.secondaryTextColor,
    )
}

private fun Layout.drawDynamicMediaMiniCard(card: DynamicMediaCard, config: DrawConfig) {
    MediaMini(
        cover = config.image(card.cover),
        title = card.title,
        desc = card.description,
        badge = card.badge,
        coverRatio = card.coverRatio ?: Ratio.COVER_1,
        accentColor = config.theme.primaryColor,
        cardColor = config.theme.cardColor,
        borderColor = config.theme.borderColor,
        secondaryTextColor = config.theme.secondaryTextColor,
    )
}
