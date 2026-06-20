package top.colter.dynamic.repository

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageDelivery
import top.colter.dynamic.core.data.MessageVisibility
import top.colter.dynamic.core.data.MessageRecordPolicy
import top.colter.dynamic.core.data.MessageRecordPolicyType
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.policyType
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.table.MessageDeliveryTable
import top.colter.dynamic.table.MessageOutboxTable
import top.colter.dynamic.table.MessageSinkReceiptTable
import top.colter.dynamic.core.tools.nowInstant

public data class MessageEnqueueResult(
    val message: Message,
    val createdMessage: Boolean,
    val newDeliveries: List<MessageDelivery>,
    val existingDeliveries: List<MessageDelivery>,
) {
    public val hasNewDeliveries: Boolean
        get() = newDeliveries.isNotEmpty()
}

public data class MessageDeliveryWithMessage(
    val delivery: MessageDelivery,
    val message: Message?,
)

public object MessageDeliveryRepository {
    public fun createMessageOnly(message: Message): Boolean {
        return transaction {
            val now = nowInstant()
            MessageOutboxTable.insertIgnore {
                it[messageId] = message.id
                it[MessageOutboxTable.message] = message
                it[createdAt] = now
                it[updatedAt] = now
            }.insertedCount > 0
        }
    }

    public fun enqueue(message: Message): MessageEnqueueResult {
        val insertedTargetKeys = linkedSetOf<String>()
        val createdMessage = transaction {
            val now = nowInstant()
            val messageInserted = MessageOutboxTable.insertIgnore {
                it[messageId] = message.id
                it[MessageOutboxTable.message] = message
                it[createdAt] = now
                it[updatedAt] = now
            }.insertedCount > 0

            message.targets.forEach { target ->
                val inserted = MessageDeliveryTable.insertIgnore {
                    it[messageId] = message.id
                    it[sourceUpdateKey] = message.sourceUpdateKey
                    it[renderVariant] = message.renderVariant
                    it.writeMessageMetadata(message)
                    it.writeTarget(target)
                    it[status] = DeliveryStatus.PENDING
                    it[attempts] = 0
                    it[sinkMessageId] = null
                    it[sinkRouteId] = null
                    it[sinkAccountId] = null
                    it[lastError] = null
                    it[nextAttemptAt] = now
                    it[lockedUntil] = null
                    it[createdAt] = now
                    it[updatedAt] = now
                }.insertedCount > 0
                if (inserted) insertedTargetKeys += target.stableValue()
            }

            messageInserted
        }

        val deliveries = findByMessageId(message.id)
        val (newDeliveries, existingDeliveries) = deliveries.partition { it.target.stableValue() in insertedTargetKeys }
        return MessageEnqueueResult(
            message = message,
            createdMessage = createdMessage,
            newDeliveries = newDeliveries,
            existingDeliveries = existingDeliveries,
        )
    }

    public fun createPending(message: Message): List<MessageDelivery> {
        return enqueue(message).newDeliveries
    }

    public fun createDeliveryRecord(
        message: Message,
        target: TargetAddress,
        status: DeliveryStatus,
        attempts: Int,
        sinkMessageId: String? = null,
        sinkRouteId: String? = null,
        sinkAccountId: String? = null,
        lastError: String? = null,
    ): MessageDelivery {
        createMessageOnly(message)
        val now = nowInstant()
        val deliveryId = transaction {
            MessageDeliveryTable.insert {
                it[messageId] = message.id
                it[sourceUpdateKey] = message.sourceUpdateKey
                it[renderVariant] = message.renderVariant
                it.writeMessageMetadata(message)
                it.writeTarget(target)
                it[MessageDeliveryTable.status] = status
                it[MessageDeliveryTable.attempts] = attempts.coerceAtLeast(0)
                it[MessageDeliveryTable.sinkMessageId] = sinkMessageId
                it[MessageDeliveryTable.sinkRouteId] = sinkRouteId.normalizedSinkRouteId()
                it[MessageDeliveryTable.sinkAccountId] = sinkAccountId.normalizedSinkAccountId()
                it[MessageDeliveryTable.lastError] = lastError?.take(500)
                it[nextAttemptAt] = null
                it[lockedUntil] = null
                it[createdAt] = now
                it[updatedAt] = now
            } get MessageDeliveryTable.id
        }.value
        return findById(deliveryId) ?: error("消息投递记录创建后无法读取：deliveryId=$deliveryId")
    }

