package top.colter.dynamic.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.dynamic.data.*
import top.colter.dynamic.draw.component.Media
import top.colter.dynamic.draw.component.MediaMini
import top.colter.dynamic.draw.component.MediaSmall
import top.colter.dynamic.draw.tools.makeImage
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Ratio
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*


fun Layout.drawDynamicMedia(media: DynamicMedia) {

    media.pics?.let { pics -> drawDynamicMediaPic(pics) }
    media.video?.let { video -> drawDynamicMediaVideo(video) }
//    media.article?.let { article -> drawDynamicMediaArticle(article) }

    media.card?.let { card -> drawDynamicMediaCard(card) }
    media.smallCard?.let { card -> drawDynamicMediaSmallCard(card) }
    media.miniCard?.let { card -> drawDynamicMediaMiniCard(card) }

}


fun Layout.drawDynamicMediaPic(pics: List<DynamicMediaPic>) {
    val imgList = pics.map { it to it.pic.image?.makeImage()!! }
    val imgModifier = Modifier().background(Color.WHITE.withAlpha(0.6f)).border(2.dp, 10.dp).shadows(Shadow.ELEVATION_1)
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

fun Layout.DynamicMediaPicItem(
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


fun Layout.drawDynamicMediaVideo(video: DynamicMediaVideo) {
    if (containsEnv("forward")) {
        MediaSmall(
            cover = video.cover.image?.makeImage()!!,
            title = video.title,
            desc = video.description,
            duration = video.duration,
            badge = video.badge
        )
    } else {
        Media(
            cover = video.cover.image?.makeImage()!!,
            title = video.title,
            desc = video.description,
            duration = video.duration,
            badge = video.badge,
            info = "${video.stats.play}播放 ${video.stats.danmaku}弹幕"
        )
    }
}


fun Layout.drawDynamicMediaCard(card: DynamicMediaCard) {
    Media(
        cover = card.cover.image?.makeImage()!!,
        title = card.title,
        desc = card.description,
        badge = card.badge,
        coverRatio = card.coverRatio ?: Ratio.COVER_1
    )
}

fun Layout.drawDynamicMediaSmallCard(card: DynamicMediaCard) {
    MediaSmall(
        cover = card.cover.image?.makeImage()!!,
        title = card.title,
        desc = card.description,
        badge = card.badge,
        coverRatio = card.coverRatio ?: Ratio.COVER_1
    )
}

fun Layout.drawDynamicMediaMiniCard(card: DynamicMediaCard) {
    MediaMini(
        cover = card.cover.image?.makeImage()!!,
        title = card.title,
        desc = card.description,
        badge = card.badge,
        coverRatio = card.coverRatio ?: Ratio.COVER_1
    )
}
