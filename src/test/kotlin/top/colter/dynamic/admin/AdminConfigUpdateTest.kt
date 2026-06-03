package top.colter.dynamic.admin

import kotlin.io.path.createTempDirectory
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.reload
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.plugin.PluginCapability
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginState

class AdminConfigUpdateTest {
    @Test
    fun pluginConfigUpdateShouldRollbackFileAndRuntimeWhenApplyFails() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-config-rollback"))
        val plugin = FailingConfigPlugin()
        configService.save(plugin.configId, plugin.currentConfig())
        val service = AdminService(
            pluginProvider = { emptyList() },
            configurablePluginProvider = { listOf(PluginHandle(pluginInfo(), plugin)) },
            publisherLookupResolver = { null },
            publisherFollowResolver = { null },
            configProvider = { MainDynamicConfig() },
            configService = configService,
        )

        plugin.failNextApply = true

        assertFailsWith<IllegalStateException> {
            service.updateConfig(
                plugin.configId,
                UpdateConfigRequest(JsonObject(mapOf("name" to JsonPrimitive("new")))),
            )
        }

        assertEquals(TestPluginConfig("old"), plugin.currentConfig())
        assertEquals(TestPluginConfig("old"), configService.reload(plugin.configId))
    }

    private fun pluginInfo(): PluginInfo = PluginInfo(
        descriptor = PluginDescriptor("config-plugin", "Config Plugin", "1.0.0", "ConfigPlugin"),
        capabilities = setOf(PluginCapability.CONFIGURABLE),
        state = PluginState.ACTIVE,
        sourceJarPath = "plugins/config-plugin.jar",
    )

    data class TestPluginConfig(
        val name: String = "old",
    )

    private class FailingConfigPlugin : ConfigurablePlugin<TestPluginConfig> {
        override val configId: String = "test-plugin"
        override val configName: String = "测试插件"
        override val configClass: KClass<TestPluginConfig> = TestPluginConfig::class
        override val configFormSpec: ConfigFormSpec = ConfigFormSpec(
            title = "测试插件",
            fields = listOf(
                ConfigFieldSpec(
                    path = "name",
                    label = "名称",
                    type = ConfigFieldType.TEXT,
                ),
            ),
        )

        var failNextApply: Boolean = false
        private var config: TestPluginConfig = TestPluginConfig()

        override fun currentConfig(): TestPluginConfig = config

        override fun applyConfig(next: TestPluginConfig): ConfigApplyResult {
            if (failNextApply) {
                failNextApply = false
                throw IllegalStateException("apply failed")
            }
            config = next
            return ConfigApplyResult(changed = true, message = "ok")
        }
    }
}
