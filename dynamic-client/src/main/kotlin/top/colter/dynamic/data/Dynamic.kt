package top.colter.dynamic.data

import top.colter.dynamic.enums.PublisherPlatform
import top.colter.dynamic.enums.PublisherType


/**
 * 动态
 * @param platform 发布者平台 [PublisherPlatform]
 *
 */

public data class Dynamic(
    val platform: PublisherPlatform,
    val dynamicId: String,
    val publisher: Publisher,
    val time: Long,

    // TODO 动态内容分为五块：平台通知、标题、文字内容、媒体、附加
    val notice: String? = null,
    val title: String? = null,
    val content: DynamicContent? = null,
    val media: DynamicMedia? = null,
    val additional: DynamicAdditional? = null,

    val origin: Dynamic? = null
)






