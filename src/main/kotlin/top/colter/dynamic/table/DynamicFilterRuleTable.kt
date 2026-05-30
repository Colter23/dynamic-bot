package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.tools.nowInstant

public object DynamicFilterRuleTable : IntIdTable("dynamic_filter_rule") {
    public val subscriptionId: Column<EntityID<Int>> = reference("subscription_id", SubscriptionTable)
    public val condition: Column<FilterCondition> = registerColumn("condition_json", filterConditionColumn())
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }

    init {
        index(isUnique = false, subscriptionId)
    }
}
