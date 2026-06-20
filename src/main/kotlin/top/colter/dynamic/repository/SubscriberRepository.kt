package top.colter.dynamic.repository

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberState
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.table.SubscriberTable
import top.colter.dynamic.core.tools.nowInstant

public object SubscriberRepository {
    public fun create(subscriber: Subscriber): Int {
        val createdId = transaction {
            SubscriberTable.insert {
                it[id] = subscriber.id
                it.writeAddress(subscriber.address)
                it[name] = subscriber.name
                it[avatar] = subscriber.avatar
                it[state] = subscriber.state
                it[createTime] = Instant.fromEpochSeconds(subscriber.createTime)
                it[createUser] = subscriber.createUser
            }
            subscriber.id
        }
        SubscriberStateCache.update(subscriber.address, subscriber.state)
        return createdId
    }

    public fun replace(subscriber: Subscriber): Boolean {
        val previous = findById(subscriber.id)
        val replaced = transaction {
            SubscriberTable.update({ SubscriberTable.id eq subscriber.id }) {
                it.writeAddress(subscriber.address)
                it[name] = subscriber.name
                it[avatar] = subscriber.avatar
                it[state] = subscriber.state
                it[createTime] = Instant.fromEpochSeconds(subscriber.createTime)
                it[createUser] = subscriber.createUser
            } > 0
        }
        if (replaced) {
            if (previous?.address != null && previous.address != subscriber.address) {
                SubscriberStateCache.remove(previous.address)
            }
            SubscriberStateCache.update(subscriber.address, subscriber.state)
        }
        return replaced
    }

    public fun findById(id: Int): Subscriber? {
        return transaction {
            SubscriberTable.selectAll().where { SubscriberTable.id eq id }.firstOrNull()?.toSubscriber()
        }
    }

    public fun findAll(): List<Subscriber> {
        return transaction {
            SubscriberTable.selectAll().map { it.toSubscriber() }
        }
    }

    public fun findNonActiveStates(): Map<String, SubscriberState> {
        return transaction {
            SubscriberTable
                .selectAll()
                .where { SubscriberTable.state neq SubscriberState.ACTIVE }
                .associate { row -> row[SubscriberTable.targetKey] to row[SubscriberTable.state] }
        }
    }

    public fun deleteById(id: Int): Boolean {
        val existing = findById(id)
        val deleted = transaction {
            SubscriberTable.deleteWhere { SubscriberTable.id eq id } > 0
        }
        if (deleted && existing != null) {
            SubscriberStateCache.remove(existing.address)
        }
        return deleted
    }

    public fun findByIds(ids: Collection<Int>): List<Subscriber> {
        if (ids.isEmpty()) return emptyList()
        return transaction {
            SubscriberTable
                .selectAll()
                .where { SubscriberTable.id inList ids }
                .map { it.toSubscriber() }
        }
    }

    public fun findByAddress(address: TargetAddress): Subscriber? {
        return transaction {
            SubscriberTable
                .selectAll()
                .where {
                    SubscriberTable.targetKey eq address.stableValue()
                }
                .firstOrNull()
                ?.toSubscriber()
        }
    }

    public fun findEffectiveByAddress(address: TargetAddress): Subscriber? {
        return findByAddress(address)
    }

    public fun upsert(
        address: TargetAddress,
        name: String,
        avatar: MediaRef? = null,
        state: SubscriberState? = null,
        createUser: Int = 0,
    ): UpsertResult<Subscriber> {
        val existed = findByAddress(address)
        if (existed != null) {
            val updatedSubscriber = existed.copy(
                address = address,
                name = name,
                avatar = avatar ?: existed.avatar,
                state = state ?: existed.state,
            )
            val changed = updatedSubscriber != existed
            if (changed) replace(updatedSubscriber)
            return UpsertResult(updatedSubscriber, created = false, updated = changed)
        }

        val result = transaction {
            val now = nowInstant()
            val id = SubscriberTable.insertAndGetId {
                it.writeAddress(address)
                it[SubscriberTable.name] = name
                it[SubscriberTable.avatar] = avatar
                it[SubscriberTable.state] = state ?: SubscriberState.ACTIVE
                it[createTime] = now
                it[SubscriberTable.createUser] = createUser
            }.value
            UpsertResult(
                value = Subscriber(
                    id = id,
                    address = address,
                    name = name,
                    avatar = avatar,
                    state = state ?: SubscriberState.ACTIVE,
                    createTime = now.epochSeconds,
                    createUser = createUser,
                ),
                created = true,
                updated = false,
            )
        }
        SubscriberStateCache.update(result.value.address, result.value.state)
        return result
    }

    public fun ensure(address: TargetAddress, name: String = address.externalId): Subscriber {
        val existed = findByAddress(address)
        if (existed != null) {
            val normalizedName = name.targetDisplayName(address)
            val updated = existed.copy(
                name = if (normalizedName != null && existed.name.targetDisplayName(address) == null) {
                    normalizedName
                } else {
                    existed.name
                },
            )
            return if (updated != existed) {
                replace(updated)
                findByAddress(address) ?: updated
            } else {
                existed
            }
        }

        return upsert(
            address = address,
            name = name.trim().takeIf { it.isNotBlank() } ?: address.externalId,
            state = SubscriberState.ACTIVE,
            createUser = 0,
        ).value
    }
}

public val Subscriber.isDeliveryAllowed: Boolean
    get() = state.allowsActiveDelivery

private fun UpdateBuilder<*>.writeAddress(address: TargetAddress) {
    this[SubscriberTable.platformId] = address.platformId.value
    this[SubscriberTable.kind] = address.kind
    this[SubscriberTable.externalId] = address.externalId
    this[SubscriberTable.targetKey] = address.stableValue()
    this[SubscriberTable.scopeId] = address.scopeId
    this[SubscriberTable.threadId] = address.threadId
    this[SubscriberTable.accountId] = address.accountId
}

public fun ResultRow.toSubscriber(): Subscriber = Subscriber(
    id = this[SubscriberTable.id].value,
    address = TargetAddress(
        platformId = top.colter.dynamic.core.data.PlatformId.of(this[SubscriberTable.platformId]),
        kind = this[SubscriberTable.kind],
        externalId = this[SubscriberTable.externalId],
        scopeId = this[SubscriberTable.scopeId],
        threadId = this[SubscriberTable.threadId],
        accountId = this[SubscriberTable.accountId],
    ),
    name = this[SubscriberTable.name],
    avatar = this[SubscriberTable.avatar],
    state = this[SubscriberTable.state],
    createTime = this[SubscriberTable.createTime].epochSeconds,
    createUser = this[SubscriberTable.createUser],
)