    public fun claimDue(
        nowEpochSeconds: Long,
        limit: Int,
        lockTtlMs: Long,
    ): List<MessageDeliveryRequest> {
        if (limit <= 0) return emptyList()
        val now = nowInstant()
        val lockedUntil = Instant.fromEpochMilliseconds(
            nowEpochSeconds * 1000 + lockTtlMs.coerceAtLeast(1),
        )
        val candidateLimit = (limit * 100).coerceIn(limit, 5_000)

        val claimedIds = transaction {
            val candidates = MessageDeliveryTable
                .selectAll()
                .where { MessageDeliveryTable.status eq DeliveryStatus.PENDING }
                .orderBy(MessageDeliveryTable.nextAttemptAt to SortOrder.ASC, MessageDeliveryTable.id to SortOrder.ASC)
                .limit(candidateLimit)
                .map { it.toMessageDelivery() }
                .filter { delivery ->
                    val next = delivery.nextAttemptAtEpochSeconds
                    next == null || next <= nowEpochSeconds
                }
                .take(limit)

            candidates.mapNotNull { delivery ->
                val updated = MessageDeliveryTable.update({
                    (MessageDeliveryTable.id eq delivery.id) and
                        (MessageDeliveryTable.status eq DeliveryStatus.PENDING)
                }) {
                    it[status] = DeliveryStatus.SENDING
                    it[attempts] = delivery.attempts + 1
                    it[MessageDeliveryTable.lockedUntil] = lockedUntil
                    it[nextAttemptAt] = null
                    it[updatedAt] = now
                } > 0
                delivery.id.takeIf { updated }
            }
        }

        return findRequestsByDeliveryIds(claimedIds)
    }

    public fun markSendingExpired(nowEpochSeconds: Long): Int {
        val now = Instant.fromEpochMilliseconds(nowEpochSeconds * 1_000 + 999)
        val expiredIds = transaction {
            MessageDeliveryTable
                .selectAll()
                .where {
                    (MessageDeliveryTable.status eq DeliveryStatus.SENDING) and
                        (MessageDeliveryTable.lockedUntil lessEq now)
                }
                .map { it[MessageDeliveryTable.id].value }
        }
        if (expiredIds.isEmpty()) return 0
        val updatedInstant = nowInstant()
        return transaction {
            expiredIds.sumOf { deliveryId ->
                MessageDeliveryTable.update({ MessageDeliveryTable.id eq deliveryId }) {
                    it[status] = DeliveryStatus.PENDING
                    it[lockedUntil] = null
                    it[updatedAt] = updatedInstant
                }
            }
        }
    }

    public fun markSent(
        deliveryId: Int,
        sinkMessageId: String? = null,
        sinkRouteId: String? = null,
        sinkAccountId: String? = null,
    ): Boolean {
        return transaction {
            MessageDeliveryTable.update({ MessageDeliveryTable.id eq deliveryId }) {
                it[status] = DeliveryStatus.SENT
                it[MessageDeliveryTable.sinkMessageId] = sinkMessageId
                it[MessageDeliveryTable.sinkRouteId] = sinkRouteId.normalizedSinkRouteId()
                it[MessageDeliveryTable.sinkAccountId] = sinkAccountId.normalizedSinkAccountId()
                it[lastError] = null
                it[nextAttemptAt] = null
                it[lockedUntil] = null
                it[updatedAt] = nowInstant()
            } > 0
        }
    }

