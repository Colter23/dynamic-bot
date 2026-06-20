package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.tools.nowInstant

public object MessageSinkReceiptTable : IntIdTable("message_sink_receipt") {
    public val transportId: Column<String> = varchar(name = "transport_id", length = 80).default("")
    public val platformId: Column<String> = varchar(name = "platform_id", length = 50)
    public val targetKind: Column<TargetKind> = enumerationByName<TargetKind>("target_kind", 30)
    public val targetId: Column<String> = varchar(name = "target_id", length = 120)
    public val targetKey: Column<String> = text(name = "target_key")
    public val scopeId: Column<String?> = varchar(name = "scope_id", length = 120).nullable()
    public val threadId: Column<String?> = varchar(name = "thread_id", length = 120).nullable()
    public val targetAccountId: Column<String?> = varchar(name = "target_account_id", length = 120).nullable()
    public val sinkMessageId: Column<String> = varchar(name = "sink_message_id", length = 255)
    public val sinkRouteId: Column<String?> = varchar(name = "sink_route_id", length = 160).nullable()
    public val sinkAccountId: Column<String?> = varchar(name = "sink_account_id", length = 120).nullable()
    public val sinkAccountKey: Column<String> = varchar(name = "sink_account_key", length = 120).default("")
    public val recallable: Column<Boolean> = bool(name = "recallable").default(true)
    public val deliveryId: Column<Int> = integer(name = "delivery_id")
    public val messageId: Column<String> = varchar(name = "message_id", length = 255)
    public val sourceUpdateKey: Column<UpdateKey?> = registerColumn("source_update_key_json", updateKeyColumn()).nullable()
    public val renderVariant: Column<String?> = varchar(name = "render_variant", length = 80).nullable()
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }

    init {
        uniqueIndex(transportId, platformId, targetKey, sinkAccountKey, sinkMessageId)
        index(isUnique = false, platformId, targetKind)
        index(isUnique = false, platformId, targetKey, sinkAccountKey, sinkMessageId)
        index(isUnique = false, deliveryId)
        index(isUnique = false, messageId)
        index(isUnique = false, createdAt)
    }
}
