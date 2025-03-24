package top.colter.dynamic.data

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
    val face: String,

    val pendant: String?,
    val official: String?,
)