    public fun markRetry(deliveryId: Int, error: String?, nextAttemptAtEpochSeconds: Long): Boolean {
        return transaction {
            MessageDeliveryTable.update({ MessageDeliveryTable.id eq deliveryId }) {
                it[status] = DeliveryStatus.PENDING
                it[sinkMessageId] = null
                it[sinkRouteId] = null
                it[sinkAccountId] = null
                it[lastError] = error?.take(500)
                it[MessageDeliveryTable.nextAttemptAt] = Instant.fromEpochSeconds(nextAttemptAtEpochSeconds)
                it[lockedUntil] = null
                it[updatedAt] = nowInstant()
            } > 0
        }
    }

    public fun markFailed(deliveryId: Int, error: String?): Boolean {
        return transaction {
            MessageDeliveryTable.update({ MessageDeliveryTable.id eq deliveryId }) {
                it[status] = DeliveryStatus.FAILED
                it[sinkMessageId] = null
                it[sinkRouteId] = null
                it[sinkAccountId] = null
                it[lastError] = error?.take(500)
                it[nextAttemptAt] = null
                it[lockedUntil] = null
                it[updatedAt] = nowInstant()
            } > 0
        }
    }

    public fun markSent(
        messageId: String,
        target: TargetAddress,
        sinkMessageId: String? = null,
        sinkRouteId: String? = null,
        sinkAccountId: String? = null,
    ): Boolean {
        return updateLegacyTerminalStatus(
            messageId,
            target,
            DeliveryStatus.SENT,
            null,
            sinkMessageId,
            sinkRouteId,
            sinkAccountId,
        )
    }

    public fun markFailed(messageId: String, target: TargetAddress, error: String?): Boolean {
        return updateLegacyTerminalStatus(messageId, target, DeliveryStatus.FAILED, error?.take(500), null, null, null)
    }

    public fun countByStatus(status: DeliveryStatus): Long {
        return transaction {
            MessageDeliveryTable.selectAll().where { MessageDeliveryTable.status eq status }.count()
        }
    }

    public fun markPartiallySent(
        deliveryId: Int,
        error: String?,
        sinkMessageId: String? = null,
        sinkRouteId: String? = null,
        sinkAccountId: String? = null,
    ): Boolean {
        return transaction {
            MessageDeliveryTable.update({ MessageDeliveryTable.id eq deliveryId }) {
                it[status] = DeliveryStatus.PARTIALLY_SENT
                it[MessageDeliveryTable.sinkMessageId] = sinkMessageId
                it[MessageDeliveryTable.sinkRouteId] = sinkRouteId.normalizedSinkRouteId()
                it[MessageDeliveryTable.sinkAccountId] = sinkAccountId.normalizedSinkAccountId()
                it[lastError] = error?.take(500)
                it[nextAttemptAt] = null
                it[lockedUntil] = null
                it[updatedAt] = nowInstant()
            } > 0
        }
    }

    public fun countAll(): Long {
        return transaction {
            MessageDeliveryTable.selectAll().count()
        }
    }

    public fun countsByStatus(): Map<DeliveryStatus, Long> {
        return DeliveryStatus.entries.associateWith(::countByStatus)
    }

    public fun countsByStatus(includeInternalRecords: Boolean): Map<DeliveryStatus, Long> {
        if (includeInternalRecords) return countsByStatus()
        return DeliveryStatus.entries.associateWith { status ->
            transaction {
                MessageDeliveryTable.selectAll()
                    .where { (MessageDeliveryTable.status eq status) and defaultVisibleDeliveryFilter() }
                    .count()
            }
        }
    }

    public fun findRecent(
        status: DeliveryStatus? = null,
        platformId: String? = null,
        targetKind: TargetKind? = null,
        query: String? = null,
        limit: Int = 50,
        includeInternalRecords: Boolean = true,
    ): List<MessageDelivery> = findRecentWithMessages(
        status = status,
        platformId = platformId,
        targetKind = targetKind,
        query = query,
        limit = limit,
        includeInternalRecords = includeInternalRecords,
    ).map { it.delivery }

