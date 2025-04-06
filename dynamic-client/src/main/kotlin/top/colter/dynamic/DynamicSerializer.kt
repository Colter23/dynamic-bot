package top.colter.dynamic

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.decodeFromJsonElement
import top.colter.dynamic.data.Dynamic


@OptIn(ExperimentalSerializationApi::class)
public val json: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
    allowStructuredMapKeys = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

public inline fun <reified T> String.decode(): T = json.parseToJsonElement(this).decode()

public inline fun <reified T> JsonElement.decode(): T {
    return try {
        json.decodeFromJsonElement(this)
    }catch (e: SerializationException) {
        throw SerializationException("Json解析失败，\n${e.message}")
    }
}

public fun Dynamic.encode(): String = json.encodeToString(this)