package top.colter.dynamic.table

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaRef

public object PublisherLiveStatusTable : IntIdTable("publisher_live_status") {
    public val publisherId: Column<EntityID<Int>> = reference("publisher_id", PublisherTable)
    public val roomId: Column<String> = varchar(name = "room_id", length = 100)
    public val status: Column<LiveStatus> = enumerationByName<LiveStatus>("status", 20)
    public val title: Column<String> = varchar(name = "title", length = 300).default("")
    public val cover: Column<MediaRef?> = registerColumn("cover_json", mediaRefColumn()).nullable()
    public val area: Column<String?> = varchar(name = "area", length = 120).nullable()
    public val startedAt: Column<Long?> = long(name = "started_at").nullable()
    public val lastObservedAt: Column<Long> = long(name = "last_observed_at")

    init {
        uniqueIndex(publisherId)
        index(isUnique = false, status)
    }
}