    public fun findRecentWithMessages(
        status: DeliveryStatus? = null,
        platformId: String? = null,
        targetKind: TargetKind? = null,
        query: String? = null,
        limit: Int = 50,
        includeInternalRecords: Boolean = true,
    ): List<MessageDeliveryWithMessage> {
        val safeLimit = if (limit == Int.MAX_VALUE) Int.MAX_VALUE else limit.coerceIn(1, 200)
        val normalizedPlatformId = platformId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedQuery = query?.trim()?.takeIf { it.isNotBlank() }
        val needsPostFilter = normalizedQuery != null
        val pageLimit = when {
            limit == Int.MAX_VALUE -> 5_000
            needsPostFilter -> (safeLimit * 25).coerceIn(safeLimit, 5_000)
            else -> safeLimit
        }
        val maxScannedRows = if (limit == Int.MAX_VALUE) Int.MAX_VALUE else pageLimit * 20
        return transaction {
            val filter = deliveryFilter(status, normalizedPlatformId, targetKind, includeInternalRecords)
            val rows = mutableListOf<MessageDeliveryWithMessage>()
            var offset = 0L
            var scannedRows = 0
            while (rows.size < safeLimit && scannedRows < maxScannedRows) {
                val deliveries = (filter?.let { MessageDeliveryTable.selectAll().where { it } } ?: MessageDeliveryTable.selectAll())
                    .orderBy(MessageDeliveryTable.updatedAt to SortOrder.DESC, MessageDeliveryTable.id to SortOrder.DESC)
                    .limit(pageLimit)
                    .offset(offset)
                    .map { it.toMessageDelivery() }
                if (deliveries.isEmpty()) break

                scannedRows += deliveries.size
                offset += deliveries.size
                val messagesById = MessageOutboxTable
                    .selectAll()
                    .where { MessageOutboxTable.messageId inList deliveries.map { it.messageId }.distinct() }
                    .associate { it[MessageOutboxTable.messageId] to it[MessageOutboxTable.message] }
                rows += deliveries
                    .filter { delivery ->
                        normalizedQuery == null || delivery.matchesDeliveryQuery(normalizedQuery)
                    }
                    .map { delivery -> MessageDeliveryWithMessage(delivery, messagesById[delivery.messageId]) }

                if (deliveries.size < pageLimit) break
            }
            rows.take(safeLimit)
        }
    }

    public fun cleanupHistory(
        cutoffEpochSeconds: Long,
        batchSize: Int = 1_000,
        maxBatches: Int = 20,
    ): MessageHistoryCleanupResult {
        val safeBatchSize = batchSize.coerceIn(1, 10_000)
        val safeMaxBatches = maxBatches.coerceIn(1, 1_000)
        val cutoff = Instant.fromEpochSeconds(cutoffEpochSeconds)
        var deletedDeliveries = 0
        var deletedMessages = 0

        repeat(safeMaxBatches) {
            val batch = transaction {
                MessageDeliveryTable
                    .selectAll()
                    .where {
                        terminalStatusFilter() and
                            (MessageDeliveryTable.updatedAt less cutoff)
                    }
                    .orderBy(MessageDeliveryTable.updatedAt to SortOrder.ASC, MessageDeliveryTable.id to SortOrder.ASC)
                    .limit(safeBatchSize)
                    .map { it[MessageDeliveryTable.id].value to it[MessageDeliveryTable.messageId] }
            }
            if (batch.isEmpty()) return MessageHistoryCleanupResult(deletedDeliveries, deletedMessages)

            val messageIds = batch.map { it.second }.distinct()
            val deletionCandidates = transaction {
                MessageDeliveryTable
                    .selectAll()
                    .where {
                        (MessageDeliveryTable.messageId inList messageIds) and
                            terminalStatusFilter() and
                            (MessageDeliveryTable.updatedAt less cutoff)
                    }
                    .map { it[MessageDeliveryTable.id].value to it[MessageDeliveryTable.messageId] }
            }
            val deliveryIds = deletionCandidates.map { it.first }.distinct()
            if (deliveryIds.isEmpty()) return@repeat

            val removedDeliveries = transaction {
                MessageSinkReceiptTable.deleteWhere { MessageSinkReceiptTable.deliveryId inList deliveryIds }
                MessageDeliveryTable.deleteWhere { MessageDeliveryTable.id inList deliveryIds }
            }
            deletedDeliveries += removedDeliveries

            val affectedMessageIds = deletionCandidates.map { it.second }.distinct()
            val orphanMessageIds = transaction {
                affectedMessageIds.filter { messageId ->
                    MessageDeliveryTable
                        .selectAll()
                        .where { MessageDeliveryTable.messageId eq messageId }
                        .count() == 0L
                }
            }
            if (orphanMessageIds.isNotEmpty()) {
                deletedMessages += transaction {
                    MessageOutboxTable.deleteWhere { MessageOutboxTable.messageId inList orphanMessageIds }
                }
            }

            if (removedDeliveries < safeBatchSize) {
                return MessageHistoryCleanupResult(deletedDeliveries, deletedMessages)
            }
        }

        return MessageHistoryCleanupResult(deletedDeliveries, deletedMessages)
    }

