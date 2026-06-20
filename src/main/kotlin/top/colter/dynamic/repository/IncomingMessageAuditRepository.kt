package top.colter.dynamic.repository

import java.security.MessageDigest
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageRecordPolicy
import top.colter.dynamic.core.data.IncomingMessageRecordPolicyType
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.IncomingProcessingResult
import top.colter.dynamic.core.data.IncomingProcessingStage
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.policyType
import top.colter.dynamic.core.plugin.IncomingMessageIntent
import top.colter.dynamic.table.IncomingMessageAuditTable
import top.colter.dynamic.table.IncomingProcessingAuditTable
import top.colter.dynamic.core.tools.nowInstant

public data class IncomingMessageAuditRecord(
    val traceId: String,
    val sourcePlugin: String,
    val platformId: String,
    val botAccountId: String?,
    val targetKind: TargetKind,
    val targetId: String,
    val targetKey: String,
    val senderId: String,
    val platformMessageId: String?,
    val sourceEventId: String?,
    val dedupeKey: String?,
    val intent: String,
    val recordPolicyType: IncomingMessageRecordPolicyType,
    val retentionSeconds: Long?,
    val expiresAtEpochSeconds: Long?,
    val textPreview: String,
    val segmentSummary: String,
    val replyToMessageId: String?,
    val receivedAtEpochSeconds: Long,
    val messageTimestampEpochSeconds: Long?,
    val rawFormat: String?,
    val rawPayloadSha256: String?,
    val rawPayloadSize: Int,
    val createdAtEpochSeconds: Long,
)

public data class IncomingProcessingAuditRecord(
    val id: Int,
    val traceId: String,
    val stage: IncomingProcessingStage,
    val handlerId: String,
    val result: IncomingProcessingResult,
    val commandPath: String?,
    val role: String?,
    val errorMessage: String?,
    val durationMs: Long?,
    val createdAtEpochSeconds: Long,
)

public data class IncomingMessageAuditWithProcessing(
    val message: IncomingMessageAuditRecord,
    val processing: List<IncomingProcessingAuditRecord>,
)

public data class IncomingAuditCleanupResult(
    val deletedMessages: Int,
    val deletedProcessingRecords: Int,
)

public data class IncomingAuditWriteRequest(
    val sourcePlugin: String,
    val message: IncomingMessage,
    val traceId: String,
    val replyToMessageId: String?,
    val intent: IncomingMessageIntent,
    val recordPolicy: IncomingMessageRecordPolicy,
    val receivedAtEpochSeconds: Long,
    val dedupeKey: String? = null,
    val sourceEventId: String? = null,
)

public data class IncomingProcessingWriteRequest(
    val traceId: String,
    val stage: IncomingProcessingStage,
    val handlerId: String,
    val result: IncomingProcessingResult,
    val commandPath: String? = null,
    val role: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long? = null,
)

public object IncomingMessageAuditRepository {
    public const val TEXT_PREVIEW_MAX_LENGTH: Int = 300

    public fun recordMessage(request: IncomingAuditWriteRequest): Boolean {
        if (request.recordPolicy == IncomingMessageRecordPolicy.None) return false
        val normalizedTraceId = request.traceId.trim().takeIf { it.isNotBlank() } ?: return false
        val policyType = request.recordPolicy.policyType
        val retentionSeconds = request.recordPolicy.retentionSeconds
        val expiresAt = if (policyType == IncomingMessageRecordPolicyType.TRACE && retentionSeconds != null) {
            safeAdd(request.receivedAtEpochSeconds, retentionSeconds)
        } else {
            null
        }
        val message = request.message
        val rawPayload = message.rawPayload
        val rawPayloadSize = rawPayload.toByteArray(Charsets.UTF_8).size
        val rawHash = rawPayload.takeIf { it.isNotBlank() }?.sha256Hex()
        val now = nowInstant()
        return transaction {
            IncomingMessageAuditTable.insertIgnore {
                it[traceId] = normalizedTraceId
                it[sourcePlugin] = request.sourcePlugin.trim().takeIf { value -> value.isNotBlank() } ?: "unknown"
                it[platformId] = message.platformId.value
                it[botAccountId] = message.botAccountId?.trim()?.takeIf(String::isNotBlank)
                it[targetKind] = message.target.kind
                it[targetId] = message.target.externalId
                it[targetKey] = message.target.stableValue()
                it[senderId] = message.senderId
                it[platformMessageId] = message.messageId.trim().takeIf(String::isNotBlank)
                it[sourceEventId] = request.sourceEventId?.trim()?.takeIf(String::isNotBlank)
                it[dedupeKey] = request.dedupeKey?.trim()?.takeIf(String::isNotBlank)
                it[intent] = request.intent.storageName()
                it[recordPolicy] = policyType
                it[IncomingMessageAuditTable.retentionSeconds] = retentionSeconds
                it[expiresAtEpochSeconds] = expiresAt
                it[textPreview] = message.previewText()
                it[segmentSummary] = message.segmentSummary()
                it[replyToMessageId] = request.replyToMessageId?.trim()?.takeIf(String::isNotBlank)
                it[receivedAt] = Instant.fromEpochSeconds(request.receivedAtEpochSeconds)
                it[messageTimestampEpochSeconds] = message.timestamp.takeIf { value -> value > 0 }
                it[rawFormat] = message.rawFormat.trim().takeIf(String::isNotBlank)
                it[rawPayloadSha256] = rawHash
                it[IncomingMessageAuditTable.rawPayloadSize] = rawPayloadSize
                it[createdAt] = now
            }.insertedCount > 0
        }
    }

