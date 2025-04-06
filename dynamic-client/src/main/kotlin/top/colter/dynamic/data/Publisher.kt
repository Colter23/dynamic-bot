package top.colter.dynamic.data

import kotlinx.serialization.Serializable
import top.colter.dynamic.ImageType
import top.colter.dynamic.ImgType
import top.colter.dynamic.LazyImage
import top.colter.dynamic.enums.PublisherPlatform


/**
 * 发布者
 *
 *
 */
@Serializable
public data class Publisher(
    val platform: PublisherPlatform,
    val userId: String,
    val name: String,
    @ImgType(ImageType.USER)
    val face: LazyImage,

    @ImgType(ImageType.USER)
    val head: LazyImage? = null,
    @ImgType(ImageType.USER)
    val pendant: LazyImage? = null,
    val official: String? = null,
)
