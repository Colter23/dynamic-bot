package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.tools.nowInstant

public object PublisherTable : IntIdTable("publisher") {
    public val platformId: Column<String> = varchar("platform_id", 50)
    public val kind: Column<PublisherKind> = enumerationByName<PublisherKind>("kind", 30).default(PublisherKind.USER)
    public val externalId: Column<String> = varchar(name = "external_id", length = 100)
    public val name: Column<String> = varchar(name = "name", length = 255)
    public val official: Column<String?> = varchar(name = "official", length = 255).nullable()
    public val state: Column<EntityState> = enumerationByName<EntityState>("state", 20).default(EntityState.ACTIVE)
    public val avatar: Column<MediaRef> = registerColumn("avatar", mediaRefColumn())
    public val banner: Column<MediaRef?> = registerColumn("banner", mediaRefColumn()).nullable()
    public val pendant: Column<MediaRef?> = registerColumn("pendant", mediaRefColumn()).nullable()
    public val createTime: Column<Instant> = timestamp(name = "create_time").clientDefault { nowInstant() }
    public val createUser: Column<Int> = integer(name = "create_user")

    init {
        uniqueIndex(platformId, kind, externalId)
    }
}
