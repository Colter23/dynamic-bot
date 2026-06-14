package top.colter.dynamic.repository

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.tools.nowInstant
import top.colter.dynamic.table.MessageDeliveryTable
import top.colter.dynamic.table.SourceUpdateSnapshotTable

public data class SourceUpdateSnapshot(
    val updateKey: UpdateKey,
    val sourcePlugin: String,
    val update: SourceUpdate,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
)

public object SourceUpdateSnapshotRepository {
    public fun upsert(sourcePlugin: String, update: SourceUpdate): SourceUpdateSnapshot {
        val normalizedSourcePlugin = sourcePlugin.trim().takeIf { it.isNotBlank() } ?: "unknown"
        val stableKey = update.key.stableValue()
        val now = nowInstant()
        transaction {
            SourceUpdateSnapshotTable.insertIgnore {
                it[SourceUpdateSnapshotTable.updateKeyStable] = stableKey
                it[SourceUpdateSnapshotTable.updateKey] = update.key
                it[SourceUpdateSnapshotTable.sourcePlugin] = normalizedSourcePlugin
                it[SourceUpdateSnapshotTable.publisherKey] = update.key.publisherKey.stableValue()
                it[SourceUpdateSnapshotTable.eventType] = update.eventType.value
                it[SourceUpdateSnapshotTable.sourceUpdate] = update
                it[SourceUpdateSnapshotTable.createdAt] = now
                it[SourceUpdateSnapshotTable.updatedAt] = now
            }
            SourceUpdateSnapshotTable.update({ SourceUpdateSnapshotTable.updateKeyStable eq stableKey }) {
                it[SourceUpdateSnapshotTable.updateKey] = update.key
                it[SourceUpdateSnapshotTable.sourcePlugin] = normalizedSourcePlugin
                it[SourceUpdateSnapshotTable.publisherKey] = update.key.publisherKey.stableValue()
                it[SourceUpdateSnapshotTable.eventType] = update.eventType.value
                it[SourceUpdateSnapshotTable.sourceUpdate] = update
                it[SourceUpdateSnapshotTable.updatedAt] = now
            }
        }
        return findByUpdateKey(update.key) ?: error("来源更新快照写入后不可见：$stableKey")
    }

    public fun findByUpdateKey(updateKey: UpdateKey): SourceUpdateSnapshot? =
        findByStableKey(updateKey.stableValue())

    public fun findUpdate(updateKey: UpdateKey): SourceUpdate? =
        findByUpdateKey(updateKey)?.update

    public fun findByStableKey(stableKey: String): SourceUpdateSnapshot? {
        val key = stableKey.trim().takeIf { it.isNotBlank() } ?: return null
        return transaction {
            SourceUpdateSnapshotTable
                .selectAll()
                .where { SourceUpdateSnapshotTable.updateKeyStable eq key }
                .firstOrNull()
                ?.toSourceUpdateSnapshot()
        }
    }

    public fun cleanupOrphaned(
        cutoffEpochSeconds: Long,
        batchSize: Int = 1_000,
        maxBatches: Int = 20,
    ): Int {
        val safeBatchSize = batchSize.coerceIn(1, 10_000)
        val safeMaxBatches = maxBatches.coerceIn(1, 1_000)
        val cutoff = Instant.fromEpochSeconds(cutoffEpochSeconds)
        var deleted = 0
        var lastSeenKey: String? = null
        repeat(safeMaxBatches) {
            val candidates = transaction {
                val baseFilter = SourceUpdateSnapshotTable.updatedAt less cutoff
                val filter = lastSeenKey?.let { key ->
                    baseFilter and (SourceUpdateSnapshotTable.updateKeyStable greater key)
                } ?: baseFilter
                SourceUpdateSnapshotTable
                    .selectAll()
                    .where { filter }
                    .orderBy(SourceUpdateSnapshotTable.updateKeyStable to SortOrder.ASC)
                    .limit(safeBatchSize)
                    .map { it[SourceUpdateSnapshotTable.updateKeyStable] to it[SourceUpdateSnapshotTable.updateKey] }
            }
            if (candidates.isEmpty()) return deleted
            lastSeenKey = candidates.last().first

            val removableKeys = transaction {
                candidates.mapNotNull { (stableKey, updateKey) ->
                    val stillReferenced = MessageDeliveryTable
                        .selectAll()
                        .where { MessageDeliveryTable.sourceUpdateKey eq updateKey }
                        .limit(1)
                        .any()
                    stableKey.takeIf { !stillReferenced }
                }
            }
            if (removableKeys.isEmpty()) return@repeat

            deleted += transaction {
                SourceUpdateSnapshotTable.deleteWhere { SourceUpdateSnapshotTable.updateKeyStable inList removableKeys }
            }
        }
        return deleted
    }
}

private fun ResultRow.toSourceUpdateSnapshot(): SourceUpdateSnapshot = SourceUpdateSnapshot(
    updateKey = this[SourceUpdateSnapshotTable.updateKey],
    sourcePlugin = this[SourceUpdateSnapshotTable.sourcePlugin],
    update = this[SourceUpdateSnapshotTable.sourceUpdate],
    createdAtEpochSeconds = this[SourceUpdateSnapshotTable.createdAt].epochSeconds,
    updatedAtEpochSeconds = this[SourceUpdateSnapshotTable.updatedAt].epochSeconds,
)
