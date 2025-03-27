package top.colter.dynamic.data

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
    val face: LazyImage,

    val pendant: LazyImage?,
    val official: String?,
)
