package top.colter.dynamic.data

import top.colter.dynamic.ImageType
import top.colter.dynamic.ImgType
import top.colter.dynamic.LazyImage
import top.colter.dynamic.enums.PublisherPlatform


/**
 * 发布者
 *
 *
 */
public data class Publisher(
    val platform: PublisherPlatform,
    val userId: String,
    val name: String,
    @ImgType(ImageType.IMAGES)
    val face: LazyImage,

    @ImgType(ImageType.IMAGES)
    val head: LazyImage? = null,
    @ImgType(ImageType.IMAGES)
    val pendant: LazyImage? = null,
    val official: String? = null,
)
