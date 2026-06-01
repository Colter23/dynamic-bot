package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.tools.nowInstant

public object LinkParseTargetConfigTable : IntIdTable("link_parse_target_config") {
    public val platformId: Column<String> = varchar("platform_id", 50)
    public val kind: Column<TargetKind> = enumerationByName<TargetKind>("kind", 30).default(TargetKind.OTHER)
    public val externalId: Column<String> = varchar(name = "external_id", length = 120)
    public val targetKey: Column<String> = text(name = "target_key")
    public val scopeId: Column<String?> = varchar(name = "scope_id", length = 120).nullable()
    public val threadId: Column<String?> = varchar(name = "thread_id", length = 120).nullable()
    public val accountId: Column<String?> = varchar(name = "account_id", length = 120).nullable()
    public val triggerMode: Column<LinkParseTriggerMode> =
        enumerationByName<LinkParseTriggerMode>("trigger_mode", 30).default(LinkParseTriggerMode.MENTION_ONLY)
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }
    public val updatedAt: Column<Instant> = timestamp(name = "updated_at").clientDefault { nowInstant() }
    public val updatedBy: Column<String?> = varchar(name = "updated_by", length = 120).nullable()

    init {
        uniqueIndex(targetKey)
    }
}