    public fun recordProcessing(request: IncomingProcessingWriteRequest): Boolean {
        val normalizedTraceId = request.traceId.trim().takeIf { it.isNotBlank() } ?: return false
        val normalizedHandlerId = request.handlerId.trim().takeIf { it.isNotBlank() } ?: return false
        return transaction {
            IncomingProcessingAuditTable.insertIgnore {
                it[traceId] = normalizedTraceId
                it[stage] = request.stage
                it[handlerId] = normalizedHandlerId
                it[result] = request.result
                it[commandPath] = request.commandPath?.trim()?.take(255)?.takeIf(String::isNotBlank)
                it[role] = request.role?.trim()?.takeIf(String::isNotBlank)
                it[errorMessage] = request.errorMessage?.trim()?.take(500)?.takeIf(String::isNotBlank)
                it[durationMs] = request.durationMs?.coerceAtLeast(0)
                it[createdAt] = nowInstant()
            }.insertedCount > 0
        }
    }

    public fun findRecent(
        recordPolicy: IncomingMessageRecordPolicyType? = null,
        intent: String? = null,
        platformId: String? = null,
        targetKind: TargetKind? = null,
        targetId: String? = null,
        senderId: String? = null,
        sourcePlugin: String? = null,
        traceId: String? = null,
        result: IncomingProcessingResult? = null,
        stage: IncomingProcessingStage? = null,
        commandPath: String? = null,
        query: String? = null,
        includeTrace: Boolean = false,
        limit: Int = 50,
    ): List<IncomingMessageAuditWithProcessing> {
        val safeLimit = limit.coerceIn(1, 200)
        val normalizedIntent = intent?.trim()?.takeIf(String::isNotBlank)?.uppercase()
        val normalizedPlatform = platformId?.trim()?.takeIf(String::isNotBlank)
        val normalizedTargetId = targetId?.trim()?.takeIf(String::isNotBlank)
        val normalizedSenderId = senderId?.trim()?.takeIf(String::isNotBlank)
        val normalizedSourcePlugin = sourcePlugin?.trim()?.takeIf(String::isNotBlank)
        val normalizedTraceId = traceId?.trim()?.takeIf(String::isNotBlank)
        val normalizedCommandPath = commandPath?.trim()?.takeIf(String::isNotBlank)
        val normalizedQuery = query?.trim()?.takeIf(String::isNotBlank)
        val needsProcessingFilter = result != null || stage != null || normalizedCommandPath != null
        val explicitSearch = normalizedTraceId != null || normalizedQuery != null || needsProcessingFilter
        val rows = transaction {
            val processingTraceIds = if (needsProcessingFilter) {
                processingTraceIdsForFilter(result, stage, normalizedCommandPath)
            } else {
                null
            }
            if (processingTraceIds != null && processingTraceIds.isEmpty()) return@transaction emptyList()
            val filter = incomingFilter(
                recordPolicy = recordPolicy,
                intent = normalizedIntent,
                platformId = normalizedPlatform,
                targetKind = targetKind,
                targetId = normalizedTargetId,
                senderId = normalizedSenderId,
                sourcePlugin = normalizedSourcePlugin,
                traceId = normalizedTraceId,
                includeTrace = includeTrace,
                skipDefaultVisibleFilter = explicitSearch,
                processingTraceIds = processingTraceIds,
            )
            val pageLimit = if (normalizedQuery != null) (safeLimit * 20).coerceIn(safeLimit, 5_000) else safeLimit
            val maxScannedRows = if (normalizedQuery != null) 5_000 else pageLimit
            val matched = mutableListOf<IncomingMessageAuditWithProcessing>()
            var scannedRows = 0
            var offset = 0L
            while (matched.size < safeLimit && scannedRows < maxScannedRows) {
                val messages = (filter?.let { IncomingMessageAuditTable.selectAll().where { it } }
                    ?: IncomingMessageAuditTable.selectAll())
                    .orderBy(IncomingMessageAuditTable.receivedAt to SortOrder.DESC)
                    .limit(pageLimit)
                    .offset(offset)
                    .map { it.toIncomingMessageAuditRecord() }
                if (messages.isEmpty()) break

                scannedRows += messages.size
                offset += messages.size
                val processingByTrace = processingByTrace(messages.map { it.traceId })
                matched += messages
                    .asSequence()
                    .map { message -> IncomingMessageAuditWithProcessing(message, processingByTrace[message.traceId].orEmpty()) }
                    .filter { item -> item.matchesProcessingFilter(result, stage, normalizedCommandPath) }
                    .filter { item -> normalizedQuery == null || item.matchesQuery(normalizedQuery) }
                    .toList()

                if (messages.size < pageLimit) break
            }
            matched.take(safeLimit)
        }
        return rows
    }

