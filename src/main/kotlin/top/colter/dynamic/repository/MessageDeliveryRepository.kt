package top.colter.dynamic.repository

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageDelivery
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.table.MessageDeliveryTable
import top.colter.dynamic.table.MessageOutboxTable
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

public object MessageDeliveryRepository {
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
                    it.writeTarget(target)
                    it[status] = DeliveryStatus.PENDING
                    it[attempts] = 0
                    it[sinkMessageId] = null
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

    public fun claimDue(
        nowEpochSeconds: Long,
        limit: Int,
        lockTtlMs: Long,
    ): List<MessageDeliveryRequest> {
        if (limit <= 0) return emptyList()
        val now = Instant.fromEpochSeconds(nowEpochSeconds)
        val lockedUntil = Instant.fromEpochMilliseconds(
            nowEpochSeconds * 1000 + lockTtlMs.coerceAtLeast(1),
        )

        val claimedIds = transaction {
            val candidates = MessageDeliveryTable
                .selectAll()
                .where { MessageDeliveryTable.status eq DeliveryStatus.PENDING }
                .map { it.toMessageDelivery() }
                .filter { delivery ->
                    val next = delivery.nextAttemptAtEpochSeconds
                    next == null || next <= nowEpochSeconds
                }
                .sortedWith(compareBy<MessageDelivery> { it.nextAttemptAtEpochSeconds ?: 0 }.thenBy { it.id })
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
        val expiredIds = transaction {
            MessageDeliveryTable
                .selectAll()
                .where { MessageDeliveryTable.status eq DeliveryStatus.SENDING }
                .map { it.toMessageDelivery() }
                .filter { delivery ->
                    val lockedUntil = delivery.lockedUntilEpochSeconds
                    lockedUntil != null && lockedUntil <= nowEpochSeconds
                }
                .map { it.id }
        }
        if (expiredIds.isEmpty()) return 0
        val now = nowInstant()
        return transaction {
            expiredIds.sumOf { deliveryId ->
                MessageDeliveryTable.update({ MessageDeliveryTable.id eq deliveryId }) {
                    it[status] = DeliveryStatus.PENDING
                    it[lockedUntil] = null
                    it[updatedAt] = now
                }
            }
        }
    }

    public fun markSent(deliveryId: Int, sinkMessageId: String? = null): Boolean {
        return transaction {
            MessageDeliveryTable.update({ MessageDeliveryTable.id eq deliveryId }) {
                it[status] = DeliveryStatus.SENT
                it[MessageDeliveryTable.sinkMessageId] = sinkMessageId
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
                it[lastError] = error?.take(500)
                it[nextAttemptAt] = null
                it[lockedUntil] = null
                it[updatedAt] = nowInstant()
            } > 0
        }
    }

    public fun markSent(messageId: String, target: TargetAddress, sinkMessageId: String? = null): Boolean {
        return updateLegacyTerminalStatus(messageId, target, DeliveryStatus.SENT, null, sinkMessageId)
    }

    public fun markFailed(messageId: String, target: TargetAddress, error: String?): Boolean {
        return updateLegacyTerminalStatus(messageId, target, DeliveryStatus.FAILED, error?.take(500), null)
    }

    public fun countByStatus(status: DeliveryStatus): Long {
        return transaction {
            MessageDeliveryTable.selectAll().where { MessageDeliveryTable.status eq status }.count()
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

    public fun findRecent(status: DeliveryStatus? = null, limit: Int = 50): List<MessageDelivery> {
        val safeLimit = limit.coerceIn(1, 200)
        return transaction {
            MessageDeliveryTable
                .selectAll()
                .let { query ->
                    if (status == null) query else query.where { MessageDeliveryTable.status eq status }
                }
                .map { it.toMessageDelivery() }
                .sortedWith(compareByDescending<MessageDelivery> { it.updatedAtEpochSeconds }.thenByDescending { it.id })
                .take(safeLimit)
        }
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

    public fun findByMessageId(messageId: String): List<MessageDelivery> {
        return transaction { findByMessageIdInCurrentTransaction(messageId) }
    }

    private fun updateLegacyTerminalStatus(
        messageId: String,
        target: TargetAddress,
        status: DeliveryStatus,
        error: String?,
        sinkMessageId: String?,
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
                it[lastError] = error
                it[nextAttemptAt] = null
                it[lockedUntil] = null
                it[updatedAt] = nowInstant()
            } > 0
        }
    }

    private fun findRequestsByDeliveryIds(deliveryIds: List<Int>): List<MessageDeliveryRequest> {
        if (deliveryIds.isEmpty()) return emptyList()
        val requestedIds = deliveryIds.toSet()
        return transaction {
            val deliveries = MessageDeliveryTable
                .selectAll()
                .map { it.toMessageDelivery() }
                .filter { it.id in requestedIds }
                .sortedBy { deliveryIds.indexOf(it.id) }
            val messagesById = MessageOutboxTable
                .selectAll()
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

private fun UpdateBuilder<*>.writeTarget(target: TargetAddress) {
    this[MessageDeliveryTable.platformId] = target.platformId.value
    this[MessageDeliveryTable.targetKind] = target.kind
    this[MessageDeliveryTable.targetId] = target.externalId
    this[MessageDeliveryTable.targetKey] = target.stableValue()
    this[MessageDeliveryTable.scopeId] = target.scopeId
    this[MessageDeliveryTable.threadId] = target.threadId
    this[MessageDeliveryTable.accountId] = target.accountId
}

public fun ResultRow.toMessageDelivery(): MessageDelivery = MessageDelivery(
    id = this[MessageDeliveryTable.id].value,
    messageId = this[MessageDeliveryTable.messageId],
    sourceUpdateKey = this[MessageDeliveryTable.sourceUpdateKey],
    renderVariant = this[MessageDeliveryTable.renderVariant],
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
    lastError = this[MessageDeliveryTable.lastError],
    nextAttemptAtEpochSeconds = this[MessageDeliveryTable.nextAttemptAt]?.epochSeconds,
    lockedUntilEpochSeconds = this[MessageDeliveryTable.lockedUntil]?.epochSeconds,
    createdAtEpochSeconds = this[MessageDeliveryTable.createdAt].epochSeconds,
    updatedAtEpochSeconds = this[MessageDeliveryTable.updatedAt].epochSeconds,
)
