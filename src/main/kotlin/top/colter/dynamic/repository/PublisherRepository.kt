package top.colter.dynamic.repository

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.table.PublisherTable
import top.colter.dynamic.core.tools.nowInstant

public data class UpsertResult<T>(
    val value: T,
    val created: Boolean,
    val updated: Boolean,
)

public object PublisherRepository {
    public fun create(publisher: Publisher): Int {
        return transaction {
            PublisherTable.insert {
                it[id] = publisher.id
                it[platformId] = publisher.key.platformId.value
                it[kind] = publisher.key.kind
                it[externalId] = publisher.key.externalId
                it[name] = publisher.name
                it[avatarBadgeKey] = publisher.avatarBadgeKey
                it[state] = publisher.state
                it[avatar] = publisher.avatar
                it[banner] = publisher.banner
                it[pendant] = publisher.pendant
                it[createTime] = Instant.fromEpochSeconds(publisher.createTime)
                it[createUser] = publisher.createUser
            }
            publisher.id
        }
    }

    public fun replace(publisher: Publisher): Boolean {
        return transaction {
            PublisherTable.update({ PublisherTable.id eq publisher.id }) {
                it[platformId] = publisher.key.platformId.value
                it[kind] = publisher.key.kind
                it[externalId] = publisher.key.externalId
                it[name] = publisher.name
                it[avatarBadgeKey] = publisher.avatarBadgeKey
                it[state] = publisher.state
                it[avatar] = publisher.avatar
                it[banner] = publisher.banner
                it[pendant] = publisher.pendant
                it[createTime] = Instant.fromEpochSeconds(publisher.createTime)
                it[createUser] = publisher.createUser
            } > 0
        }
    }

    public fun findById(id: Int): Publisher? {
        return transaction {
            PublisherTable.selectAll().where { PublisherTable.id eq id }.firstOrNull()?.toPublisher()
        }
    }

    public fun deleteById(id: Int): Boolean {
        return transaction {
            PublisherTable.deleteWhere { PublisherTable.id eq id } > 0
        }
    }

    public fun findByKey(key: PublisherKey): Publisher? {
        return transaction {
            PublisherTable
                .selectAll()
                .where {
                    (PublisherTable.platformId eq key.platformId.value) and
                        (PublisherTable.kind eq key.kind) and
                        (PublisherTable.externalId eq key.externalId)
                }
                .firstOrNull()
                ?.toPublisher()
        }
    }

    public fun findAll(): List<Publisher> {
        return transaction {
            PublisherTable.selectAll().map { it.toPublisher() }
        }
    }

    public fun countAll(): Long {
        return transaction {
            PublisherTable.selectAll().count()
        }
    }

    public fun upsertInfo(info: PublisherInfo, createUser: Int = 0): UpsertResult<Publisher> {
        val existed = findByKey(info.key)
        if (existed != null) {
            val updatedPublisher = existed.copy(
                key = info.key,
                name = info.name,
                avatarBadgeKey = info.avatarBadgeKey,
                state = info.state,
                avatar = info.avatar,
                banner = info.banner,
                pendant = info.pendant,
            )
            val changed = updatedPublisher != existed
            if (changed) replace(updatedPublisher)
            return UpsertResult(updatedPublisher, created = false, updated = changed)
        }

        return transaction {
            val now = nowInstant()
            val id = PublisherTable.insertAndGetId {
                it[platformId] = info.key.platformId.value
                it[kind] = info.key.kind
                it[externalId] = info.key.externalId
                it[name] = info.name
                it[avatarBadgeKey] = info.avatarBadgeKey
                it[state] = info.state
                it[avatar] = info.avatar
                it[banner] = info.banner
                it[pendant] = info.pendant
                it[createTime] = now
                it[PublisherTable.createUser] = createUser
            }.value
            UpsertResult(
                value = Publisher(
                    id = id,
                    key = info.key,
                    name = info.name,
                    avatarBadgeKey = info.avatarBadgeKey,
                    state = info.state,
                    avatar = info.avatar,
                    banner = info.banner,
                    pendant = info.pendant,
                    createTime = now.epochSeconds,
                    createUser = createUser,
                ),
                created = true,
                updated = false,
            )
        }
    }
}

public fun ResultRow.toPublisher(): Publisher = Publisher(
    id = this[PublisherTable.id].value,
    key = PublisherKey.of(
        platformId = this[PublisherTable.platformId],
        kind = this[PublisherTable.kind],
        externalId = this[PublisherTable.externalId],
    ),
    name = this[PublisherTable.name],
    avatarBadgeKey = this[PublisherTable.avatarBadgeKey],
    state = this[PublisherTable.state],
    avatar = this[PublisherTable.avatar],
    banner = this[PublisherTable.banner],
    pendant = this[PublisherTable.pendant],
    createTime = this[PublisherTable.createTime].epochSeconds,
    createUser = this[PublisherTable.createUser],
)
