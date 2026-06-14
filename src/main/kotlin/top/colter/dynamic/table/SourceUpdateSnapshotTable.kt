package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.tools.nowInstant

public object SourceUpdateSnapshotTable : Table("source_update_snapshot") {
    public val updateKeyStable: Column<String> = varchar(name = "update_key_stable", length = 1024)
    public val updateKey: Column<UpdateKey> = registerColumn("update_key_json", updateKeyColumn())
    public val sourcePlugin: Column<String> = varchar(name = "source_plugin", length = 160)
    public val publisherKey: Column<String> = varchar(name = "publisher_key", length = 512)
    public val eventType: Column<String> = varchar(name = "event_type", length = 120)
    public val sourceUpdate: Column<SourceUpdate> = registerColumn("source_update_json", sourceUpdateColumn())
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }
    public val updatedAt: Column<Instant> = timestamp(name = "updated_at").clientDefault { nowInstant() }

    override val primaryKey: PrimaryKey = PrimaryKey(updateKeyStable)

    init {
        index(isUnique = false, publisherKey, eventType)
        index(isUnique = false, updatedAt)
    }
}
