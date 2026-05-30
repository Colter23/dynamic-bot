package top.colter.dynamic.repository

import java.util.LinkedHashSet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.hasSeen
import top.colter.dynamic.table.PublisherTable
import top.colter.dynamic.table.SourceCursorTable
import top.colter.dynamic.table.coreTableJson

private val recentUpdateKeysSerializer = ListSerializer(String.serializer())

public object SourceCursorRepository {
    private const val MAX_RECENT_UPDATE_KEYS: Int = 50
    private const val BASELINE_UPDATE_KEY_PREFIX: String = "__baseline__"

    public fun find(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
    ): SourceCursor? {
        return transaction {
            SourceCursorTable
                .selectAll()
                .where {
                    (SourceCursorTable.publisherId eq EntityID(publisherId, PublisherTable)) and
                        (SourceCursorTable.sourceKey eq sourceKey) and
                        (SourceCursorTable.eventType eq eventType.value)
                }
                .firstOrNull()
                ?.toSourceCursor()
        }
    }

    public fun hasSeen(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
        updateKey: String,
    ): Boolean {
        val state = find(publisherId, sourceKey, eventType) ?: return false
        return state.hasSeen(updateKey)
    }

    public fun ensureBaseline(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
        timestamp: Long,
    ): SourceCursor {
        find(publisherId, sourceKey, eventType)?.let { return it }
        return markSeen(
            publisherId = publisherId,
            sourceKey = sourceKey,
            eventType = eventType,
            updateKey = "$BASELINE_UPDATE_KEY_PREFIX$timestamp",
            timestamp = timestamp,
        )
    }

    public fun markSeen(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
        updateKey: String,
        timestamp: Long,
    ): SourceCursor {
        require(PublisherRepository.findById(publisherId) != null) { "发布者不存在：publisherId=$publisherId" }

        val previous = find(publisherId, sourceKey, eventType)
        val recentUpdateKeys = updateRecentUpdateKeys(previous, updateKey)
        val updated = SourceCursor(
            publisherId = publisherId,
            sourceKey = sourceKey,
            eventType = eventType,
            lastSeenUpdateKey = updateKey,
            lastSeenAtEpochSeconds = timestamp,
            recentUpdateKeys = recentUpdateKeys,
        )

        transaction {
            if (previous == null) {
                SourceCursorTable.insert {
                    it[SourceCursorTable.publisherId] = EntityID(updated.publisherId, PublisherTable)
                    it[SourceCursorTable.sourceKey] = updated.sourceKey
                    it[SourceCursorTable.eventType] = updated.eventType.value
                    it[SourceCursorTable.lastSeenUpdateKey] = updated.lastSeenUpdateKey
                    it[SourceCursorTable.lastSeenAt] = updated.lastSeenAtEpochSeconds
                    it[SourceCursorTable.recentUpdateKeys] = encodeRecentUpdateKeys(updated.recentUpdateKeys)
                }
            } else {
                SourceCursorTable.update({
                    (SourceCursorTable.publisherId eq EntityID(publisherId, PublisherTable)) and
                        (SourceCursorTable.sourceKey eq sourceKey) and
                        (SourceCursorTable.eventType eq eventType.value)
                }) {
                    it[SourceCursorTable.lastSeenUpdateKey] = updated.lastSeenUpdateKey
                    it[SourceCursorTable.lastSeenAt] = updated.lastSeenAtEpochSeconds
                    it[SourceCursorTable.recentUpdateKeys] = encodeRecentUpdateKeys(updated.recentUpdateKeys)
                }
            }
        }

        return updated
    }

    public fun deleteByPublisherId(publisherId: Int): Boolean {
        return transaction {
            SourceCursorTable.deleteWhere {
                SourceCursorTable.publisherId eq EntityID(publisherId, PublisherTable)
            } > 0
        }
    }

    private fun updateRecentUpdateKeys(previous: SourceCursor?, updateKey: String): List<String> {
        val dedupe = LinkedHashSet(previous?.recentUpdateKeys ?: emptyList())
        dedupe.add(updateKey)
        while (dedupe.size > MAX_RECENT_UPDATE_KEYS) {
            dedupe.remove(dedupe.first())
        }
        return dedupe.toList()
    }
}

private fun encodeRecentUpdateKeys(keys: List<String>): String {
    return coreTableJson.encodeToString(recentUpdateKeysSerializer, keys)
}

private fun decodeRecentUpdateKeys(value: String): List<String> {
    if (value.isBlank()) return emptyList()
    return coreTableJson.decodeFromString(recentUpdateKeysSerializer, value)
}

public fun ResultRow.toSourceCursor(): SourceCursor {
    return SourceCursor(
        publisherId = this[SourceCursorTable.publisherId].value,
        sourceKey = this[SourceCursorTable.sourceKey],
        eventType = SourceEventType.of(this[SourceCursorTable.eventType]),
        lastSeenUpdateKey = this[SourceCursorTable.lastSeenUpdateKey],
        lastSeenAtEpochSeconds = this[SourceCursorTable.lastSeenAt],
        recentUpdateKeys = decodeRecentUpdateKeys(this[SourceCursorTable.recentUpdateKeys]),
    )
}
