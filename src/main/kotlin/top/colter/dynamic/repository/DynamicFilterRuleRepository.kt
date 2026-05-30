package top.colter.dynamic.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import top.colter.dynamic.core.data.DynamicFilterRule
import top.colter.dynamic.core.data.FilterAction
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.table.DynamicFilterRuleTable
import top.colter.dynamic.table.SubscriptionTable
import top.colter.dynamic.core.tools.nowInstant

public object DynamicFilterRuleRepository {
    public fun addRule(
        subscriptionId: Int,
        action: FilterAction,
        condition: FilterCondition,
        priority: Int = 0,
    ): UpsertResult<DynamicFilterRule> {
        require(SubscriptionRepository.findById(subscriptionId) != null) {
            "订阅不存在：subscriptionId=$subscriptionId"
        }
        validateCondition(condition)

        return transaction {
            val now = nowInstant()
            val id = DynamicFilterRuleTable.insertAndGetId {
                it[DynamicFilterRuleTable.subscriptionId] = EntityID(subscriptionId, SubscriptionTable)
                it[DynamicFilterRuleTable.action] = action
                it[DynamicFilterRuleTable.condition] = condition
                it[DynamicFilterRuleTable.priority] = priority
                it[enabled] = true
                it[createdAt] = now
            }.value
            UpsertResult(
                value = DynamicFilterRule(
                    id = id,
                    subscriptionId = subscriptionId,
                    action = action,
                    condition = condition,
                    priority = priority,
                    enabled = true,
                    createdAtEpochSeconds = now.epochSeconds,
                ),
                created = true,
                updated = false,
            )
        }
    }

    public fun findBySubscriptionId(subscriptionId: Int): List<DynamicFilterRule> {
        return transaction {
            DynamicFilterRuleTable
                .selectAll()
                .where { DynamicFilterRuleTable.subscriptionId eq EntityID(subscriptionId, SubscriptionTable) }
                .map { it.toDynamicFilterRule() }
        }
    }

    public fun findBySubscriptionIds(subscriptionIds: Collection<Int>): Map<Int, List<DynamicFilterRule>> {
        if (subscriptionIds.isEmpty()) return emptyMap()
        val ids = subscriptionIds.distinct()
        return transaction {
            DynamicFilterRuleTable
                .selectAll()
                .where { DynamicFilterRuleTable.subscriptionId inList ids.map { EntityID(it, SubscriptionTable) } }
                .map { it.toDynamicFilterRule() }
                .groupBy { it.subscriptionId }
        }
    }

    public fun findById(id: Int): DynamicFilterRule? {
        return transaction {
            DynamicFilterRuleTable
                .selectAll()
                .where { DynamicFilterRuleTable.id eq id }
                .firstOrNull()
                ?.toDynamicFilterRule()
        }
    }

    public fun removeById(id: Int): Boolean {
        return transaction {
            DynamicFilterRuleTable.deleteWhere { DynamicFilterRuleTable.id eq id } > 0
        }
    }

    public fun clearBySubscriptionId(subscriptionId: Int): Int {
        return transaction {
            DynamicFilterRuleTable.deleteWhere {
                DynamicFilterRuleTable.subscriptionId eq EntityID(subscriptionId, SubscriptionTable)
            }
        }
    }

    public fun findAll(): List<DynamicFilterRule> {
        return transaction {
            DynamicFilterRuleTable.selectAll().map { it.toDynamicFilterRule() }
        }
    }

    private fun validateCondition(condition: FilterCondition) {
        when (condition) {
            is FilterCondition.TextContains -> require(condition.value.isNotBlank()) {
                "text condition value must not be blank"
            }
            is FilterCondition.TextRegex -> Regex(condition.pattern)
            is FilterCondition.AnyOf -> condition.conditions.forEach(::validateCondition)
            is FilterCondition.AllOf -> condition.conditions.forEach(::validateCondition)
            is FilterCondition.Not -> validateCondition(condition.condition)
            is FilterCondition.HasAttachmentKind,
            is FilterCondition.HasReference -> Unit
        }
    }
}

public fun ResultRow.toDynamicFilterRule(): DynamicFilterRule = DynamicFilterRule(
    id = this[DynamicFilterRuleTable.id].value,
    subscriptionId = this[DynamicFilterRuleTable.subscriptionId].value,
    action = this[DynamicFilterRuleTable.action],
    condition = this[DynamicFilterRuleTable.condition],
    priority = this[DynamicFilterRuleTable.priority],
    enabled = this[DynamicFilterRuleTable.enabled],
    createdAtEpochSeconds = this[DynamicFilterRuleTable.createdAt].epochSeconds,
)
