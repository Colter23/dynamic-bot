package top.colter.dynamic.template

import top.colter.dynamic.PushTemplates
import top.colter.dynamic.TemplateOverrides
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.event.SourceUpdateDeliveryMode
import top.colter.dynamic.core.link.LinkKinds

public enum class TemplateContentKind(public val label: String) {
    DYNAMIC("动态"), LIVE_STARTED("开播"), LIVE_ENDED("下播"),
    VIDEO("视频"), LIVE("直播间"), USER("用户"), LINK("其他链接");

    public companion object {
        public fun of(update: SourceUpdate): TemplateContentKind = when (update.eventType) {
            SourceEventType.LIVE_STARTED -> LIVE_STARTED
            SourceEventType.LIVE_ENDED -> LIVE_ENDED
            else -> DYNAMIC
        }

        public fun ofLink(kind: String): TemplateContentKind = when (kind) {
            LinkKinds.DYNAMIC -> DYNAMIC
            LinkKinds.VIDEO -> VIDEO
            LinkKinds.LIVE -> LIVE
            LinkKinds.USER -> USER
            else -> LINK
        }
    }
}

public data class SelectedTemplate(val template: String, val source: String)

public object TemplateSelector {
    public fun select(
        templates: PushTemplates,
        platformId: String,
        kind: TemplateContentKind,
        purpose: SourceUpdateDeliveryMode,
    ): SelectedTemplate {
        val platform = templates.platforms.firstOrNull { it.platformId.trim().equals(platformId.trim(), true) }
        val platformPurpose = when (purpose) {
            SourceUpdateDeliveryMode.SUBSCRIPTION -> null
            SourceUpdateDeliveryMode.LINK_PREVIEW -> platform?.linkParsing
            SourceUpdateDeliveryMode.MANUAL_QUERY -> platform?.manualQuery
        }
        val globalPurpose = when (purpose) {
            SourceUpdateDeliveryMode.SUBSCRIPTION -> null
            SourceUpdateDeliveryMode.LINK_PREVIEW -> templates.linkParsing
            SourceUpdateDeliveryMode.MANUAL_QUERY -> templates.manualQuery
        }
        return listOf(
            platformPurpose?.value(kind) to "平台用途覆盖",
            globalPurpose?.value(kind) to "全局用途覆盖",
            platform?.base?.value(kind) to "平台基础模板",
            templates.value(kind) to "全局基础模板",
        ).first { !it.first.isNullOrBlank() }.let { SelectedTemplate(it.first!!, it.second) }
    }
}

internal fun TemplateOverrides.value(kind: TemplateContentKind): String = when (kind) {
    TemplateContentKind.DYNAMIC -> dynamic
    TemplateContentKind.LIVE_STARTED -> liveStarted
    TemplateContentKind.LIVE_ENDED -> liveEnded
    TemplateContentKind.VIDEO -> video
    TemplateContentKind.LIVE -> live
    TemplateContentKind.USER -> user
    TemplateContentKind.LINK -> link
}

private fun PushTemplates.value(kind: TemplateContentKind): String = when (kind) {
    TemplateContentKind.DYNAMIC -> dynamic
    TemplateContentKind.LIVE_STARTED -> liveStarted
    TemplateContentKind.LIVE_ENDED -> liveEnded
    TemplateContentKind.VIDEO -> video
    TemplateContentKind.LIVE -> live
    TemplateContentKind.USER -> user
    TemplateContentKind.LINK -> link
}