    public fun cleanupTransientMessages(
        nowEpochSeconds: Long,
        batchSize: Int = 1_000,
        maxBatches: Int = 20,
    ): MessageHistoryCleanupResult {
        val safeBatchSize = batchSize.coerceIn(1, 10_000)
        val safeMaxBatches = maxBatches.coerceIn(1, 1_000)
        var deletedDeliveries = 0
        var deletedMessages = 0

        repeat(safeMaxBatches) {
            val deliveryIds = transaction {
                MessageDeliveryTable
                    .selectAll()
                    .where {
                        (MessageDeliveryTable.messageRecordPolicy eq MessageRecordPolicyType.TRANSIENT) and
                            (MessageDeliveryTable.transientExpiresAtEpochSeconds lessEq nowEpochSeconds)
                    }
                    .orderBy(
                        MessageDeliveryTable.transientExpiresAtEpochSeconds to SortOrder.ASC,
                        MessageDeliveryTable.id to SortOrder.ASC,
                    )
                    .limit(safeBatchSize)
                    .map { it[MessageDeliveryTable.id].value }
            }
            if (deliveryIds.isEmpty()) return MessageHistoryCleanupResult(deletedDeliveries, deletedMessages)

            val messageIds = transaction {
                MessageDeliveryTable
                    .selectAll()
                    .where { MessageDeliveryTable.id inList deliveryIds }
                    .map { it[MessageDeliveryTable.messageId] }
            }

            val removedDeliveries = transaction {
                MessageSinkReceiptTable.deleteWhere { MessageSinkReceiptTable.deliveryId inList deliveryIds }
                MessageDeliveryTable.deleteWhere { MessageDeliveryTable.id inList deliveryIds }
            }
            deletedDeliveries += removedDeliveries

            val orphanMessageIds = transaction {
                messageIds.distinct().filter { messageId ->
                    MessageDeliveryTable
                        .selectAll()
                        .where { MessageDeliveryTable.messageId eq messageId }
                        .count() == 0L
                }
            }
            if (orphanMessageIds.isNotEmpty()) {
                deletedMessages += transaction {
                    MessageOutboxTable.deleteWhere { MessageOutboxTable.messageId inList orphanMessageIds }
                }
            }

            if (removedDeliveries < safeBatchSize) return MessageHistoryCleanupResult(deletedDeliveries, deletedMessages)
        }
        return MessageHistoryCleanupResult(deletedDeliveries, deletedMessages)
    }

    public fun findMessage(messageId: String): Message? {
        return transaction {
            MessageOutboxTable
                .selectAll()
                .where { MessageOutboxTable.messageId eq messageId }
                .firstOrNull()
                ?.get(MessageOutboxTable.message)
        }
    }

    public fun findById(id: Int): MessageDelivery? {
        return transaction {
            MessageDeliveryTable
                .selectAll()
                .where { MessageDeliveryTable.id eq id }
                .firstOrNull()
                ?.toMessageDelivery()
        }
    }

    public fun findByMessageId(messageId: String): List<MessageDelivery> {
        return transaction { findByMessageIdInCurrentTransaction(messageId) }
    }

