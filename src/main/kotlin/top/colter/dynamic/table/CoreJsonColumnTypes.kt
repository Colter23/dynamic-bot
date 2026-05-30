package top.colter.dynamic.table

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.core.ColumnType
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.data.coreJson

public val coreTableJson = coreJson

public class JsonColumnType<T : Any>(
    private val serializer: KSerializer<T>,
) : ColumnType<T>() {
    override fun sqlType(): String = "TEXT"

    override fun valueFromDB(value: Any): T {
        @Suppress("UNCHECKED_CAST")
        if (value !is String) return value as T
        return coreTableJson.decodeFromString(serializer, value)
    }

    override fun valueToDB(value: T?): Any? {
        return value?.let { coreTableJson.encodeToString(serializer, it) }
    }
}

public fun mediaRefColumn(): JsonColumnType<MediaRef> = JsonColumnType(MediaRef.serializer())

public fun subscriptionPolicyColumn(): JsonColumnType<SubscriptionPolicy> =
    JsonColumnType(SubscriptionPolicy.serializer())

public fun filterConditionColumn(): JsonColumnType<FilterCondition> =
    JsonColumnType(FilterCondition.serializer())

public fun updateKeyColumn(): JsonColumnType<UpdateKey> = JsonColumnType(UpdateKey.serializer())

public fun messageColumn(): JsonColumnType<Message> = JsonColumnType(Message.serializer())