    public fun findByTraceId(traceId: String): IncomingMessageAuditWithProcessing? {
        val normalizedTraceId = traceId.trim().takeIf { it.isNotBlank() } ?: return null
        return transaction {
            val message = IncomingMessageAuditTable
                .selectAll()
                .where { IncomingMessageAuditTable.traceId eq normalizedTraceId }
                .firstOrNull()
                ?.toIncomingMessageAuditRecord()
                ?: return@transaction null
            IncomingMessageAuditWithProcessing(
                message = message,
                processing = processingByTrace(listOf(normalizedTraceId))[normalizedTraceId].orEmpty(),
            )
        }
    }

    public fun cleanupExpiredTrace(nowEpochSeconds: Long, batchSize: Int = 1_000): IncomingAuditCleanupResult {
        val safeBatchSize = batchSize.coerceIn(1, 10_000)
        val traceIds = transaction {
            IncomingMessageAuditTable
                .selectAll()
                .where {
                    (IncomingMessageAuditTable.recordPolicy eq IncomingMessageRecordPolicyType.TRACE) and
                        (IncomingMessageAuditTable.expiresAtEpochSeconds lessEq nowEpochSeconds)
                }
                .orderBy(IncomingMessageAuditTable.expiresAtEpochSeconds to SortOrder.ASC)
                .limit(safeBatchSize)
                .map { it[IncomingMessageAuditTable.traceId] }
        }
        if (traceIds.isEmpty()) return IncomingAuditCleanupResult(0, 0)
        return transaction {
            val deletedProcessing = IncomingProcessingAuditTable.deleteWhere {
                IncomingProcessingAuditTable.traceId inList traceIds
            }
            val deletedMessages = IncomingMessageAuditTable.deleteWhere {
                IncomingMessageAuditTable.traceId inList traceIds
            }
            IncomingAuditCleanupResult(deletedMessages, deletedProcessing)
        }
    }

    public fun countMessages(): Long {
        return transaction { IncomingMessageAuditTable.selectAll().count() }
    }

    public fun countProcessing(): Long {
        return transaction { IncomingProcessingAuditTable.selectAll().count() }
    }
}

private fun processingByTrace(traceIds: List<String>): Map<String, List<IncomingProcessingAuditRecord>> {
    if (traceIds.isEmpty()) return emptyMap()
    return IncomingProcessingAuditTable
        .selectAll()
        .where { IncomingProcessingAuditTable.traceId inList traceIds.distinct() }
        .orderBy(IncomingProcessingAuditTable.createdAt to SortOrder.ASC, IncomingProcessingAuditTable.id to SortOrder.ASC)
        .map { it.toIncomingProcessingAuditRecord() }
        .groupBy { it.traceId }
}

