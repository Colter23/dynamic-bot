package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.tools.nowInstant

public object MessageOutboxTable : Table("message_outbox") {
    public val messageId: Column<String> = varchar(name = "message_id", length = 255)
    public val message: Column<Message> = registerColumn("message_json", messageColumn())
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }
    public val updatedAt: Column<Instant> = timestamp(name = "updated_at").clientDefault { nowInstant() }

    override val primaryKey: PrimaryKey = PrimaryKey(messageId)
}
