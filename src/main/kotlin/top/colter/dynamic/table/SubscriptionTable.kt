package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.tools.nowInstant

public object SubscriptionTable : IntIdTable("subscription") {
    public val subscriberId: Column<EntityID<Int>> = reference("subscriber_id", SubscriberTable)
    public val publisherId: Column<EntityID<Int>> = reference("publisher_id", PublisherTable)
    public val state: Column<EntityState> = enumerationByName<EntityState>("state", 20).default(EntityState.ACTIVE)
    public val policy: Column<SubscriptionPolicy> = registerColumn("policy_json", subscriptionPolicyColumn())
    public val createdAt: Column<Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }
    public val createdBy: Column<Int> = integer(name = "created_by").default(0)
    public val updatedAt: Column<Instant> = timestamp(name = "updated_at").clientDefault { nowInstant() }

    init {
        uniqueIndex(subscriberId, publisherId)
        index(isUnique = false, publisherId)
        index(isUnique = false, subscriberId)
        index(isUnique = false, state)
    }
}
