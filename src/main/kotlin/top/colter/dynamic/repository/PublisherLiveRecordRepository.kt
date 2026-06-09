package top.colter.dynamic.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.tools.nowInstant
import top.colter.dynamic.table.PublisherLiveRecordTable
import top.colter.dynamic.table.PublisherTable

public data class PublisherLiveRecord(
    val id: Int,
    val publisherId: Int,
    val roomId: String,
    val title: String,
    val coverUri: String?,
    val area: String?,
    val startedAtEpochSeconds: Long,
    val endedAtEpochSeconds: Long?,
    val lastObservedAtEpochSeconds: Long,
    val startUpdateKey: String?,
    val endUpdateKey: String?,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
) {
    public val durationSeconds: Long?
        get() = endedAtEpochSeconds?.minus(startedAtEpochSeconds)?.coerceAtLeast(0)
}

public object PublisherLiveRecordRepository {
    public fun recordLiveEvent(publisherId: Int, update: SourceUpdate): PublisherLiveRecord? {
        val payload = update.payload as? LivePayload ?: return null
        return when (update.eventType) {
            SourceEventType.LIVE_STARTED -> recordStarted(publisherId, update, payload)
            SourceEventType.LIVE_ENDED -> recordEnded(publisherId, update, payload)
            else -> null
        }
    }

