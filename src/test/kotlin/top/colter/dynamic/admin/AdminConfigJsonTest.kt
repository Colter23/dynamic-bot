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
    fun decodeShouldTreatBlankSecretAsExplicitClear() {
        val decoded = AdminConfigJson.decode(
            values = JsonObject(mapOf("token" to JsonPrimitive(""))),
            current = SecretConfig(token = "old"),
            spec = secretSpec(),
            clazz = SecretConfig::class,
        )

        assertEquals("", decoded.token)
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

    private data class SecretConfig(
        val token: String = "",
    )
}
