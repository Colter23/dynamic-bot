package top.colter.dynamic.admin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigNumberKind
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import kotlin.reflect.KClass

internal object AdminConfigJson {
    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val json: Json = Json { encodeDefaults = true }

    fun valuesFor(config: Any, spec: ConfigFormSpec, maskSecrets: Boolean = true): JsonObject {
        val node = mapper.valueToTree<JsonNode>(config)
        return JsonObject(
            spec.fields.associate { field ->
                val value = if (field.secret && maskSecrets) {
                    JsonPrimitive("")
                } else {
                    jsonElementAt(node, field.path) ?: JsonNull
                }
                field.path to value
            }
        )
    }

    fun secretStates(config: Any, spec: ConfigFormSpec): Map<String, Boolean> {
        val node = mapper.valueToTree<JsonNode>(config)
        return spec.fields
            .filter { it.secret }
            .associate { field ->
                val value = jsonElementAt(node, field.path)
                field.path to !jsonText(value).isNullOrBlank()
            }
    }

    fun <T : Any> decode(
        values: JsonObject,
        current: T,
        spec: ConfigFormSpec,
        clazz: KClass<T>,
    ): T {
        validateValues(values, spec)
        val currentNode = mapper.valueToTree<JsonNode>(current)
        val root = JsonNodeFactory.instance.objectNode()

        spec.fields.forEach { field ->
            val incoming = values[field.path]
            val value = incoming ?: jsonElementAt(currentNode, field.path) ?: JsonNull
            setPath(root, field.path, toJsonNode(value))
        }

        return mapper.treeToValue(root, clazz.java)
    }

    fun validateValues(values: JsonObject, spec: ConfigFormSpec) {
        spec.fields.forEach { field ->
            val value = values[field.path] ?: return@forEach
            if (field.secret && isBlankSecret(value)) return@forEach
            if (field.required) {
                require(!isBlankValue(value)) { "${field.displayName()}不能为空" }
            }
            when (field.type) {
                ConfigFieldType.NUMBER -> validateNumber(field, value)
                ConfigFieldType.SELECT -> validateSelect(field, value)
                ConfigFieldType.BOOLEAN -> validateBoolean(field, value)
                ConfigFieldType.JSON -> Unit
                ConfigFieldType.TEXT,
                ConfigFieldType.TEXTAREA,
                ConfigFieldType.SECRET -> Unit
            }
        }
    }

    private fun validateNumber(field: ConfigFieldSpec, value: JsonElement) {
        val text = jsonText(value)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("${field.displayName()}必须是数字")
        val number = try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("${field.displayName()}必须是数字")
        }
        if (field.numberKind == ConfigNumberKind.INTEGER) {
            require(number.stripTrailingZeros().scale() <= 0) {
                "${field.displayName()}必须是整数"
            }
        }
        field.min?.let { min ->
            require(number >= BigDecimal.valueOf(min)) { "${field.displayName()}不能小于 $min" }
        }
        field.max?.let { max ->
            require(number <= BigDecimal.valueOf(max)) { "${field.displayName()}不能大于 $max" }
        }
    }

    private fun validateSelect(field: ConfigFieldSpec, value: JsonElement) {
        val text = jsonText(value) ?: ""
        if (text.isBlank() && !field.required) return
        val allowed = field.options.map { it.value }.toSet()
        require(text in allowed) { "${field.displayName()}必须是 ${allowed.joinToString("|")} 之一" }
    }

    private fun validateBoolean(field: ConfigFieldSpec, value: JsonElement) {
        if (value is JsonPrimitive && value.booleanOrNull != null) return
        val text = jsonText(value)?.lowercase()
        require(text == "true" || text == "false") { "${field.displayName()}必须是 true 或 false" }
    }

    private fun ConfigFieldSpec.displayName(): String = label.takeIf { it.isNotBlank() } ?: path

    private fun jsonElementAt(node: JsonNode, path: String): JsonElement? {
        var current: JsonNode = node
        path.split(".").forEach { segment ->
            current = current.get(segment) ?: return null
        }
        if (current.isMissingNode) return null
        return json.parseToJsonElement(mapper.writeValueAsString(current))
    }

    private fun setPath(root: ObjectNode, path: String, value: JsonNode) {
        val segments = path.split(".")
        var current = root
        segments.dropLast(1).forEach { segment ->
            val child = current.get(segment) as? ObjectNode
                ?: JsonNodeFactory.instance.objectNode().also { current.set<ObjectNode>(segment, it) }
            current = child
        }
        current.set<JsonNode>(segments.last(), value)
    }

    private fun toJsonNode(value: JsonElement): JsonNode {
        return mapper.readTree(value.toString())
    }

    private fun isBlankSecret(value: JsonElement?): Boolean {
        return value == null || value is JsonNull || jsonText(value)?.isBlank() == true
    }

    private fun isBlankValue(value: JsonElement): Boolean {
        return value is JsonNull || jsonText(value)?.isBlank() == true
    }

    private fun jsonText(value: JsonElement?): String? {
        if (value == null || value is JsonNull) return null
        return if (value is JsonPrimitive && value.isString) {
            value.contentOrNull
        } else if (value is JsonPrimitive) {
            value.content
        } else {
            null
        }
    }
}