    public fun recordStarted(
        publisherId: Int,
        update: SourceUpdate,
        payload: LivePayload,
    ): PublisherLiveRecord {
        require(PublisherRepository.findById(publisherId) != null) {
            "发布者不存在：publisherId=$publisherId"
        }
        val observedAt = update.observedAtEpochSeconds ?: update.occurredAtEpochSeconds
        val startedAt = payload.startedAtEpochSeconds ?: update.occurredAtEpochSeconds
        val roomId = payload.roomId.trim()
        val updateKey = update.key.stableValue()
        return transaction {
            closeStaleOpenRecords(publisherId, roomId, startedAt, startedAt)
            val existing = findByPublisherRoomStartedInCurrentTransaction(publisherId, roomId, startedAt)
            val now = nowInstant()
            if (existing == null) {
                val id = PublisherLiveRecordTable.insertAndGetId {
                    it[PublisherLiveRecordTable.publisherId] = EntityID(publisherId, PublisherTable)
                    it[PublisherLiveRecordTable.roomId] = roomId
                    it[title] = payload.title
                    it[cover] = payload.cover
                    it[area] = payload.area
                    it[PublisherLiveRecordTable.startedAt] = startedAt
                    it[endedAt] = null
                    it[lastObservedAt] = observedAt
                    it[startUpdateKey] = updateKey
                    it[endUpdateKey] = null
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
                findByIdInCurrentTransaction(id)
            } else {
                PublisherLiveRecordTable.update({ PublisherLiveRecordTable.id eq existing[PublisherLiveRecordTable.id] }) {
                    it[title] = payload.title
                    it[cover] = payload.cover
                    it[area] = payload.area
                    it[lastObservedAt] = observedAt
                    it[startUpdateKey] = existing[PublisherLiveRecordTable.startUpdateKey] ?: updateKey
                    it[updatedAt] = now
                }
                findByIdInCurrentTransaction(existing[PublisherLiveRecordTable.id].value)
            }
        }
    }

    public fun recordEnded(
        publisherId: Int,
        update: SourceUpdate,
        payload: LivePayload,
    ): PublisherLiveRecord {
        require(PublisherRepository.findById(publisherId) != null) {
            "发布者不存在：publisherId=$publisherId"
        }
        val observedAt = update.observedAtEpochSeconds ?: update.occurredAtEpochSeconds
        val endedAt = payload.endedAtEpochSeconds ?: update.occurredAtEpochSeconds
        val roomId = payload.roomId.trim()
        val updateKey = update.key.stableValue()
        return transaction {
            val now = nowInstant()
            val startedAtHint = payload.startedAtEpochSeconds
            val existing = startedAtHint
                ?.let { startedAt -> findByPublisherRoomStartedInCurrentTransaction(publisherId, roomId, startedAt) }
                ?: startedAtHint
                    ?.let { startedAt -> findOpenByPublisherStartedInCurrentTransaction(publisherId, roomId, startedAt) }
                ?: findLatestOpenInCurrentTransaction(publisherId, roomId)
                    ?.takeIf { startedAtHint == null }
                ?: findLatestOpenByPublisherInCurrentTransaction(publisherId)
                    ?.takeIf { row ->
                        val openRoomId = row[PublisherLiveRecordTable.roomId]
                        startedAtHint == null &&
                            (openRoomId.isBlank() || roomId.isBlank())
                    }

            if (existing == null) {
                val startedAt = payload.startedAtEpochSeconds ?: endedAt
                val id = PublisherLiveRecordTable.insertAndGetId {
                    it[PublisherLiveRecordTable.publisherId] = EntityID(publisherId, PublisherTable)
                    it[PublisherLiveRecordTable.roomId] = roomId
                    it[title] = payload.title
                    it[cover] = payload.cover
                    it[area] = payload.area
                    it[PublisherLiveRecordTable.startedAt] = startedAt
                    it[PublisherLiveRecordTable.endedAt] = endedAt.coerceAtLeast(startedAt)
                    it[lastObservedAt] = observedAt
                    it[startUpdateKey] = null
                    it[endUpdateKey] = updateKey
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
                findByIdInCurrentTransaction(id)
            } else {
                val startedAt = existing[PublisherLiveRecordTable.startedAt]
                PublisherLiveRecordTable.update({ PublisherLiveRecordTable.id eq existing[PublisherLiveRecordTable.id] }) {
                    if (roomId.isNotBlank()) it[PublisherLiveRecordTable.roomId] = roomId
                    it[title] = payload.title
                    it[cover] = payload.cover
                    it[area] = payload.area
                    it[PublisherLiveRecordTable.endedAt] = endedAt.coerceAtLeast(startedAt)
                    it[lastObservedAt] = observedAt
                    it[endUpdateKey] = existing[PublisherLiveRecordTable.endUpdateKey] ?: updateKey
                    it[updatedAt] = now
                }
                findByIdInCurrentTransaction(existing[PublisherLiveRecordTable.id].value)
            }
        }
    }

    public fun findRecentByPublisherId(publisherId: Int, limit: Int = 20): List<PublisherLiveRecord> {
        val safeLimit = limit.coerceIn(1, 200)
        return transaction {
            PublisherLiveRecordTable
                .selectAll()
                .where { PublisherLiveRecordTable.publisherId eq EntityID(publisherId, PublisherTable) }
                .orderBy(
                    PublisherLiveRecordTable.startedAt to SortOrder.DESC,
                    PublisherLiveRecordTable.id to SortOrder.DESC,
                )
                .limit(safeLimit)
                .map { it.toPublisherLiveRecord() }
        }
    }

    public fun findRecentByPublisherIds(
        publisherIds: Collection<Int>,
        limitPerPublisher: Int = 10,
    ): Map<Int, List<PublisherLiveRecord>> {
        val ids = publisherIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val safeLimit = limitPerPublisher.coerceIn(1, 200)
        return transaction {
            ids.associateWith { publisherId ->
                PublisherLiveRecordTable
                    .selectAll()
                    .where { PublisherLiveRecordTable.publisherId eq EntityID(publisherId, PublisherTable) }
                    .orderBy(
                        PublisherLiveRecordTable.startedAt to SortOrder.DESC,
                        PublisherLiveRecordTable.id to SortOrder.DESC,
                    )
                    .limit(safeLimit)
                    .map { it.toPublisherLiveRecord() }
            }
        }
    }

    public fun findAll(): List<PublisherLiveRecord> {
        return transaction {
            PublisherLiveRecordTable.selectAll().map { it.toPublisherLiveRecord() }
        }
    }

    public fun deleteByPublisherId(publisherId: Int): Boolean {
        return transaction {
            PublisherLiveRecordTable.deleteWhere {
                PublisherLiveRecordTable.publisherId eq EntityID(publisherId, PublisherTable)
            } > 0
        }
    }

    private fun closeStaleOpenRecords(
        publisherId: Int,
        roomId: String,
        startedAt: Long,
        fallbackEndedAt: Long,
    ) {
        val stale = PublisherLiveRecordTable
            .selectAll()
            .where { PublisherLiveRecordTable.publisherId eq EntityID(publisherId, PublisherTable) }
            .filter { row ->
                row[PublisherLiveRecordTable.endedAt] == null &&
                    (row[PublisherLiveRecordTable.roomId] != roomId || row[PublisherLiveRecordTable.startedAt] != startedAt)
            }
        if (stale.isEmpty()) return
        val now = nowInstant()
        stale.forEach { row ->
            val rowStartedAt = row[PublisherLiveRecordTable.startedAt]
            PublisherLiveRecordTable.update({ PublisherLiveRecordTable.id eq row[PublisherLiveRecordTable.id] }) {
                it[endedAt] = fallbackEndedAt.coerceAtLeast(rowStartedAt)
                it[lastObservedAt] = fallbackEndedAt
                it[updatedAt] = now
            }
        }
    }

    private fun findLatestOpenInCurrentTransaction(publisherId: Int, roomId: String): ResultRow? {
        return PublisherLiveRecordTable
            .selectAll()
            .where {
                (PublisherLiveRecordTable.publisherId eq EntityID(publisherId, PublisherTable)) and
                    (PublisherLiveRecordTable.roomId eq roomId)
            }
            .orderBy(
                PublisherLiveRecordTable.startedAt to SortOrder.DESC,
                PublisherLiveRecordTable.id to SortOrder.DESC,
            )
            .firstOrNull { it[PublisherLiveRecordTable.endedAt] == null }
    }

    private fun findOpenByPublisherStartedInCurrentTransaction(
        publisherId: Int,
        roomId: String,
        startedAt: Long,
    ): ResultRow? {
        return PublisherLiveRecordTable
            .selectAll()
            .where {
                (PublisherLiveRecordTable.publisherId eq EntityID(publisherId, PublisherTable)) and
                    (PublisherLiveRecordTable.startedAt eq startedAt)
            }
            .orderBy(PublisherLiveRecordTable.id to SortOrder.DESC)
            .firstOrNull { row ->
                row[PublisherLiveRecordTable.endedAt] == null &&
                    (row[PublisherLiveRecordTable.roomId] == roomId ||
                        row[PublisherLiveRecordTable.roomId].isBlank() ||
                        roomId.isBlank())
            }
    }

    private fun findLatestOpenByPublisherInCurrentTransaction(publisherId: Int): ResultRow? {
        return PublisherLiveRecordTable
            .selectAll()
            .where { PublisherLiveRecordTable.publisherId eq EntityID(publisherId, PublisherTable) }
            .orderBy(
                PublisherLiveRecordTable.startedAt to SortOrder.DESC,
                PublisherLiveRecordTable.id to SortOrder.DESC,
            )
            .firstOrNull { it[PublisherLiveRecordTable.endedAt] == null }
    }

    private fun findByPublisherRoomStartedInCurrentTransaction(
        publisherId: Int,
        roomId: String,
        startedAt: Long,
    ): ResultRow? {
        return PublisherLiveRecordTable
            .selectAll()
            .where {
                (PublisherLiveRecordTable.publisherId eq EntityID(publisherId, PublisherTable)) and
                    (PublisherLiveRecordTable.roomId eq roomId) and
                    (PublisherLiveRecordTable.startedAt eq startedAt)
            }
            .firstOrNull()
    }

    private fun findByIdInCurrentTransaction(id: Int): PublisherLiveRecord {
        return PublisherLiveRecordTable
            .selectAll()
            .where { PublisherLiveRecordTable.id eq id }
            .first()
            .toPublisherLiveRecord()
    }
}

public fun ResultRow.toPublisherLiveRecord(): PublisherLiveRecord = PublisherLiveRecord(
    id = this[PublisherLiveRecordTable.id].value,
    publisherId = this[PublisherLiveRecordTable.publisherId].value,
    roomId = this[PublisherLiveRecordTable.roomId],
    title = this[PublisherLiveRecordTable.title],
    coverUri = this[PublisherLiveRecordTable.cover]?.uri,
    area = this[PublisherLiveRecordTable.area],
    startedAtEpochSeconds = this[PublisherLiveRecordTable.startedAt],
    endedAtEpochSeconds = this[PublisherLiveRecordTable.endedAt],
    lastObservedAtEpochSeconds = this[PublisherLiveRecordTable.lastObservedAt],
    startUpdateKey = this[PublisherLiveRecordTable.startUpdateKey],
    endUpdateKey = this[PublisherLiveRecordTable.endUpdateKey],
    createdAtEpochSeconds = this[PublisherLiveRecordTable.createdAt].epochSeconds,
    updatedAtEpochSeconds = this[PublisherLiveRecordTable.updatedAt].epochSeconds,
)
