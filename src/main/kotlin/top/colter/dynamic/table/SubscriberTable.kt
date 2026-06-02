package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.tools.nowInstant

public object SubscriberTable : IntIdTable("subscriber") {
    public val platformId: Column<String> = varchar("platform_id", 50)
    public val kind: Column<TargetKind> = enumerationByName<TargetKind>("kind", 30).default(TargetKind.OTHER)
    public val externalId: Column<String> = varchar(name = "external_id", length = 120)
    public val targetKey: Column<String> = text(name = "target_key")
    public val scopeId: Column<String?> = varchar(name = "scope_id", length = 120).nullable()
    public val threadId: Column<String?> = varchar(name = "thread_id", length = 120).nullable()
    public val accountId: Column<String?> = varchar(name = "account_id", length = 120).nullable()
    public val name: Column<String> = varchar(name = "name", length = 255)
    public val avatar: Column<MediaRef?> = registerColumn("avatar", mediaRefColumn()).nullable()
    public val state: Column<EntityState> = enumerationByName<EntityState>("state", 20).default(EntityState.ACTIVE)
    public val createTime: Column<Instant> = timestamp(name = "create_time").clientDefault { nowInstant() }
    public val createUser: Column<Int> = integer(name = "create_user")

    init {
        uniqueIndex(targetKey)
    }
}