    private fun updateLegacyTerminalStatus(
        messageId: String,
        target: TargetAddress,
        status: DeliveryStatus,
        error: String?,
        sinkMessageId: String?,
        sinkRouteId: String?,
        sinkAccountId: String?,
    ): Boolean {
        return transaction {
            val filter = targetFilter(messageId, target)
            val currentAttempts = MessageDeliveryTable
                .selectAll()
                .where(filter)
                .firstOrNull()
                ?.get(MessageDeliveryTable.attempts)
                ?: return@transaction false

            MessageDeliveryTable.update({ filter }) {
                it[MessageDeliveryTable.status] = status
                it[attempts] = currentAttempts + 1
                it[MessageDeliveryTable.sinkMessageId] = sinkMessageId
                it[MessageDeliveryTable.sinkRouteId] = sinkRouteId.normalizedSinkRouteId()
                it[MessageDeliveryTable.sinkAccountId] = sinkAccountId.normalizedSinkAccountId()
                it[lastError] = error
                it[nextAttemptAt] = null
                it[lockedUntil] = null
                it[updatedAt] = nowInstant()
            } > 0
        }
    }

    private fun findRequestsByDeliveryIds(deliveryIds: List<Int>): List<MessageDeliveryRequest> {
        if (deliveryIds.isEmpty()) return emptyList()
        return transaction {
            val deliveries = deliveryIds.mapNotNull { deliveryId ->
                MessageDeliveryTable
                    .selectAll()
                    .where { MessageDeliveryTable.id eq deliveryId }
                    .firstOrNull()
                    ?.toMessageDelivery()
            }
            if (deliveries.isEmpty()) return@transaction emptyList()
            val messagesById = MessageOutboxTable
                .selectAll()
                .where { MessageOutboxTable.messageId inList deliveries.map { it.messageId }.distinct() }
                .associate { it[MessageOutboxTable.messageId] to it[MessageOutboxTable.message] }
            deliveries.mapNotNull { delivery ->
                messagesById[delivery.messageId]?.let { message ->
                    MessageDeliveryRequest(delivery = delivery, message = message.copy(targets = listOf(delivery.target)))
                }
            }
        }
    }

    private fun targetFilter(messageId: String, target: TargetAddress): org.jetbrains.exposed.v1.core.Op<Boolean> {
        return (MessageDeliveryTable.messageId eq messageId) and
            (MessageDeliveryTable.targetKey eq target.stableValue())
    }

    private fun findByMessageIdInCurrentTransaction(messageId: String): List<MessageDelivery> {
        return MessageDeliveryTable
            .selectAll()
            .where { MessageDeliveryTable.messageId eq messageId }
            .map { it.toMessageDelivery() }
    }
}

public data class MessageHistoryCleanupResult(
    val deletedDeliveries: Int,
    val deletedMessages: Int,
)

private fun deliveryFilter(
    status: DeliveryStatus?,
    platformId: String?,
    targetKind: TargetKind?,
    includeInternalRecords: Boolean = true,
): Op<Boolean>? {
    val filters = buildList {
        status?.let { add(MessageDeliveryTable.status eq it) }
        platformId?.let { add(MessageDeliveryTable.platformId eq it) }
        targetKind?.let { add(MessageDeliveryTable.targetKind eq it) }
        if (!includeInternalRecords) add(defaultVisibleDeliveryFilter())
    }
    return filters.reduceOrNull { acc, op -> acc and op }
}

private fun defaultVisibleDeliveryFilter(): Op<Boolean> {
    return (MessageDeliveryTable.messageVisibility eq MessageVisibility.DEFAULT) and
        (MessageDeliveryTable.messageRecordPolicy eq MessageRecordPolicyType.DURABLE)
}

private fun terminalStatusFilter(): Op<Boolean> {
    return (MessageDeliveryTable.status eq DeliveryStatus.SENT) or
        (MessageDeliveryTable.status eq DeliveryStatus.PARTIALLY_SENT) or
        (MessageDeliveryTable.status eq DeliveryStatus.FAILED)
}

