package top.colter.dynamic.link

import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.core.data.TargetAddress

public data class LinkParseTargetConfig(
    val id: Int,
    val address: TargetAddress,
    val triggerMode: LinkParseTriggerMode,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
    val updatedBy: String?,
)
