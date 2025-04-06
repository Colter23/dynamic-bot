package top.colter.dynamic.data

import kotlinx.serialization.Serializable
import top.colter.dynamic.ImageType
import top.colter.dynamic.ImgType
import top.colter.dynamic.LazyImage

/**
 * 动态媒体
 *
 * @param pics 图片
 * @param video 视频
 * @param article 文章
 */
@Serializable
public data class DynamicMedia(
    val pics: List<DynamicMediaPic>? = null,
    val video: DynamicMediaVideo? = null,
//    val article: DynamicMediaArticle? = null,

    val card: DynamicMediaCard? = null,
    val smallCard: DynamicMediaCard? = null,
    val miniCard: DynamicMediaCard? = null,
)

/**
 * 动态媒体图片
 *
 * @param pic 图片
 * @param width 宽
 * @param height 高
 * @param size 大小
 * @param badge 徽章（动图/长图/视频）
 */
@Serializable
public data class DynamicMediaPic(
    @ImgType(ImageType.IMAGES)
    val pic: LazyImage,
    val width: Int,
    val height: Int,
    val size: Float? = null,
    val badge: String? = null,
)

/**
 * 动态媒体视频
 *
 */
@Serializable
public data class DynamicMediaVideo(
    val id: String,
    val title: String,
    val description: String,
    @ImgType(ImageType.COVER)
    val cover: LazyImage,
    val duration: String,
    val badge: String,
    val stats: DynamicMediaVideoStats,
    val link: String
)

/**
 * 统计
 * @param danmaku 弹幕数
 * @param play 播放数
 */
@Serializable
public data class DynamicMediaVideoStats(
    val play: String,
    val danmaku: String,
    val like: String,
)


///**
// * 动态媒体文章
// *
// */
//public data class DynamicMediaArticle(
//    val id: String,
//    val title: String,
//    val description: String,
//    @ImgType(ImageType.COVER)
//    val cover: LazyImage,
//    val badge: String,
//    val link: String
//)


/**
 * 动态媒体卡片
 *
 */
@Serializable
public data class DynamicMediaCard(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val badge: String,
    @ImgType(ImageType.COVER)
    val cover: LazyImage,
    val coverRatio: Float? = null,
    val info: String? = null,
    val link: String
)
