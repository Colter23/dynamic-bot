package top.colter.dynamic.data

import top.colter.dynamic.ImageType
import top.colter.dynamic.ImgType
import top.colter.dynamic.LazyImage

/**
 * 动态媒体
 *
 * @param pics 图片
 * @param video 视频
 * @param article 文章
 * @param card 卡片（预留）
 * @param smallCard 小卡片（预留）
 */
public data class DynamicMedia(
    val pics: List<DynamicMediaPic>? = null,
    val video: DynamicMediaVideo? = null,
    val article: String? = null,
    val card: String? = null,
    val smallCard: String? = null,
    val miniCard: String? = null,
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
public data class DynamicMediaVideo(
    val id: String,
    val title: String,
    val description: String,
//    @ImgType(ImageType.COVER)
    @ImgType(ImageType.IMAGES)
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
public data class DynamicMediaVideoStats(
    val play: String,
    val danmaku: String,
    val like: String,
)