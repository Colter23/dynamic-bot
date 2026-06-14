package top.colter.dynamic.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.tools.nowInstant
import top.colter.dynamic.table.MessageSinkReceiptTable

public data class MessageSinkReceipt(
    val id: Int,
    val transportId: String,
    val platformId: PlatformId,
    val target: TargetAddress,
    val sinkMessageId: String,
    val sinkRouteId: String? = null,
    val sinkAccountId: String? = null,
    val deliveryId: Int,
    val messageId: String,
    val sourceUpdateKey: UpdateKey? = null,
    val renderVariant: String? = null,
    val createdAtEpochSeconds: Long,
)

public object MessageSinkReceiptRepository {
    public fun recordSent(
        delivery: top.colter.dynamic.core.data.MessageDelivery,
        message: Message,
        result: MessageSendResult.Sent,
    ): Int {
        val sinkMessageIds = result.actualSinkMessageIds()
        if (sinkMessageIds.isEmpty()) return 0

        val transportId = result.sinkTransportId.normalized().orEmpty()
        val sinkRouteId = result.sinkRouteId.normalized()
        val sinkAccountId = result.sinkAccountId.normalized()
        val sinkAccountKey = sinkAccountId.orEmpty()
        val target = delivery.target
        val createdAt = nowInstant()
        return transaction {
            sinkMessageIds.sumOf { sinkMessageId ->
                MessageSinkReceiptTable.insertIgnore {
                    it[MessageSinkReceiptTable.transportId] = transportId
                    it[MessageSinkReceiptTable.platformId] = target.platformId.value
                    it[MessageSinkReceiptTable.targetKind] = target.kind
                    it[MessageSinkReceiptTable.targetId] = target.externalId
                    it[MessageSinkReceiptTable.targetKey] = target.stableValue()
                    it[MessageSinkReceiptTable.scopeId] = target.scopeId
                    it[MessageSinkReceiptTable.threadId] = target.threadId
                    it[MessageSinkReceiptTable.targetAccountId] = target.accountId
                    it[MessageSinkReceiptTable.sinkMessageId] = sinkMessageId
                    it[MessageSinkReceiptTable.sinkRouteId] = sinkRouteId
                    it[MessageSinkReceiptTable.sinkAccountId] = sinkAccountId
                    it[MessageSinkReceiptTable.sinkAccountKey] = sinkAccountKey
                    it[MessageSinkReceiptTable.deliveryId] = delivery.id
                    it[MessageSinkReceiptTable.messageId] = message.id
                    it[MessageSinkReceiptTable.sourceUpdateKey] = message.sourceUpdateKey
                    it[MessageSinkReceiptTable.renderVariant] = message.renderVariant
                    it[MessageSinkReceiptTable.createdAt] = createdAt
                }.insertedCount
            }
        }
    }

    public fun findByIncomingReply(message: IncomingMessage): MessageSinkReceipt? {
        val replyMessageId = message.replyMessageId() ?: return null
        return findByPlatformMessage(
            platformId = message.platformId,
            target = message.target,
            sinkMessageId = replyMessageId,
            sinkAccountId = message.botAccountId ?: message.target.accountId,
        )
    }

    public fun findByPlatformMessage(
        platformId: PlatformId,
        target: TargetAddress,
        sinkMessageId: String,
        sinkAccountId: String? = null,
    ): MessageSinkReceipt? {
        val normalizedMessageId = sinkMessageId.normalized() ?: return null
        val targetKey = target.stableValue()
        val accountKey = sinkAccountId.normalized()
        if (accountKey != null) {
            return findOne(platformId, targetKey, normalizedMessageId, accountKey)
        }
        return findOne(platformId, targetKey, normalizedMessageId, null)
    }

    public fun findByMessageId(messageId: String): List<MessageSinkReceipt> {
        val normalizedMessageId = messageId.normalized() ?: return emptyList()
        return transaction {
            MessageSinkReceiptTable
                .selectAll()
                .where { MessageSinkReceiptTable.messageId eq normalizedMessageId }
                .orderBy(MessageSinkReceiptTable.createdAt to SortOrder.DESC, MessageSinkReceiptTable.id to SortOrder.DESC)
                .map { it.toMessageSinkReceipt() }
        }
    }

    public fun deleteByDeliveryIds(deliveryIds: Collection<Int>): Int {
        val ids = deliveryIds.distinct()
        if (ids.isEmpty()) return 0
        return transaction {
            MessageSinkReceiptTable.deleteWhere { MessageSinkReceiptTable.deliveryId inList ids }
        }
    }

    private fun findOne(
        platformId: PlatformId,
        targetKey: String,
        sinkMessageId: String,
        sinkAccountKey: String?,
    ): MessageSinkReceipt? {
        return transaction {
            val baseFilter = (MessageSinkReceiptTable.platformId eq platformId.value) and
                (MessageSinkReceiptTable.targetKey eq targetKey) and
                (MessageSinkReceiptTable.sinkMessageId eq sinkMessageId)
            val filter = sinkAccountKey?.let { baseFilter and (MessageSinkReceiptTable.sinkAccountKey eq it) }
                ?: baseFilter
            MessageSinkReceiptTable
                .selectAll()
                .where { filter }
                .orderBy(MessageSinkReceiptTable.createdAt to SortOrder.DESC, MessageSinkReceiptTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toMessageSinkReceipt()
        }
    }

    private fun MessageSendResult.Sent.actualSinkMessageIds(): List<String> {
        val explicitIds = sinkMessageIds.mapNotNull { it.normalized() }
        if (explicitIds.isNotEmpty()) return explicitIds.distinct()
        return listOfNotNull(sinkMessageId.normalized()).distinct()
    }

    private fun IncomingMessage.replyMessageId(): String? {
        replyTo?.messageId.normalized()?.let { return it }
        return segments
            .asSequence()
            .filterIsInstance<IncomingMessageSegment.Reply>()
            .mapNotNull { it.messageId.normalized() }
            .firstOrNull()
    }
}

private fun ResultRow.toMessageSinkReceipt(): MessageSinkReceipt = MessageSinkReceipt(
    id = this[MessageSinkReceiptTable.id].value,
    transportId = this[MessageSinkReceiptTable.transportId],
    platformId = PlatformId.of(this[MessageSinkReceiptTable.platformId]),
    target = TargetAddress(
        platformId = PlatformId.of(this[MessageSinkReceiptTable.platformId]),
        kind = this[MessageSinkReceiptTable.targetKind],
        externalId = this[MessageSinkReceiptTable.targetId],
        scopeId = this[MessageSinkReceiptTable.scopeId],
        threadId = this[MessageSinkReceiptTable.threadId],
        accountId = this[MessageSinkReceiptTable.targetAccountId],
    ),
    sinkMessageId = this[MessageSinkReceiptTable.sinkMessageId],
    sinkRouteId = this[MessageSinkReceiptTable.sinkRouteId],
    sinkAccountId = this[MessageSinkReceiptTable.sinkAccountId],
    deliveryId = this[MessageSinkReceiptTable.deliveryId],
    messageId = this[MessageSinkReceiptTable.messageId],
    sourceUpdateKey = this[MessageSinkReceiptTable.sourceUpdateKey],
    renderVariant = this[MessageSinkReceiptTable.renderVariant],
    createdAtEpochSeconds = this[MessageSinkReceiptTable.createdAt].epochSeconds,
)

private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotBlank() }
