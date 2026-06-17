package top.colter.dynamic

import kotlin.io.path.createTempDirectory
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
    fun shouldUseInjectedDefaultConfigOnlyWhenCreatingMainConfig() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-config"))
        val store = MainConfigStore(configService)

        val created = store.loadOrCreate(
            adminTokenProvider = { "token" },
            secretProvider = { "secret" },
            defaultConfigProvider = {
                MainDynamicConfig(webAdmin = WebAdminConfig(host = "0.0.0.0"))
            },
        )
        val loadedAgain = MainConfigStore(configService).loadOrCreate(
            adminTokenProvider = { "new-token" },
            secretProvider = { "new-secret" },
            defaultConfigProvider = {
                MainDynamicConfig(webAdmin = WebAdminConfig(host = "127.0.0.1"))
            },
        )

        assertEquals("0.0.0.0", created.webAdmin.host)
        assertEquals("0.0.0.0", loadedAgain.webAdmin.host)
        assertEquals("token", loadedAgain.webAdmin.token)
    }

    @Test
    fun defaultConfigAndFormShouldExposeImportantFields() {
        val config = MainDynamicConfig()
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertEquals(DrawOutputFormat.PNG, config.draw.outputFormat)
        assertEquals(1.0, config.draw.scale)
        assertEquals("{draw}", config.linkParsing.templates.message)
        assertEquals(0.0, config.linkParsing.videoDownload.maxFileMegabytes)

        assertContainsAll(
            paths,
            listOf(
                "draw.outputFormat",
                "draw.scale",
                "draw.themeColors",
                "draw.autoTheme",
                "draw.font.typography.autoNormalize",
                "draw.font.typography.lineHeightScale",
                "draw.font.typography.letterSpacingEm",
                "linkParsing.templates.message",
                "linkParsing.videoDownload.prompts.downloading",
                "linkParsing.videoDownload.prompts.failed",
                "mediaDelivery.defaultProfileId",
                "mediaDelivery.profiles",
                "messageRouting.defaultPolicy.strategy",
                "messageRouting.defaultPolicy.primaryAccountId",
                "network.proxy.enabled",
                "network.proxy.type",
                "network.proxy.host",
                "network.proxy.port",
            ),
        )
        assertContainsNone(
            paths,
            listOf(
                "draw.themeColor",
                "draw.backgroundStartColor",
                "draw.backgroundEndColor",
                "draw.width",
                "linkParsing.replyOnFailure",
                "linkParsing.progressReply.enabled",
                "linkParsing.videoDownload.enabled",
                "linkParsing.templates.video",
                "linkParsing.templates.live",
                "linkParsing.templates.user",
                "linkParsing.templates.fallback",
                "linkParsing.templates.videoFile",
                "linkParsing.videoDownload.prompts.durationUnknown",
                "linkParsing.videoDownload.prompts.durationTooLong",
                "linkParsing.videoDownload.prompts.noDownloader",
                "linkParsing.videoDownload.prompts.timeout",
                "linkParsing.videoDownload.prompts.fileTooLarge",
                "outboundMedia.enabled",
            ),
        )
    }

    @Test
    fun legacyDrawWidthShouldMigrateToScale() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-config"))
        val path = configService.resolvePath(MainDynamicConfig.CONFIG_ID)
        path.parent.toFile().mkdirs()
        path.writeText(
            """
            draw:
              width: 1500
            webAdmin:
              token: token
            """.trimIndent(),
        )

        val loaded = MainConfigStore(configService).loadOrCreate { "new-token" }
        val rewritten = path.readText()
        val rewrittenAgain = MainConfigStore(configService).loadOrCreate { "another-token" }
        val rewrittenAfterSecondLoad = path.readText()

        assertEquals(1.5, loaded.draw.scale)
        assertEquals(1.5, rewrittenAgain.draw.scale)
        assertTrue(rewritten.contains("scale: 1.5"))
        assertFalse(rewritten.contains("width:"))
        assertEquals(rewritten, rewrittenAfterSecondLoad)
    }

    @Test
    fun drawScaleShouldRejectTooLargeValues() {
        val error = assertFailsWith<IllegalArgumentException> {
            MainConfigForms.validate(
                MainDynamicConfig(draw = DrawSettings(scale = 1e40)),
            )
        }

        assertTrue(error.message!!.contains("绘图倍率不能大于"))
    }

    @Test
    fun pluginCatalogConfigShouldExposeHttpsUrlAndKeepInternalLimitsHidden() {
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertContainsAll(paths, listOf("pluginCatalog.url", "pluginCatalog.downloadTimeoutSeconds"))
        assertContainsNone(paths, listOf("pluginCatalog.cacheSeconds", "pluginCatalog.maxDownloadMegabytes"))

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
    fun networkProxyConfigShouldStayLightweightAndValidateOnlyWhenEnabled() {
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertContainsAll(
            paths,
            listOf(
                "network.proxy.enabled",
                "network.proxy.type",
                "network.proxy.host",
                "network.proxy.port",
            ),
        )

        MainConfigForms.validate(
            MainDynamicConfig(
                webAdmin = WebAdminConfig(token = "token"),
                network = NetworkConfig(
                    proxy = NetworkProxyConfig(enabled = false, host = "", port = 7890),
                ),
            ),
        )

        val missingHost = assertFailsWith<IllegalArgumentException> {
            MainConfigForms.validate(
                MainDynamicConfig(
                    webAdmin = WebAdminConfig(token = "token"),
                    network = NetworkConfig(
                        proxy = NetworkProxyConfig(enabled = true, host = "", port = 7890),
                    ),
                ),
            )
        }
        assertTrue(missingHost.message!!.contains("代理主机"))

        val invalidPort = assertFailsWith<IllegalArgumentException> {
            MainConfigForms.validate(
                MainDynamicConfig(
                    webAdmin = WebAdminConfig(token = "token"),
                    network = NetworkConfig(
                        proxy = NetworkProxyConfig(enabled = true, host = "127.0.0.1", port = 0),
                    ),
                ),
            )
        }
        assertTrue(invalidPort.message!!.contains("代理端口"))
    }

    @Test
    fun hiddenImageCacheAndDeliveryConfigShouldStillValidate() {
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertContainsAll(paths, listOf("delivery.historyRetentionDays", "delivery.cleanupCron"))
        assertContainsNone(paths, listOf("imageCache.memoryMaxMegabytes", "imageCache.memoryMaxEntries"))

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
    fun autoMediaDeliveryProfileShouldIgnoreLocalFileMappings() {
        MainConfigForms.validate(
            MainDynamicConfig(
                webAdmin = WebAdminConfig(token = "token"),
                mediaDelivery = MediaDeliveryConfig(
                    profiles = listOf(
                        MediaDeliveryProfile(
                            type = MediaDeliveryType.AUTO,
                            localFile = MediaDeliveryLocalFileConfig(
                                pathMappings = listOf(MediaDeliveryPathMapping(enabled = true)),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun webAdminConfigShouldHideLogBufferCapacityButStillValidate() {
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertFalse("webAdmin.logBufferCapacity" in paths)

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
            MainConfigForms.validate(MainDynamicConfig(draw = DrawSettings(themeColors = "#FE65A6;;#BFFAFF")))
        }

        assertTrue(error.message!!.contains("主题色"))
    }

    @Test
    fun mainConfigFormShouldKeepPresentationBasics() {
        val fields = MainConfigForms.formSpec.fields
        val paths = fields.map { it.path }
        val sections = fields.map { it.section }.toSet()
        val byPath = fields.associateBy { it.path }

        assertContainsAll(sections, listOf("基础设置", "推送内容", "链接解析", "发送与媒体", "系统维护"))
        assertFalse(fields.any { it.section.isBlank() })
        assertFalse(fields.any { it.label.contains("*") })

        assertEquals("基础设置", byPath.getValue("command.prefix").section)
        assertFalse(byPath.getValue("command.prefix").advanced)
        assertEquals("推送内容", byPath.getValue("draw.outputFormat").section)
        assertFalse(byPath.getValue("draw.outputFormat").advanced)
        assertEquals("推送内容", byPath.getValue("draw.font.typography.autoNormalize").section)
        assertTrue(byPath.getValue("draw.font.typography.autoNormalize").advanced)
        assertEquals("链接解析", byPath.getValue("linkParsing.templates.message").section)
        assertEquals("MESSAGE_TEMPLATE_EDITOR", byPath.getValue("linkParsing.templates.message").component)
        assertEquals("发送与媒体", byPath.getValue("mediaDelivery.profiles").section)
        assertFalse(byPath.getValue("mediaDelivery.profiles").advanced)
        assertEquals("MEDIA_DELIVERY_PROFILES", byPath.getValue("mediaDelivery.profiles").component)
        assertEquals("HIDDEN", byPath.getValue("mediaDelivery.defaultProfileId").component)
        assertFalse(byPath.getValue("messageRouting.defaultPolicy.strategy").advanced)
        assertFalse(byPath.getValue("messageRouting.defaultPolicy.primaryAccountId").advanced)
        assertEquals("系统维护", byPath.getValue("network.proxy.enabled").section)
        assertTrue(byPath.getValue("network.proxy.enabled").advanced)

        assertContainsNone(
            paths,
            listOf(
                "webAdmin.logBufferCapacity",
                "pluginCatalog.cacheSeconds",
                "pluginCatalog.maxDownloadMegabytes",
            ),
        )
    }

    private fun assertContainsAll(actual: Collection<String>, expected: Collection<String>) {
        expected.forEach { value ->
            assertTrue(value in actual, "缺少配置字段：$value")
        }
    }

    private fun assertContainsNone(actual: Collection<String>, removed: Collection<String>) {
        removed.forEach { value ->
            assertFalse(value in actual, "不应再展示配置字段：$value")
        }
    }
}
