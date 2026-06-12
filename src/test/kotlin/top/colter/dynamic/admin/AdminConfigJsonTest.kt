package top.colter.dynamic.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigNumberKind

class AdminConfigJsonTest {
    @Test
    fun valuesForShouldExposeSecretsWhenMaskDisabled() {
        val spec = secretSpec()

        val masked = AdminConfigJson.valuesFor(SecretConfig(token = "secret"), spec)
        val exposed = AdminConfigJson.valuesFor(SecretConfig(token = "secret"), spec, maskSecrets = false)

        assertEquals(JsonPrimitive(""), masked["token"])
        assertEquals(JsonPrimitive("secret"), exposed["token"])
    }

    @Test
    fun decodeShouldPreserveExistingSecretWhenIncomingSecretIsBlank() {
        val decoded = AdminConfigJson.decode(
            values = JsonObject(mapOf("token" to JsonPrimitive(""))),
            current = SecretConfig(token = "old"),
            spec = secretSpec(),
            clazz = SecretConfig::class,
        )

        assertEquals("old", decoded.token)
    }

    @Test
    fun decodeShouldApplyNonBlankSecret() {
        val decoded = AdminConfigJson.decode(
            values = JsonObject(mapOf("token" to JsonPrimitive("new"))),
            current = SecretConfig(token = "old"),
            spec = secretSpec(),
            clazz = SecretConfig::class,
        )

        assertEquals("new", decoded.token)
    }

    @Test
    fun decodeShouldPreserveExistingReadOnlyField() {
        val decoded = AdminConfigJson.decode(
            values = JsonObject(
                mapOf(
                    "name" to JsonPrimitive("new"),
                    "editable" to JsonPrimitive("new"),
                )
            ),
            current = ReadOnlyConfig(name = "old", editable = "old"),
            spec = readOnlySpec(),
            clazz = ReadOnlyConfig::class,
        )

        assertEquals("old", decoded.name)
        assertEquals("new", decoded.editable)
    }

    @Test
    fun decodeShouldPreserveFieldsOutsideFormSpec() {
        val decoded = AdminConfigJson.decode(
            values = JsonObject(mapOf("visible" to JsonPrimitive("new"))),
            current = PartialConfig(visible = "old", hidden = "keep"),
            spec = partialSpec(),
            clazz = PartialConfig::class,
        )

        assertEquals("new", decoded.visible)
        assertEquals("keep", decoded.hidden)
    }

    @Test
    fun validateValuesShouldIgnoreReadOnlyField() {
        AdminConfigJson.validateValues(
            values = JsonObject(mapOf("name" to JsonPrimitive(""))),
            spec = readOnlySpec(),
        )
    }

    @Test
    fun integerNumberFieldsShouldRejectDecimalsWithChineseLabel() {
        val spec = ConfigFormSpec(
            title = "测试配置",
            fields = listOf(
                ConfigFieldSpec(
                    path = "port",
                    label = "后台端口",
                    type = ConfigFieldType.NUMBER,
                    numberKind = ConfigNumberKind.INTEGER,
                    min = 1,
                    max = 65_535,
                ),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            AdminConfigJson.validateValues(
                values = JsonObject(mapOf("port" to JsonPrimitive(8080.5))),
                spec = spec,
            )
        }

        assertTrue(error.message!!.contains("后台端口"))
        assertTrue(error.message!!.contains("整数"))
    }

    @Test
    fun decimalNumberFieldsShouldKeepAcceptingDecimals() {
        val spec = ConfigFormSpec(
            title = "测试配置",
            fields = listOf(
                ConfigFieldSpec(
                    path = "timeoutSeconds",
                    label = "超时时间",
                    type = ConfigFieldType.NUMBER,
                    numberKind = ConfigNumberKind.DECIMAL,
                    min = 0,
                ),
            ),
        )

        AdminConfigJson.validateValues(
            values = JsonObject(mapOf("timeoutSeconds" to JsonPrimitive(0.5))),
            spec = spec,
        )
    }

    private fun secretSpec(): ConfigFormSpec = ConfigFormSpec(
        title = "测试配置",
        fields = listOf(
            ConfigFieldSpec(
                path = "token",
                label = "Token",
                type = ConfigFieldType.SECRET,
                secret = true,
            ),
        ),
    )

    private fun readOnlySpec(): ConfigFormSpec = ConfigFormSpec(
        title = "测试配置",
        fields = listOf(
            ConfigFieldSpec(
                path = "name",
                label = "只读名称",
                type = ConfigFieldType.TEXT,
                required = true,
                readOnly = true,
            ),
            ConfigFieldSpec(
                path = "editable",
                label = "可编辑名称",
                type = ConfigFieldType.TEXT,
            ),
        ),
    )

    private fun partialSpec(): ConfigFormSpec = ConfigFormSpec(
        title = "测试配置",
        fields = listOf(
            ConfigFieldSpec(
                path = "visible",
                label = "可见配置",
                type = ConfigFieldType.TEXT,
            ),
        ),
    )

    private data class SecretConfig(
        val token: String = "",
    )

    private data class ReadOnlyConfig(
        val name: String = "",
        val editable: String = "",
    )

    private data class PartialConfig(
        val visible: String = "",
        val hidden: String = "",
    )
}
