package top.colter.dynamic

import org.junit.jupiter.api.Disabled
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


@Disabled
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

        assertEquals(DrawOutputFormat.PNG, MainDynamicConfig().draw.outputFormat)
        assertEquals("{draw}", MainDynamicConfig().linkParsing.templates.message)
        assertEquals(0.0, MainDynamicConfig().linkParsing.videoDownload.maxFileMegabytes)
        assertTrue("draw.outputFormat" in paths)
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
            ).replace(Regex("""\n  outputFormat:.*"""), ""),
        )

        val loaded = MainConfigStore(configService).loadOrCreate { "token" }
        val rewritten = path.readText()

        assertEquals("#111111;#222222;#333333", loaded.draw.themeColors)
        assertEquals(DrawOutputFormat.PNG, loaded.draw.outputFormat)
        assertTrue(rewritten.contains("outputFormat:"))
        assertTrue(rewritten.contains("themeColors:"))
        assertFalse(rewritten.contains("themeColor:"))
        assertFalse(rewritten.contains("backgroundStartColor"))
        assertFalse(rewritten.contains("backgroundEndColor"))
    }

    @Test
    fun pluginCatalogConfigShouldBeExposedAndRequireHttpsUrl() {
        val paths = MainConfigForms.formSpec.fields.map { it.path }

        assertTrue("pluginCatalog.url" in paths)
        assertTrue("pluginCatalog.downloadTimeoutSeconds" in paths)
        assertFalse("pluginCatalog.cacheSeconds" in paths)
        assertFalse("pluginCatalog.maxDownloadMegabytes" in paths)

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

        assertFalse("imageCache.memoryMaxMegabytes" in paths)
        assertFalse("imageCache.memoryMaxEntries" in paths)
        assertTrue("delivery.historyRetentionDays" in paths)
        assertTrue("delivery.cleanupCron" in paths)
        assertTrue("mediaDelivery.defaultProfileId" in paths)
        assertTrue("mediaDelivery.profiles" in paths)
        assertFalse("outboundMedia.enabled" in paths)

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
    fun shouldMigrateLegacyOutboundMediaToMediaDeliveryProfile() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-media-migration"))
        val path = configService.resolvePath(MainDynamicConfig.CONFIG_ID)
        path.parent.createDirectories()
        path.writeText(
            """
            webAdmin:
              enabled: true
              token: token
            outboundMedia:
              enabled: true
              publicBaseUrl: "http://example.com:2233"
              urlTtlSeconds: 120
              signingSecret: old-secret
            """.trimIndent(),
        )

        val loaded = MainConfigStore(configService).loadOrCreate { "token" }
        val rewritten = path.readText()

        assertEquals("remote", loaded.mediaDelivery.defaultProfileId)
        val remote = loaded.mediaDelivery.profiles.single { it.id == "remote" }
        assertEquals(MediaDeliveryType.SIGNED_URL, remote.type)
        assertEquals("http://example.com:2233", remote.signedUrl.publicBaseUrl)
        assertEquals(120, remote.signedUrl.ttlSeconds)
        assertEquals("old-secret", remote.signedUrl.signingSecret)
        assertFalse(rewritten.contains("outboundMedia"))
        assertTrue(rewritten.contains("mediaDelivery"))
    }

    @Test
    fun shouldMigrateLegacyMediaDeliveryProfileBytesToMegabytes() {
        assertEquals(
            listOf("main-media-delivery-profiles"),
            MainConfigForms.migrations.map { it.id }.filter { it.startsWith("main-media-delivery") },
        )
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-media-profile-migration"))
        val path = configService.resolvePath(MainDynamicConfig.CONFIG_ID)
        path.parent.createDirectories()
        path.writeText(
            """
            webAdmin:
              enabled: true
              token: token
            mediaDelivery:
              defaultProfileId: auto
              profiles:
                - id: auto
                  name: 自动
                  type: AUTO
                  imageBase64MaxBytes: 5242880
                  publicBaseUrl: ""
                  urlTtlSeconds: 1800
                  signingSecret: old-secret
                  pathMappings: []
            """.trimIndent(),
        )

        val loaded = MainConfigStore(configService).loadOrCreate { "token" }
        val rewritten = path.readText()

        val auto = loaded.mediaDelivery.profiles.single { it.id == "auto" }
        assertEquals(5.0, auto.base64Fallback.maxMegabytes)
        assertEquals("old-secret", auto.signedUrl.signingSecret)
        assertFalse(rewritten.contains("imageBase64MaxBytes"))
        assertTrue(rewritten.contains("maxMegabytes"))
    }

    @Test
    fun shouldMigrateRemovedLinkParseReplyFields() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-link-parse-migration"))
        val path = configService.resolvePath(MainDynamicConfig.CONFIG_ID)
        path.parent.createDirectories()
        path.writeText(
            """
            webAdmin:
              enabled: true
              token: token
            linkParsing:
              replyOnFailure: false
              progressReply:
                enabled: false
                text: "链接解析中，请稍候..."
                recallOnComplete: true
            """.trimIndent(),
        )

        val loaded = MainConfigStore(configService).loadOrCreate { "token" }
        val rewritten = path.readText()
        val progressReplyBlock = rewritten
            .substringAfter("progressReply:", "")
            .substringBefore("templates:", "")

        assertEquals("", loaded.linkParsing.progressReply.text)
        assertFalse(rewritten.contains("replyOnFailure"))
        assertFalse(progressReplyBlock.contains("enabled:"))
    }

    @Test
    fun shouldMigrateSimplifiedVideoDownloadPromptFields() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-video-download-migration"))
        val path = configService.resolvePath(MainDynamicConfig.CONFIG_ID)
        path.parent.createDirectories()
        path.writeText(
            """
            webAdmin:
              enabled: true
              token: token
            linkParsing:
              videoDownload:
                enabled: true
                maxFileMegabytes: 200.0
                prompts:
                  downloading: "视频正在下载"
                  durationUnknown: "时长未知"
                  durationTooLong: "时长超限"
                  noDownloader: "无下载器"
                  timeout: "下载超时"
                  fileTooLarge: "文件过大"
                  failed: "下载失败：{reason}"
            """.trimIndent(),
        )

        val loaded = MainConfigStore(configService).loadOrCreate { "token" }
        val rewritten = path.readText()

        assertEquals(0.0, loaded.linkParsing.videoDownload.maxFileMegabytes)
        assertEquals("视频正在下载", loaded.linkParsing.videoDownload.prompts.downloading)
        assertEquals("下载失败：{reason}", loaded.linkParsing.videoDownload.prompts.failed)
        assertEquals("{draw}\\r{video}", loaded.linkParsing.templates.message)
        assertFalse(Regex("""videoDownload:\r?\n\s+enabled:""").containsMatchIn(rewritten))
        assertFalse(rewritten.contains("durationUnknown"))
        assertFalse(rewritten.contains("durationTooLong"))
        assertFalse(rewritten.contains("noDownloader"))
        assertFalse(rewritten.contains("fileTooLarge"))
    }

    @Test
    fun shouldMigrateLegacyLinkParseTemplatesToSingleMessageTemplate() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-link-template-migration"))
        val path = configService.resolvePath(MainDynamicConfig.CONFIG_ID)
        path.parent.createDirectories()
        path.writeText(
            """
            webAdmin:
              enabled: true
              token: token
            linkParsing:
              videoDownload:
                enabled: true
              templates:
                video: "{draw}\\n{title}"
                live: "{draw}\\n{link}"
                user: "{name}\\n{link}"
                fallback: "{draw}"
                videoFile: "{video}\\n{size}"
            """.trimIndent(),
        )

        val loaded = MainConfigStore(configService).loadOrCreate { "token" }
        val rewritten = path.readText()

        assertEquals("{draw}\\n{title}\\r{video}\\n{size}", loaded.linkParsing.templates.message)
        assertTrue(rewritten.contains("message:"))
        assertFalse(rewritten.contains("videoFile:"))
        assertFalse(rewritten.contains("fallback:"))
    }

    @Test
    fun shouldRemoveVideoPlaceholderWhenMigratingDisabledLinkVideoDownload() {
        val configService = YamlConfigService(createTempDirectory("dynamic-bot-main-link-template-disabled-migration"))
        val path = configService.resolvePath(MainDynamicConfig.CONFIG_ID)
        path.parent.createDirectories()
        path.writeText(
            """
            webAdmin:
              enabled: true
              token: token
            linkParsing:
              videoDownload:
                enabled: false
              templates:
                message: "{draw}\\r{video}"
            """.trimIndent(),
        )

        val loaded = MainConfigStore(configService).loadOrCreate { "token" }
        val rewritten = path.readText()

        assertEquals("{draw}", loaded.linkParsing.templates.message)
        assertFalse(rewritten.contains("{video}"))
        assertFalse(Regex("""videoDownload:\r?\n\s+enabled:""").containsMatchIn(rewritten))
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
    fun mainConfigFormShouldGroupFieldsAndCollapseAdvancedOptions() {
        val fields = MainConfigForms.formSpec.fields
        val byPath = fields.associateBy { it.path }

        assertEquals(
            listOf("基础设置", "推送内容", "链接解析", "发送与媒体", "系统维护"),
            fields.map { it.section }.distinct(),
        )
        assertEquals("基础设置", byPath.getValue("command.prefix").section)
        assertFalse(byPath.getValue("command.prefix").advanced)
        assertEquals("推送内容", byPath.getValue("draw.outputFormat").section)
        assertFalse(byPath.getValue("draw.outputFormat").advanced)
        assertEquals("推送内容", byPath.getValue("linkParsing.templates.message").section)
        assertTrue(byPath.getValue("linkParsing.templates.message").advanced)
        assertFalse("linkParsing.replyOnFailure" in byPath)
        assertFalse("linkParsing.progressReply.enabled" in byPath)
        assertFalse("linkParsing.videoDownload.enabled" in byPath)
        assertFalse("linkParsing.templates.video" in byPath)
        assertFalse("linkParsing.templates.live" in byPath)
        assertFalse("linkParsing.templates.user" in byPath)
        assertFalse("linkParsing.templates.fallback" in byPath)
        assertFalse("linkParsing.templates.videoFile" in byPath)
        assertFalse("linkParsing.videoDownload.prompts.durationUnknown" in byPath)
        assertFalse("linkParsing.videoDownload.prompts.durationTooLong" in byPath)
        assertFalse("linkParsing.videoDownload.prompts.noDownloader" in byPath)
        assertFalse("linkParsing.videoDownload.prompts.timeout" in byPath)
        assertFalse("linkParsing.videoDownload.prompts.fileTooLarge" in byPath)
        assertEquals("发送与媒体", byPath.getValue("mediaDelivery.profiles").section)
        assertFalse(byPath.getValue("mediaDelivery.profiles").advanced)
        assertEquals("MEDIA_DELIVERY_PROFILES", byPath.getValue("mediaDelivery.profiles").component)
        assertEquals("HIDDEN", byPath.getValue("mediaDelivery.defaultProfileId").component)
        assertTrue(byPath.getValue("delivery.lockTtlSeconds").advanced)
        assertTrue(byPath.getValue("pluginCatalog.url").advanced)
        assertFalse("imageCache.memoryMaxEntries" in byPath)
        assertFalse("webAdmin.logBufferCapacity" in byPath)
        assertFalse("pluginCatalog.cacheSeconds" in byPath)
        assertFalse("pluginCatalog.maxDownloadMegabytes" in byPath)
    }
}
