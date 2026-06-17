package top.colter.dynamic.util

import top.colter.dynamic.core.data.DynamicMetric

internal fun List<DynamicMetric>.formatMetricInfo(limit: Int? = null): String {
    val source = if (limit == null) this else take(limit)
    return source.mapNotNull { metric ->
        metric.display?.takeIf { it.isNotBlank() }?.let { display ->
            when (metric.key) {
                "play" -> "${display}播放"
                "danmaku" -> "${display}弹幕"
                "like" -> "${display}点赞"
                "coin" -> "${display}投币"
                "favorite" -> "${display}收藏"
                "comment", "reply" -> "${display}评论"
                "forward", "share" -> "${display}转发"
                "online" -> "${display}在线"
                "follow", "attention" -> "${display}关注"
                else -> display
            }
        }
    }.joinToString(" / ")
}
