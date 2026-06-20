package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.IncomingMessageRecordPolicyType
import top.colter.dynamic.core.data.IncomingProcessingResult
import top.colter.dynamic.core.data.IncomingProcessingStage
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.tools.nowInstant

public object IncomingMessageAuditTable : Table("incoming_message_audit") {
    public val traceId: Column<String> = varchar(name = "trace_id", length = 160)
    public val sourcePlugin: Column<String> = varchar(name = "source_plugin", length = 120)
    public val platformId: Column<String> = varchar(name = "platform_id", length = 50)
    public val botAccountId: Column<String?> = varchar(name = "bot_account_id", length = 120).nullable()
    public val targetKind: Column<TargetKind> = enumerationByName<TargetKind>("target_kind", 30)
    public val targetId: Column<String> = varchar(name = "target_id", length = 120)
    public val targetKey: Column<String> = text(name = "target_key")
    public val senderId: Column<String> = varchar(name = "sender_id", length = 160)
    public val platformMessageId: Column<String?> = varchar(name = "platform_message_id", length = 255).nullable()
    public val sourceEventId: Column<String?> = varchar(name = "source_event_id", length = 255).nullable()
    public val dedupeKey: Column<String?> = varchar(name = "dedupe_key", length = 255).nullable()
    public val intent: Column<String> = varchar(name = "intent", length = 40)
    public val recordPolicy: Column<IncomingMessageRecordPolicyType> =
        enumerationByName<IncomingMessageRecordPolicyType>("record_policy", 20)
    public val retentionSeconds: Column<Long?> = long(name = "retention_seconds").nullable()
    public val expiresAtEpochSeconds: Column<Long?> = long(name = "expires_at_epoch_seconds").nullable()
    public val textPreview: Column<String> = text(name = "text_preview")
    public val segmentSummary: Column<String> = varchar(name = "segment_summary", length = 255)
    public val replyToMessageId: Column<String?> = varchar(name = "reply_to_message_id", length = 255).nullable()
    public val receivedAt: Column<Instant> = timestamp(name = "received_at")
    public val messageTimestampEpochSeconds: Column<Long?> = long(name = "message_timestamp_epoch_seconds").nullable()
    public val rawFormat: Column<String?> = varchar(name = "raw_format", length = 120).nullable()
    public val rawPayloadSha256: Column<String?> = varchar(name = "raw_payload_sha256", length = 64).nullable()
    public val rawPayloadSize: Column<Int> = integer(name = "raw_payload_size").default(0)
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }

    override val primaryKey: PrimaryKey = PrimaryKey(traceId)

    init {
        index(isUnique = false, recordPolicy, receivedAt)
        index(isUnique = false, dedupeKey)
        index(isUnique = false, platformId, targetKind, targetId)
        index(isUnique = false, expiresAtEpochSeconds)
    }
}

public object IncomingProcessingAuditTable : IntIdTable("incoming_processing_audit") {
    public val traceId: Column<String> = varchar(name = "trace_id", length = 160)
    public val stage: Column<IncomingProcessingStage> = enumerationByName<IncomingProcessingStage>("stage", 40)
    public val handlerId: Column<String> = varchar(name = "handler_id", length = 160)
    public val result: Column<IncomingProcessingResult> = enumerationByName<IncomingProcessingResult>("result", 30)
    public val commandPath: Column<String?> = varchar(name = "command_path", length = 255).nullable()
    public val role: Column<String?> = varchar(name = "role", length = 30).nullable()
    public val errorMessage: Column<String?> = varchar(name = "error_message", length = 500).nullable()
    public val durationMs: Column<Long?> = long(name = "duration_ms").nullable()
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }

    init {
        index(isUnique = false, traceId, createdAt)
        index(isUnique = false, stage, result, createdAt)
    }
}
