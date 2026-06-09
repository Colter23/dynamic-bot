package top.colter.dynamic.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.table.PublisherLiveStatusTable
import top.colter.dynamic.table.PublisherTable

public object PublisherLiveStatusRepository {
    public fun findByPublisherId(publisherId: Int): PublisherLiveStatus? {
        return transaction {
            PublisherLiveStatusTable
                .selectAll()
                .where { PublisherLiveStatusTable.publisherId eq EntityID(publisherId, PublisherTable) }
                .firstOrNull()
                ?.toPublisherLiveStatus()
        }
    }

    public fun findAllByPublisherId(publisherId: Int): List<PublisherLiveStatus> {
        return transaction {
            PublisherLiveStatusTable
                .selectAll()
                .where { PublisherLiveStatusTable.publisherId eq EntityID(publisherId, PublisherTable) }
                .map { it.toPublisherLiveStatus() }
        }
    }

    public fun upsert(state: PublisherLiveStatus): PublisherLiveStatus {
        require(PublisherRepository.findById(state.publisherId) != null) {
            "发布者不存在：publisherId=${state.publisherId}"
        }

        transaction {
            val existing = PublisherLiveStatusTable
                .selectAll()
                .where { PublisherLiveStatusTable.publisherId eq EntityID(state.publisherId, PublisherTable) }
                .firstOrNull()

            if (existing == null) {
                PublisherLiveStatusTable.insert {
                    it[publisherId] = EntityID(state.publisherId, PublisherTable)
                    it[roomId] = state.roomId
                    it[status] = state.status
                    it[title] = state.title
                    it[cover] = state.cover
                    it[area] = state.area
                    it[startedAt] = state.startedAtEpochSeconds
                    it[lastObservedAt] = state.lastObservedAtEpochSeconds
                }
            } else {
                PublisherLiveStatusTable.update({
                    PublisherLiveStatusTable.publisherId eq EntityID(state.publisherId, PublisherTable)
                }) {
                    it[roomId] = state.roomId
                    it[status] = state.status
                    it[title] = state.title
                    it[cover] = state.cover
                    it[area] = state.area
                    it[startedAt] = state.startedAtEpochSeconds
                    it[lastObservedAt] = state.lastObservedAtEpochSeconds
                }
            }
        }

        return state
    }

    public fun findAll(): List<PublisherLiveStatus> {
        return transaction {
            PublisherLiveStatusTable.selectAll().map { it.toPublisherLiveStatus() }
        }
    }

    public fun deleteByPublisherId(publisherId: Int): Boolean {
        return transaction {
            PublisherLiveStatusTable.deleteWhere {
                PublisherLiveStatusTable.publisherId eq EntityID(publisherId, PublisherTable)
            } > 0
        }
    }
}

public fun ResultRow.toPublisherLiveStatus(): PublisherLiveStatus {
    return PublisherLiveStatus(
        publisherId = this[PublisherLiveStatusTable.publisherId].value,
        roomId = this[PublisherLiveStatusTable.roomId],
        status = this[PublisherLiveStatusTable.status],
        title = this[PublisherLiveStatusTable.title],
        cover = this[PublisherLiveStatusTable.cover],
        area = this[PublisherLiveStatusTable.area],
        startedAtEpochSeconds = this[PublisherLiveStatusTable.startedAt],
        lastObservedAtEpochSeconds = this[PublisherLiveStatusTable.lastObservedAt],
    )
}
