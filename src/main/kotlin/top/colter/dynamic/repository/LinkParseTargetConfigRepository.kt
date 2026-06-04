package top.colter.dynamic.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.tools.nowInstant
import top.colter.dynamic.link.LinkParseTargetConfig
import top.colter.dynamic.table.LinkParseTargetConfigTable

public object LinkParseTargetConfigRepository {
    public fun findByAddress(address: TargetAddress): LinkParseTargetConfig? {
        return transaction {
            LinkParseTargetConfigTable
                .selectAll()
                .where { LinkParseTargetConfigTable.targetKey eq address.stableValue() }
                .firstOrNull()
                ?.toLinkParseTargetConfig()
        }
    }

    public fun findEffectiveByAddress(address: TargetAddress): LinkParseTargetConfig? {
        return findByAddress(address)
            ?: address
                .takeIf { it.accountId != null }
                ?.copy(accountId = null)
                ?.let { findByAddress(it) }
    }

    public fun findAll(): List<LinkParseTargetConfig> {
        return transaction {
            LinkParseTargetConfigTable.selectAll().map { it.toLinkParseTargetConfig() }
        }
    }

    public fun upsert(
        address: TargetAddress,
        triggerMode: LinkParseTriggerMode,
        updatedBy: String? = null,
    ): UpsertResult<LinkParseTargetConfig> {
        val existed = findByAddress(address)
        val now = nowInstant()
        if (existed != null) {
            val next = existed.copy(
                address = address,
                triggerMode = triggerMode,
                updatedAtEpochSeconds = now.epochSeconds,
                updatedBy = updatedBy,
            )
            val changed = next != existed
            if (changed) {
                transaction {
                    LinkParseTargetConfigTable.update({
                        LinkParseTargetConfigTable.id eq existed.id
                    }) {
                        it.writeAddress(address)
                        it[LinkParseTargetConfigTable.triggerMode] = triggerMode
                        it[updatedAt] = now
                        it[LinkParseTargetConfigTable.updatedBy] = updatedBy
                    }
                }
            }
            return UpsertResult(next, created = false, updated = changed)
        }

        return transaction {
            val id = LinkParseTargetConfigTable.insertAndGetId {
                it.writeAddress(address)
                it[LinkParseTargetConfigTable.triggerMode] = triggerMode
                it[createdAt] = now
                it[updatedAt] = now
                it[LinkParseTargetConfigTable.updatedBy] = updatedBy
            }.value
            UpsertResult(
                value = LinkParseTargetConfig(
                    id = id,
                    address = address,
                    triggerMode = triggerMode,
                    createdAtEpochSeconds = now.epochSeconds,
                    updatedAtEpochSeconds = now.epochSeconds,
                    updatedBy = updatedBy,
                ),
                created = true,
                updated = false,
            )
        }
    }

    public fun deleteByAddress(address: TargetAddress): Boolean {
        return transaction {
            LinkParseTargetConfigTable.deleteWhere {
                LinkParseTargetConfigTable.targetKey eq address.stableValue()
            } > 0
        }
    }
}

private fun UpdateBuilder<*>.writeAddress(address: TargetAddress) {
    this[LinkParseTargetConfigTable.platformId] = address.platformId.value
    this[LinkParseTargetConfigTable.kind] = address.kind
    this[LinkParseTargetConfigTable.externalId] = address.externalId
    this[LinkParseTargetConfigTable.targetKey] = address.stableValue()
    this[LinkParseTargetConfigTable.scopeId] = address.scopeId
    this[LinkParseTargetConfigTable.threadId] = address.threadId
    this[LinkParseTargetConfigTable.accountId] = address.accountId
}

public fun ResultRow.toLinkParseTargetConfig(): LinkParseTargetConfig = LinkParseTargetConfig(
    id = this[LinkParseTargetConfigTable.id].value,
    address = TargetAddress(
        platformId = PlatformId.of(this[LinkParseTargetConfigTable.platformId]),
        kind = this[LinkParseTargetConfigTable.kind],
        externalId = this[LinkParseTargetConfigTable.externalId],
        scopeId = this[LinkParseTargetConfigTable.scopeId],
        threadId = this[LinkParseTargetConfigTable.threadId],
        accountId = this[LinkParseTargetConfigTable.accountId],
    ),
    triggerMode = this[LinkParseTargetConfigTable.triggerMode],
    createdAtEpochSeconds = this[LinkParseTargetConfigTable.createdAt].epochSeconds,
    updatedAtEpochSeconds = this[LinkParseTargetConfigTable.updatedAt].epochSeconds,
    updatedBy = this[LinkParseTargetConfigTable.updatedBy],
)