private fun MessageDelivery.matchesDeliveryQuery(query: String): Boolean {
    return listOfNotNull(
        id.toString(),
        messageId,
        sourceUpdateKey?.stableValue(),
        renderVariant,
        target.platformId.value,
        target.kind.name,
        target.externalId,
        target.scopeId,
        target.threadId,
        target.accountId,
        target.stableValue(),
        status.name,
        sinkMessageId,
        sinkRouteId,
        sinkAccountId,
        lastError,
    ).any { it.contains(query, ignoreCase = true) }
}

private fun String?.normalizedSinkRouteId(): String? = this?.trim()?.takeIf { it.isNotBlank() }

private fun String?.normalizedSinkAccountId(): String? = this?.trim()?.takeIf { it.isNotBlank() }

private fun Message.transientExpiresAtEpochSeconds(): Long? {
    val policy = recordPolicy as? MessageRecordPolicy.Transient ?: return null
    return if (policy.retentionSeconds > Long.MAX_VALUE - time) {
        Long.MAX_VALUE
    } else {
        time + policy.retentionSeconds
    }
}

private fun UpdateBuilder<*>.writeTarget(target: TargetAddress) {
    this[MessageDeliveryTable.platformId] = target.platformId.value
    this[MessageDeliveryTable.targetKind] = target.kind
    this[MessageDeliveryTable.targetId] = target.externalId
    this[MessageDeliveryTable.targetKey] = target.stableValue()
    this[MessageDeliveryTable.scopeId] = target.scopeId
    this[MessageDeliveryTable.threadId] = target.threadId
    this[MessageDeliveryTable.accountId] = target.accountId
}

private fun UpdateBuilder<*>.writeMessageMetadata(message: Message) {
    this[MessageDeliveryTable.messageKind] = message.kind
    this[MessageDeliveryTable.messageImportance] = message.importance
    this[MessageDeliveryTable.messageVisibility] = message.visibility
    this[MessageDeliveryTable.messageRecordPolicy] = message.recordPolicy.policyType
    this[MessageDeliveryTable.transientExpiresAtEpochSeconds] = message.transientExpiresAtEpochSeconds()
}

public fun ResultRow.toMessageDelivery(): MessageDelivery = MessageDelivery(
    id = this[MessageDeliveryTable.id].value,
    messageId = this[MessageDeliveryTable.messageId],
    sourceUpdateKey = this[MessageDeliveryTable.sourceUpdateKey],
    renderVariant = this[MessageDeliveryTable.renderVariant],
    messageKind = this[MessageDeliveryTable.messageKind],
    messageImportance = this[MessageDeliveryTable.messageImportance],
    messageVisibility = this[MessageDeliveryTable.messageVisibility],
    messageRecordPolicyType = this[MessageDeliveryTable.messageRecordPolicy],
    transientExpiresAtEpochSeconds = this[MessageDeliveryTable.transientExpiresAtEpochSeconds],
    target = TargetAddress(
        platformId = PlatformId.of(this[MessageDeliveryTable.platformId]),
        kind = this[MessageDeliveryTable.targetKind],
        externalId = this[MessageDeliveryTable.targetId],
        scopeId = this[MessageDeliveryTable.scopeId],
        threadId = this[MessageDeliveryTable.threadId],
        accountId = this[MessageDeliveryTable.accountId],
    ),
    status = this[MessageDeliveryTable.status],
    attempts = this[MessageDeliveryTable.attempts],
    sinkMessageId = this[MessageDeliveryTable.sinkMessageId],
    sinkRouteId = this[MessageDeliveryTable.sinkRouteId],
    sinkAccountId = this[MessageDeliveryTable.sinkAccountId],
    lastError = this[MessageDeliveryTable.lastError],
    nextAttemptAtEpochSeconds = this[MessageDeliveryTable.nextAttemptAt]?.epochSeconds,
    lockedUntilEpochSeconds = this[MessageDeliveryTable.lockedUntil]?.epochSeconds,
    createdAtEpochSeconds = this[MessageDeliveryTable.createdAt].epochSeconds,
    updatedAtEpochSeconds = this[MessageDeliveryTable.updatedAt].epochSeconds,
)
