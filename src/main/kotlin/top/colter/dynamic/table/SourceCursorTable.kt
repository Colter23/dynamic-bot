package top.colter.dynamic.table

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

public object SourceCursorTable : IntIdTable("source_cursor") {
    public val publisherId: Column<EntityID<Int>> = reference("publisher_id", PublisherTable)
    public val sourceKey: Column<String> = varchar(name = "source_key", length = 80)
    public val eventType: Column<String> = varchar(name = "event_type", length = 80)
    public val lastSeenUpdateKey: Column<String> = varchar(name = "last_seen_update_key", length = 500)
    public val lastSeenAt: Column<Long> = long(name = "last_seen_at")
    public val recentUpdateKeys: Column<String> = text(name = "recent_update_keys").default("")

    init {
        uniqueIndex(publisherId, sourceKey, eventType)
    }
}
