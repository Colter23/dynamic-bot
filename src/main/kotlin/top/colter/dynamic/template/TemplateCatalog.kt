package top.colter.dynamic.template

import kotlinx.serialization.Serializable
import top.colter.dynamic.PushTemplates

@Serializable
public data class TemplateFieldDto(
    val key: String,
    val label: String,
    val dependency: String? = null,
)

@Serializable
public data class TemplateKindDto(
    val key: String,
    val label: String,
    val defaultTemplate: String,
    val fields: List<String>,
)

@Serializable
public data class TemplateCatalogDto(
    val fields: List<TemplateFieldDto>,
    val kinds: List<TemplateKindDto>,
    val platforms: List<String> = emptyList(),
    val sinks: List<TemplateSinkDto> = emptyList(),
)

@Serializable
public data class TemplateSinkDto(val id: String, val name: String)

public object TemplateCatalog {
    public val fields: List<TemplateFieldDto> = listOf(
        TemplateFieldDto("draw", "绘图", "draw"),
        TemplateFieldDto("name", "发布者名称"), TemplateFieldDto("uid", "发布者 ID"),
        TemplateFieldDto("id", "内容 ID"), TemplateFieldDto("did", "动态 ID"), TemplateFieldDto("rid", "直播间 ID"),
        TemplateFieldDto("link", "主链接"), TemplateFieldDto("url", "主链接（别名）"),
        TemplateFieldDto("time", "发布时间"), TemplateFieldDto("content", "正文 / 描述"),
        TemplateFieldDto("images", "动态图片"), TemplateFieldDto("links", "附加链接"),
        TemplateFieldDto("title", "标题"), TemplateFieldDto("area", "直播分区"), TemplateFieldDto("cover", "封面"),
        TemplateFieldDto("startTime", "开播时间"), TemplateFieldDto("endTime", "下播时间"), TemplateFieldDto("duration", "时长"),
        TemplateFieldDto("video", "视频（仅链接解析可下载）", "video"), TemplateFieldDto("size", "视频大小", "video"),
        TemplateFieldDto("kind", "内容类型"), TemplateFieldDto("stats", "统计数据"),
        TemplateFieldDto("atAll", "@全体（仅已启用的订阅群，不能放入合并转发）"),
        TemplateFieldDto("play", "播放"), TemplateFieldDto("danmaku", "弹幕"), TemplateFieldDto("like", "点赞"),
        TemplateFieldDto("coin", "投币"), TemplateFieldDto("favorite", "收藏"), TemplateFieldDto("comment", "评论"),
        TemplateFieldDto("share", "分享"), TemplateFieldDto("repost", "转发"),
        TemplateFieldDto("online", "人气"), TemplateFieldDto("follow", "关注"),
    )

    public fun describe(): TemplateCatalogDto {
        val common = listOf("name", "uid", "id", "link", "url", "atAll")
        return TemplateCatalogDto(fields, TemplateContentKind.entries.map { kind ->
            val keys = when (kind) {
                TemplateContentKind.DYNAMIC -> common + listOf("draw", "did", "time", "content", "images", "links", "video", "size")
                TemplateContentKind.LIVE_STARTED -> common + listOf("draw", "rid", "time", "title", "area", "cover")
                TemplateContentKind.LIVE_ENDED -> common + listOf("rid", "title", "area", "startTime", "endTime", "duration")
                else -> common.filterNot { it == "atAll" } + listOf("draw", "cover", "title", "content", "kind", "time", "stats", "duration") +
                    if (kind == TemplateContentKind.VIDEO) listOf("video", "size") else emptyList()
            }
            val default = when (kind) {
                TemplateContentKind.DYNAMIC -> PushTemplates.DEFAULT_DYNAMIC_TEMPLATE
                TemplateContentKind.LIVE_STARTED -> PushTemplates.DEFAULT_LIVE_STARTED_TEMPLATE
                TemplateContentKind.LIVE_ENDED -> PushTemplates.DEFAULT_LIVE_ENDED_TEMPLATE
                else -> "{draw}"
            }
            TemplateKindDto(kind.name, kind.label, default, keys)
        })
    }
}
