package top.colter.dynamic.admin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
            val value = if (field.secret && isBlankSecret(incoming)) {
                jsonElementAt(currentNode, field.path) ?: JsonNull
            } else {
                incoming ?: jsonElementAt(currentNode, field.path) ?: JsonNull
            }
            setPath(root, field.path, toJsonNode(value))
        }

        return mapper.treeToValue(root, clazz.java)
    }

    fun validateValues(values: JsonObject, spec: ConfigFormSpec) {
        spec.fields.forEach { field ->
            val value = values[field.path] ?: return@forEach
            if (field.secret && isBlankSecret(value)) return@forEach
            if (field.required) {
                require(!isBlankValue(value)) { "${field.path} must not be blank" }
            }
            when (field.type) {
                ConfigFieldType.NUMBER -> validateNumber(field, value)
                ConfigFieldType.SELECT -> validateSelect(field, value)
                ConfigFieldType.BOOLEAN -> validateBoolean(field, value)
                ConfigFieldType.COMMAND_PERMISSIONS -> require(value is JsonArray) {
                    "${field.path} must be an array"
                }
                ConfigFieldType.TEXT,
                ConfigFieldType.TEXTAREA,
                ConfigFieldType.SECRET -> Unit
            }
        }
    }

    private fun validateNumber(field: ConfigFieldSpec, value: JsonElement) {
        val number = jsonText(value)?.toLongOrNull()
            ?: throw IllegalArgumentException("${field.path} must be a number")
        field.min?.let { min ->
            require(number >= min) { "${field.path} must be at least $min" }
        }
        field.max?.let { max ->
            require(number <= max) { "${field.path} must be at most $max" }
        }
    }

    private fun validateSelect(field: ConfigFieldSpec, value: JsonElement) {
        val text = jsonText(value) ?: ""
        if (text.isBlank() && !field.required) return
        val allowed = field.options.map { it.value }.toSet()
        require(text in allowed) { "${field.path} must be one of ${allowed.joinToString("|")}" }
    }

    private fun validateBoolean(field: ConfigFieldSpec, value: JsonElement) {
        if (value is JsonPrimitive && value.booleanOrNull != null) return
        val text = jsonText(value)?.lowercase()
        require(text == "true" || text == "false") { "${field.path} must be true or false" }
    }

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
