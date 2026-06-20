package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.MessageImportance
import top.colter.dynamic.core.data.MessageRecordPolicyType
import top.colter.dynamic.core.data.MessageVisibility
import top.colter.dynamic.core.data.OutboundMessageKind
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.tools.nowInstant

public object MessageDeliveryTable : IntIdTable("message_delivery") {
    public val messageId: Column<String> = varchar(name = "message_id", length = 255)
    public val sourceUpdateKey: Column<UpdateKey?> = registerColumn("source_update_key_json", updateKeyColumn()).nullable()
    public val renderVariant: Column<String?> = varchar(name = "render_variant", length = 80).nullable()
    public val messageKind: Column<OutboundMessageKind> = enumerationByName<OutboundMessageKind>("message_kind", 40)
        .default(OutboundMessageKind.NORMAL)
    public val messageImportance: Column<MessageImportance> = enumerationByName<MessageImportance>("message_importance", 20)
        .default(MessageImportance.NORMAL)
    public val messageVisibility: Column<MessageVisibility> = enumerationByName<MessageVisibility>("message_visibility", 20)
        .default(MessageVisibility.DEFAULT)
    public val messageRecordPolicy: Column<MessageRecordPolicyType> =
        enumerationByName<MessageRecordPolicyType>("message_record_policy", 20).default(MessageRecordPolicyType.DURABLE)
    public val transientExpiresAtEpochSeconds: Column<Long?> = long(name = "transient_expires_at_epoch_seconds").nullable()
    public val platformId: Column<String> = varchar(name = "platform_id", length = 50)
    public val targetKind: Column<TargetKind> = enumerationByName<TargetKind>("target_kind", 30)
    public val targetId: Column<String> = varchar(name = "target_id", length = 120)
    public val targetName: Column<String?> = varchar(name = "target_name", length = 255).nullable()
    public val targetKey: Column<String> = text(name = "target_key")
    public val scopeId: Column<String?> = varchar(name = "scope_id", length = 120).nullable()
    public val threadId: Column<String?> = varchar(name = "thread_id", length = 120).nullable()
    public val accountId: Column<String?> = varchar(name = "account_id", length = 120).nullable()
    public val status: Column<DeliveryStatus> = enumerationByName<DeliveryStatus>("status", 20).default(DeliveryStatus.PENDING)
    public val attempts: Column<Int> = integer(name = "attempts").default(0)
    public val sinkMessageId: Column<String?> = varchar(name = "sink_message_id", length = 255).nullable()
    public val sinkRouteId: Column<String?> = varchar(name = "sink_route_id", length = 160).nullable()
    public val sinkAccountId: Column<String?> = varchar(name = "sink_account_id", length = 120).nullable()
    public val lastError: Column<String?> = varchar(name = "last_error", length = 500).nullable()
    public val nextAttemptAt: Column<Instant?> = timestamp(name = "next_attempt_at").nullable()
    public val lockedUntil: Column<Instant?> = timestamp(name = "locked_until").nullable()
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }
    public val updatedAt: Column<Instant> = timestamp(name = "updated_at").clientDefault { nowInstant() }

    init {
        uniqueIndex(messageId, targetKey)
        index(isUnique = false, status)
        index(isUnique = false, nextAttemptAt)
        index(isUnique = false, updatedAt)
        index(isUnique = false, platformId, targetKind)
        index(isUnique = false, messageVisibility, messageRecordPolicy, updatedAt)
        index(isUnique = false, messageRecordPolicy, transientExpiresAtEpochSeconds)
    }
}