private fun incomingFilter(
    recordPolicy: IncomingMessageRecordPolicyType?,
    intent: String?,
    platformId: String?,
    targetKind: TargetKind?,
    targetId: String?,
    senderId: String?,
    sourcePlugin: String?,
    traceId: String?,
    includeTrace: Boolean,
    skipDefaultVisibleFilter: Boolean,
    processingTraceIds: List<String>?,
): Op<Boolean>? {
    val filters = buildList {
        recordPolicy?.let { add(IncomingMessageAuditTable.recordPolicy eq it) }
        intent?.let { add(IncomingMessageAuditTable.intent eq it) }
        platformId?.let { add(IncomingMessageAuditTable.platformId eq it) }
        targetKind?.let { add(IncomingMessageAuditTable.targetKind eq it) }
        targetId?.let { add(IncomingMessageAuditTable.targetId eq it) }
        senderId?.let { add(IncomingMessageAuditTable.senderId eq it) }
        sourcePlugin?.let { add(IncomingMessageAuditTable.sourcePlugin eq it) }
        traceId?.let { add(IncomingMessageAuditTable.traceId eq it) }
        processingTraceIds?.let { add(IncomingMessageAuditTable.traceId inList it) }
        if (!includeTrace && recordPolicy == null && !skipDefaultVisibleFilter) add(defaultVisibleIncomingFilter())
    }
    return filters.reduceOrNull { acc, op -> acc and op }
}

private fun defaultVisibleIncomingFilter(): Op<Boolean> {
    return (IncomingMessageAuditTable.recordPolicy eq IncomingMessageRecordPolicyType.AUDIT) or
        (IncomingMessageAuditTable.traceId inList errorTraceIds())
}

private fun errorTraceIds(): List<String> {
    return IncomingProcessingAuditTable
        .selectAll()
        .where { IncomingProcessingAuditTable.result eq IncomingProcessingResult.FAILED }
        .orderBy(IncomingProcessingAuditTable.createdAt to SortOrder.DESC, IncomingProcessingAuditTable.id to SortOrder.DESC)
        .limit(1_000)
        .map { it[IncomingProcessingAuditTable.traceId] }
        .distinct()
}

private fun processingTraceIdsForFilter(
    result: IncomingProcessingResult?,
    stage: IncomingProcessingStage?,
    commandPath: String?,
): List<String> {
    val filter = buildList {
        result?.let { add(IncomingProcessingAuditTable.result eq it) }
        stage?.let { add(IncomingProcessingAuditTable.stage eq it) }
    }.reduceOrNull { acc, op -> acc and op }
    return (filter?.let { IncomingProcessingAuditTable.selectAll().where { it } }
        ?: IncomingProcessingAuditTable.selectAll())
        .orderBy(IncomingProcessingAuditTable.createdAt to SortOrder.DESC, IncomingProcessingAuditTable.id to SortOrder.DESC)
        .limit(5_000)
        .map { it.toIncomingProcessingAuditRecord() }
        .asSequence()
        .filter { record -> commandPath == null || record.commandPath?.contains(commandPath, ignoreCase = true) == true }
        .map { it.traceId }
        .distinct()
        .toList()
}

private fun IncomingMessageAuditWithProcessing.matchesProcessingFilter(
    result: IncomingProcessingResult?,
    stage: IncomingProcessingStage?,
    commandPath: String?,
): Boolean {
    if (result == null && stage == null && commandPath == null) return true
    return processing.any { record ->
        (result == null || record.result == result) &&
            (stage == null || record.stage == stage) &&
            (commandPath == null || record.commandPath?.contains(commandPath, ignoreCase = true) == true)
    }
}

private fun IncomingMessageAuditWithProcessing.matchesQuery(query: String): Boolean {
    return listOfNotNull(
        message.traceId,
        message.sourcePlugin,
        message.platformId,
        message.targetId,
        message.targetKey,
        message.senderId,
        message.platformMessageId,
        message.sourceEventId,
        message.dedupeKey,
        message.intent,
        message.recordPolicyType.name,
        message.textPreview,
        message.segmentSummary,
        message.replyToMessageId,
        message.rawFormat,
        message.rawPayloadSha256,
    ).any { it.contains(query, ignoreCase = true) } || processing.any { record ->
        listOfNotNull(
            record.stage.name,
            record.handlerId,
            record.result.name,
            record.commandPath,
            record.role,
            record.errorMessage,
        ).any { it.contains(query, ignoreCase = true) }
    }
}

private fun IncomingMessage.previewText(): String {
    val text = text.trim().takeIf { it.isNotBlank() }
        ?: segments.asSequence().mapNotNull { it.previewText() }.joinToString(" ").trim().takeIf { it.isNotBlank() }
        ?: segmentSummary()
    return text.replace(Regex("\\s+"), " ").take(IncomingMessageAuditRepository.TEXT_PREVIEW_MAX_LENGTH)
}

