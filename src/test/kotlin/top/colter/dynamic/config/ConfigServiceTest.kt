package top.colter.dynamic.config

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigException
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigurablePlugin
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigServiceTest {

    data class TestConfig(
        val pollingIntervalMs: Long = 30000,
        val enabled: Boolean = true,
    )

    data class ExpandedTestConfig(
        val pollingIntervalMs: Long = 30000,
        val enabled: Boolean = true,
        val label: String = "默认标签",
    )

    private fun service(): YamlConfigService = YamlConfigService(createTempDirectory("config-service-test"))

    @Test
    fun loadOrCreateShouldCreateConfigFileWhenMissing() {
        val configService = service()
        val pluginId = "test-config-create"
        val path = configService.resolvePath(pluginId)
        path.parent.createDirectories()
        path.deleteIfExists()

        val config = configService.loadOrCreate(pluginId, TestConfig::class) { TestConfig() }

        assertTrue(path.exists())
        assertEquals(30000, config.pollingIntervalMs)
        assertEquals(true, config.enabled)
    }

    @Test
    fun saveAndReloadShouldBeConsistent() {
        val configService = service()
        val pluginId = "test-config-save"
        val path = configService.resolvePath(pluginId)
        path.parent.createDirectories()
        path.deleteIfExists()

        configService.save(pluginId, TestConfig(12345, false))
        val reloaded = configService.reload(pluginId, TestConfig::class)

        assertEquals(12345, reloaded.pollingIntervalMs)
        assertEquals(false, reloaded.enabled)
    }

    @Test
    fun reloadShouldReflectExternalEdit() {
        val configService = service()
        val pluginId = "test-config-reload"
        val path = configService.resolvePath(pluginId)
        path.parent.createDirectories()
        path.writeText("pollingIntervalMs: 7777\nenabled: false\n")

        val reloaded = configService.reload(pluginId, TestConfig::class)

        assertEquals(7777, reloaded.pollingIntervalMs)
        assertEquals(false, reloaded.enabled)
    }

    @Test
    fun loadOrCreateShouldBackfillMissingDefaultFields() {
        val configService = service()
        val pluginId = "test-config-backfill"
        val path = configService.resolvePath(pluginId)
        path.parent.createDirectories()
        path.writeText("pollingIntervalMs: 7777\nenabled: false\n")

        val loaded = configService.loadOrCreate(pluginId, ExpandedTestConfig::class) {
            ExpandedTestConfig()
        }
        val rewritten = path.readText()

        assertEquals(7777, loaded.pollingIntervalMs)
        assertEquals(false, loaded.enabled)
        assertEquals("默认标签", loaded.label)
        assertTrue(rewritten.contains("label:"))
    }

    @Test
    fun migrationsShouldMoveAndRemoveFieldsBeforeDeserialization() {
        val configService = service()
        val pluginId = "test-config-migration"
        val path = configService.resolvePath(pluginId)
        path.parent.createDirectories()
        path.writeText(
            """
            pollingIntervalMs: 8888
            enabled: false
            oldLabel: 保留这个值
            removedLegacyField: true
            """.trimIndent(),
        )

        val loaded = configService.loadOrCreate(
            pluginId,
            ExpandedTestConfig::class,
            listOf(
                ConfigMigration("test-label-migration") {
                    move("oldLabel", "label")
                    remove("removedLegacyField")
                },
            ),
        ) {
            ExpandedTestConfig()
        }
        val rewritten = path.readText()

        assertEquals("保留这个值", loaded.label)
        assertTrue(rewritten.contains("label:"))
        assertTrue(!rewritten.contains("oldLabel"))
        assertTrue(!rewritten.contains("removedLegacyField"))
    }

    @Test
    fun generatedCompleteYamlShouldNotBeRewrittenOnLoad() {
        val configService = service()
        val pluginId = "test-config-stable"
        val path = configService.resolvePath(pluginId)

        configService.save(pluginId, ExpandedTestConfig(123, false, "stable"))
        val before = path.readText()

        val loaded = configService.loadOrCreate(pluginId, ExpandedTestConfig::class) {
            ExpandedTestConfig()
        }
        val after = path.readText()

        assertEquals("stable", loaded.label)
        assertEquals(before, after)
    }

    @Test
    fun invalidPluginIdShouldFail() {
        val configService = service()
        assertFailsWith<IllegalArgumentException> {
            configService.resolvePath("../bad")
        }
    }

    @Test
    fun brokenYamlShouldThrowConfigExceptionWithPath() {
        val configService = service()
        val pluginId = "test-config-broken"
        val path = configService.resolvePath(pluginId)
        path.parent.createDirectories()
        path.writeText("pollingIntervalMs: [\n")

        val ex = assertFailsWith<ConfigException> {
            configService.reload(pluginId, TestConfig::class)
        }

        assertTrue(ex.message?.contains(path.toAbsolutePath().toString()) == true)
    }

    @Test
    fun existsShouldReturnFalseForDifferentPlugin() {
        val configService = service()
        val pluginA = "test-config-a"
        val pluginB = "test-config-b"
        val pathA = configService.resolvePath(pluginA)
        val pathB = configService.resolvePath(pluginB)
        pathA.parent.createDirectories()
        pathA.deleteIfExists()
        pathB.deleteIfExists()

        configService.loadOrCreate(pluginA, TestConfig::class) { TestConfig() }

        assertTrue(configService.exists(pluginA))
        assertTrue(!configService.exists(pluginB))
    }

    @Test
    fun deleteShouldRemoveConfigFile() {
        val configService = service()
        val pluginId = "test-config-delete"
        configService.save(pluginId, TestConfig(123, false))

        assertTrue(configService.delete(pluginId))
        assertTrue(!configService.exists(pluginId))
    }

    @Test
    fun pluginDataStoreShouldUsePluginScopedYamlFiles() {
        val baseDir = createTempDirectory("plugin-data-store-test")
        val dataStore = YamlPluginDataStore("test-plugin", baseDir)

        val state = dataStore.loadOrCreate("state", TestConfig::class) { TestConfig(999, false) }
        dataStore.save("state", state.copy(enabled = true))
        val reloaded = dataStore.reload("state", TestConfig::class)

        assertEquals(baseDir.resolve("test-plugin"), dataStore.dataDir)
        assertEquals(baseDir.resolve("test-plugin").resolve("state.yml"), dataStore.resolvePath("state"))
        assertEquals(999, reloaded.pollingIntervalMs)
        assertEquals(true, reloaded.enabled)
    }

    @Test
    fun pluginDataStoreShouldRejectUnsafeNames() {
        val dataStore = YamlPluginDataStore("test-plugin", createTempDirectory("plugin-data-unsafe-test"))
        assertFailsWith<IllegalArgumentException> {
            dataStore.resolvePath("../state")
        }
        assertFailsWith<IllegalArgumentException> {
            YamlPluginDataStore("../plugin", createTempDirectory("plugin-data-bad-plugin-test"))
        }
        assertFailsWith<IllegalArgumentException> {
            YamlPluginDataStore("..", createTempDirectory("plugin-data-parent-segment-test"))
        }
        assertFailsWith<IllegalArgumentException> {
            YamlPluginDataStore(".", createTempDirectory("plugin-data-current-segment-test"))
        }
    }

    @Test
    fun reloadShouldRejectUnknownFields() {
        val configService = service()
        val pluginId = "test-config-unknown"
        val path = configService.resolvePath(pluginId)
        path.parent.createDirectories()
        path.writeText("pollingIntervalMs: 7777\nenabled: false\nunknown: true\n")

        assertFailsWith<ConfigException> {
            configService.reload(pluginId, TestConfig::class)
        }
    }

    @Test
    fun configFormSpecShouldKeepFieldMetadata() {
        val spec = ConfigFormSpec(
            title = "Test",
            fields = listOf(
                ConfigFieldSpec(
                    path = "token",
                    label = "Token",
                    type = ConfigFieldType.SECRET,
                    description = "测试令牌说明",
                    secret = true,
                    restartRequired = true,
                    restartTarget = "test-plugin",
                )
            ),
        )

        assertEquals("token", spec.fields.single().path)
        assertEquals("测试令牌说明", spec.fields.single().description)
        assertEquals(true, spec.fields.single().secret)
        assertEquals(true, spec.fields.single().restartRequired)
        assertEquals("test-plugin", spec.fields.single().restartTarget)

        val jsonSpec = ConfigFieldSpec(
            path = "command.permissions",
            label = "权限规则",
            type = ConfigFieldType.JSON,
        )
        assertEquals(ConfigFieldType.JSON, jsonSpec.type)
    }

    @Test
    fun configurablePluginShouldExposeDefaultMetadataAndApplyResult() {
        val plugin = object : ConfigurablePlugin<TestConfig> {
            private var current = TestConfig()

            override val configId: String = "test-plugin"
            override val configClass = TestConfig::class
            override val configFormSpec = ConfigFormSpec("Test", fields = emptyList())

            override fun currentConfig(): TestConfig = current

            override fun applyConfig(next: TestConfig): ConfigApplyResult {
                val changed = next != current
                current = next
                return ConfigApplyResult(changed = changed)
            }
        }

        assertEquals("test-plugin", plugin.configName)
        assertEquals("", plugin.configDescription)
        assertEquals(true, plugin.applyConfig(TestConfig(enabled = false)).changed)
        assertEquals(false, plugin.applyConfig(TestConfig(enabled = false)).changed)
    }
}
