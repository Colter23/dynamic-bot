package top.colter.dynamic

import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.core.config.reload

class MainConfigStoreTest {
    @Test
    fun shouldPersistGeneratedAdminToken() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-config"))
        val store = MainConfigStore(configService)

        val created = store.loadOrCreate { "token-1" }
        val reloaded = configService.reload<MainDynamicConfig>(MainDynamicConfig.CONFIG_ID)
        val loadedAgain = MainConfigStore(configService).loadOrCreate { "token-2" }

        assertEquals("token-1", created.webAdmin.token)
        assertEquals("token-1", reloaded.webAdmin.token)
        assertEquals("token-1", loadedAgain.webAdmin.token)
    }

    @Test
    fun drawConfigFormShouldExposeThemeColorsAndAutoTheme() {
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertTrue("draw.themeColors" in paths)
        assertTrue("draw.autoTheme" in paths)
        assertFalse("draw.themeColor" in paths)
        assertFalse("draw.backgroundStartColor" in paths)
        assertFalse("draw.backgroundEndColor" in paths)
    }

    @Test
    fun shouldMigrateLegacyDrawThemeFields() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-config-migration"))
        val path = configService.resolvePath(MainDynamicConfig.CONFIG_ID)
        path.parent.createDirectories()
        configService.save(
            MainDynamicConfig.CONFIG_ID,
            MainDynamicConfig(draw = DrawSettings(themeColors = "#CURRENT")),
        )
        val currentYaml = path.readText()
        path.writeText(
            currentYaml.replace(
                Regex("""themeColors:.*"""),
                "themeColor: \"#111111\"\n  backgroundStartColor: \"#222222\"\n  backgroundEndColor: \"#333333\"",
            ),
        )

        val loaded = MainConfigStore(configService).loadOrCreate { "token" }
        val rewritten = path.readText()

        assertEquals("#111111;#222222;#333333", loaded.draw.themeColors)
        assertTrue(rewritten.contains("themeColors:"))
        assertFalse(rewritten.contains("themeColor:"))
        assertFalse(rewritten.contains("backgroundStartColor"))
        assertFalse(rewritten.contains("backgroundEndColor"))
    }

    @Test
    fun pluginCatalogConfigShouldBeExposedAndRequireHttpsUrl() {
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertTrue("pluginCatalog.url" in paths)
        assertTrue("pluginCatalog.cacheSeconds" in paths)
        assertTrue("pluginCatalog.downloadTimeoutSeconds" in paths)
        assertTrue("pluginCatalog.maxDownloadMegabytes" in paths)

        val error = assertFailsWith<IllegalArgumentException> {
            MainConfigForms.validate(
                MainDynamicConfig(
                    pluginCatalog = PluginCatalogConfig(url = "http://example.com/catalog.json"),
                ),
            )
        }

        assertTrue(error.message!!.contains("https"))
    }

    @Test
    fun imageCacheAndDeliveryRetentionConfigShouldBeExposedAndValidated() {
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertTrue("imageCache.memoryMaxMegabytes" in paths)
        assertTrue("imageCache.memoryMaxEntries" in paths)
        assertTrue("delivery.historyRetentionDays" in paths)
        assertTrue("delivery.cleanupCron" in paths)

        val error = assertFailsWith<IllegalArgumentException> {
            MainConfigForms.validate(
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(memoryMaxMegabytes = -1.0),
                ),
            )
        }

        assertTrue(error.message!!.contains("图片内存缓存"))
    }

    @Test
    fun webAdminConfigShouldExposeLogBufferCapacity() {
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertTrue("webAdmin.logBufferCapacity" in paths)

        val error = assertFailsWith<IllegalArgumentException> {
            MainConfigForms.validate(
                MainDynamicConfig(
                    webAdmin = WebAdminConfig(token = "token", logBufferCapacity = 10),
                ),
            )
        }

        assertTrue(error.message!!.contains("日志缓冲容量"))
    }

    @Test
    fun validateShouldRejectInvalidThemeColorsWithChineseMessage() {
        val error = assertFailsWith<IllegalArgumentException> {
            MainConfigForms.validate(MainDynamicConfig(draw = DrawSettings(themeColors = "#FE65A6;")))
        }

        assertTrue(error.message!!.contains("主题色"))
    }
}
