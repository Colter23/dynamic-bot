package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.tools.nowInstant

public object PublisherLiveRecordTable : IntIdTable("publisher_live_record") {
    public val publisherId: Column<EntityID<Int>> = reference("publisher_id", PublisherTable)
    public val roomId: Column<String> = varchar(name = "room_id", length = 100)
    public val title: Column<String> = varchar(name = "title", length = 300).default("")
    public val cover: Column<MediaRef?> = registerColumn("cover_json", mediaRefColumn()).nullable()
    public val area: Column<String?> = varchar(name = "area", length = 120).nullable()
    public val startedAt: Column<Long> = long(name = "started_at")
    public val endedAt: Column<Long?> = long(name = "ended_at").nullable()
    public val lastObservedAt: Column<Long> = long(name = "last_observed_at")
    public val startUpdateKey: Column<String?> = varchar(name = "start_update_key", length = 500).nullable()
    public val endUpdateKey: Column<String?> = varchar(name = "end_update_key", length = 500).nullable()
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }
    public val updatedAt: Column<Instant> = timestamp(name = "updated_at").clientDefault { nowInstant() }

    init {
        uniqueIndex(publisherId, roomId, startedAt)
        index(isUnique = false, publisherId, startedAt)
        index(isUnique = false, endedAt)
    }
}
