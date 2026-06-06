package top.colter.dynamic.repository

import java.util.LinkedHashSet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
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
                .where { sourceCursorFilter(publisherId, sourceKey, eventType) }
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

        return transaction {
            val filter = sourceCursorFilter(publisherId, sourceKey, eventType)
            val initial = SourceCursor(
                publisherId = publisherId,
                sourceKey = sourceKey,
                eventType = eventType,
                lastSeenUpdateKey = updateKey,
                lastSeenAtEpochSeconds = timestamp,
                recentUpdateKeys = listOf(updateKey),
            )
            val inserted = SourceCursorTable.insertIgnore {
                it[SourceCursorTable.publisherId] = EntityID(initial.publisherId, PublisherTable)
                it[SourceCursorTable.sourceKey] = initial.sourceKey
                it[SourceCursorTable.eventType] = initial.eventType.value
                it[SourceCursorTable.lastSeenUpdateKey] = initial.lastSeenUpdateKey
                it[SourceCursorTable.lastSeenAt] = initial.lastSeenAtEpochSeconds
                it[SourceCursorTable.recentUpdateKeys] = encodeRecentUpdateKeys(initial.recentUpdateKeys)
            }.insertedCount > 0
            if (inserted) {
                return@transaction initial
            }

            val previous = SourceCursorTable
                .selectAll()
                .where { filter }
                .firstOrNull()
                ?.toSourceCursor()
            val updated = initial.copy(recentUpdateKeys = updateRecentUpdateKeys(previous, updateKey))
            SourceCursorTable.update({ filter }) {
                it[SourceCursorTable.lastSeenUpdateKey] = updated.lastSeenUpdateKey
                it[SourceCursorTable.lastSeenAt] = updated.lastSeenAtEpochSeconds
                it[SourceCursorTable.recentUpdateKeys] = encodeRecentUpdateKeys(updated.recentUpdateKeys)
            }
            updated
        }
    }

    public fun deleteByPublisherId(publisherId: Int): Boolean {
        return transaction {
            SourceCursorTable.deleteWhere {
                SourceCursorTable.publisherId eq EntityID(publisherId, PublisherTable)
            } > 0
        }
    }

    public fun findAll(): List<SourceCursor> {
        return transaction {
            SourceCursorTable
                .selectAll()
                .map { it.toSourceCursor() }
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

private fun sourceCursorFilter(
    publisherId: Int,
    sourceKey: String,
    eventType: SourceEventType,
): Op<Boolean> {
    return (SourceCursorTable.publisherId eq EntityID(publisherId, PublisherTable)) and
        (SourceCursorTable.sourceKey eq sourceKey) and
        (SourceCursorTable.eventType eq eventType.value)
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