private fun IncomingMessageSegment.previewText(): String? {
    return when (this) {
        is IncomingMessageSegment.Text -> text
        is IncomingMessageSegment.Image -> url ?: file.takeIf { it.isNotBlank() }?.let { "图片 $it" }
        is IncomingMessageSegment.Video -> url ?: file.takeIf { it.isNotBlank() }?.let { "视频 $it" }
        is IncomingMessageSegment.Audio -> url ?: file.takeIf { it.isNotBlank() }?.let { "音频 $it" }
        is IncomingMessageSegment.Mention -> if (all) "@全体" else "@${targetId}"
        is IncomingMessageSegment.Reply -> "回复 $messageId"
        is IncomingMessageSegment.Unknown -> segmentType
    }?.trim()?.takeIf { it.isNotBlank() }
}

private fun IncomingMessage.segmentSummary(): String {
    val counts = segments.groupingBy { segment ->
        when (segment) {
            is IncomingMessageSegment.Text -> "文本"
            is IncomingMessageSegment.Mention -> "提及"
            is IncomingMessageSegment.Image -> "图片"
            is IncomingMessageSegment.Video -> "视频"
            is IncomingMessageSegment.Audio -> "音频"
            is IncomingMessageSegment.Reply -> "回复"
            is IncomingMessageSegment.Unknown -> "未知"
        }
    }.eachCount()
    return counts.entries.joinToString(" · ") { (name, count) -> "$name $count" }
        .ifBlank { if (text.isNotBlank()) "文本 1" else "无内容" }
        .take(255)
}

private fun IncomingMessageIntent.storageName(): String {
    return when (this) {
        IncomingMessageIntent.Command -> "COMMAND"
        IncomingMessageIntent.LinkText -> "LINK_TEXT"
        IncomingMessageIntent.PlainText -> "PLAIN_TEXT"
        IncomingMessageIntent.NonText -> "NON_TEXT"
    }
}

private fun safeAdd(left: Long, right: Long): Long {
    return if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun ResultRow.toIncomingMessageAuditRecord(): IncomingMessageAuditRecord = IncomingMessageAuditRecord(
    traceId = this[IncomingMessageAuditTable.traceId],
    sourcePlugin = this[IncomingMessageAuditTable.sourcePlugin],
    platformId = this[IncomingMessageAuditTable.platformId],
    botAccountId = this[IncomingMessageAuditTable.botAccountId],
    targetKind = this[IncomingMessageAuditTable.targetKind],
    targetId = this[IncomingMessageAuditTable.targetId],
    targetKey = this[IncomingMessageAuditTable.targetKey],
    senderId = this[IncomingMessageAuditTable.senderId],
    platformMessageId = this[IncomingMessageAuditTable.platformMessageId],
    sourceEventId = this[IncomingMessageAuditTable.sourceEventId],
    dedupeKey = this[IncomingMessageAuditTable.dedupeKey],
    intent = this[IncomingMessageAuditTable.intent],
    recordPolicyType = this[IncomingMessageAuditTable.recordPolicy],
    retentionSeconds = this[IncomingMessageAuditTable.retentionSeconds],
    expiresAtEpochSeconds = this[IncomingMessageAuditTable.expiresAtEpochSeconds],
    textPreview = this[IncomingMessageAuditTable.textPreview],
    segmentSummary = this[IncomingMessageAuditTable.segmentSummary],
    replyToMessageId = this[IncomingMessageAuditTable.replyToMessageId],
    receivedAtEpochSeconds = this[IncomingMessageAuditTable.receivedAt].epochSeconds,
    messageTimestampEpochSeconds = this[IncomingMessageAuditTable.messageTimestampEpochSeconds],
    rawFormat = this[IncomingMessageAuditTable.rawFormat],
    rawPayloadSha256 = this[IncomingMessageAuditTable.rawPayloadSha256],
    rawPayloadSize = this[IncomingMessageAuditTable.rawPayloadSize],
    createdAtEpochSeconds = this[IncomingMessageAuditTable.createdAt].epochSeconds,
)

private fun ResultRow.toIncomingProcessingAuditRecord(): IncomingProcessingAuditRecord = IncomingProcessingAuditRecord(
    id = this[IncomingProcessingAuditTable.id].value,
    traceId = this[IncomingProcessingAuditTable.traceId],
    stage = this[IncomingProcessingAuditTable.stage],
    handlerId = this[IncomingProcessingAuditTable.handlerId],
    result = this[IncomingProcessingAuditTable.result],
    commandPath = this[IncomingProcessingAuditTable.commandPath],
    role = this[IncomingProcessingAuditTable.role],
    errorMessage = this[IncomingProcessingAuditTable.errorMessage],
    durationMs = this[IncomingProcessingAuditTable.durationMs],
    createdAtEpochSeconds = this[IncomingProcessingAuditTable.createdAt].epochSeconds,
)
