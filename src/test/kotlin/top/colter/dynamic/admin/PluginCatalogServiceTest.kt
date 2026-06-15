package top.colter.dynamic.admin

import top.colter.dynamic.PluginCatalogConfig
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.plugin.PluginCapability
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginInstallResult
import top.colter.dynamic.plugin.PluginState
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginCatalogServiceTest {

    @Test
    fun catalogShouldResolveGitHubReleaseAndMergeInstalledStatus() {
        val catalog = catalogJson(
            item("demo-plugin"),
            item("extra-plugin"),
        )
        val service = service(
            catalogBytes = catalog.toByteArray(Charsets.UTF_8),
            releaseVersion = "1.1.0",
            installed = listOf(installedPlugin("demo-plugin", "1.0.0")),
        )

        val response = service.catalog()
        val byId = response.plugins.associateBy { it.id }

        assertEquals(2, response.schemaVersion)
        assertEquals("UPDATE_AVAILABLE", byId.getValue("demo-plugin").catalogStatus)
        assertTrue(byId.getValue("demo-plugin").updateAvailable)
        assertEquals("NOT_INSTALLED", byId.getValue("extra-plugin").catalogStatus)
        assertEquals("1.1.0", byId.getValue("demo-plugin").version)
        assertTrue(byId.getValue("demo-plugin").downloadUrl.startsWith("https://github.com/"))
        assertTrue(byId.getValue("demo-plugin").sha256.matches(Regex("^[a-fA-F0-9]{64}$")))
    }

    @Test
    fun catalogShouldResolveChecksumAssetWhenGitHubDigestIsMissing() {
        val jarBytes = pluginJarBytes(id = "demo-plugin", version = "1.0.0")
        val service = service(
            catalogBytes = catalogJson(item("demo-plugin")).toByteArray(Charsets.UTF_8),
            jarBytes = jarBytes,
            includeDigest = false,
        )

        val plugin = service.catalog().plugins.single()

        assertEquals(jarBytes.sha256(), plugin.sha256)
        assertEquals("1.0.0", plugin.version)
    }

    @Test
    fun catalogShouldKeepBrokenReleaseAsEntryError() {
        val service = service(
            catalogBytes = catalogJson(item("demo-plugin")).toByteArray(Charsets.UTF_8),
            unsafeAssetUrl = true,
        )

        val plugin = service.catalog().plugins.single()

        assertEquals("RESOLVE_FAILED", plugin.catalogStatus)
        assertTrue(plugin.error!!.contains("https"))
        assertFalse(plugin.updateAvailable)
    }

    @Test
    fun catalogShouldRejectInvalidReleaseSource() {
        val catalog = catalogJson(
            """
            {
              "id": "demo-plugin",
              "name": "demo-plugin",
              "release": {
                "provider": "GITHUB_RELEASE",
                "repository": "../demo",
                "assetPattern": "demo-plugin-*-all.jar"
              }
            }
            """.trimIndent(),
        )
        val service = service(catalogBytes = catalog.toByteArray(Charsets.UTF_8))

        val error = assertFailsWith<IllegalArgumentException> {
            service.catalog()
        }

        assertTrue(error.message!!.contains("GitHub 仓库格式"))
    }

    @Test
    fun catalogShouldWrapRemoteDownloadFailure() {
        val service = PluginCatalogService(
            configProvider = { PluginCatalogConfig(url = "https://example.com/catalog.json") },
            pluginProvider = { emptyList() },
            pluginDirPathProvider = { createTempDirectory("plugin-catalog-missing").toString() },
            pluginInstaller = { _, id, version, _, _ ->
                PluginInstallResult(
                    pluginId = id,
                    success = true,
                    changed = true,
                    pluginState = PluginState.ACTIVE,
                    newVersion = version,
                    message = "ok",
                )
            },
            downloader = object : PluginCatalogDownloader {
                override fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray {
                    throw java.io.FileNotFoundException("404")
                }

                override fun downloadToFile(
                    url: String,
                    destination: File,
                    timeoutSeconds: Double,
                    maxBytes: Long,
                ): PluginCatalogDownloadResult {
                    throw java.io.FileNotFoundException("404")
                }
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            service.catalog()
        }

        assertTrue(error.message!!.contains("插件目录获取失败"))
    }

    @Test
    fun catalogShouldRedactSensitiveUrlInFailureMessage() {
        val service = PluginCatalogService(
            configProvider = { PluginCatalogConfig(url = "https://example.com/catalog.json") },
            pluginProvider = { emptyList() },
            pluginDirPathProvider = { createTempDirectory("plugin-catalog-redacted").toString() },
            pluginInstaller = { _, id, version, _, _ ->
                PluginInstallResult(
                    pluginId = id,
                    success = true,
                    changed = true,
                    pluginState = PluginState.ACTIVE,
                    newVersion = version,
                    message = "ok",
                )
            },
            downloader = object : PluginCatalogDownloader {
                override fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray {
                    throw IllegalStateException("failed https://example.com/catalog.json?token=secret#frag")
                }

                override fun downloadToFile(
                    url: String,
                    destination: File,
                    timeoutSeconds: Double,
                    maxBytes: Long,
                ): PluginCatalogDownloadResult {
                    throw IllegalStateException("failed https://example.com/demo.jar?token=secret#frag")
                }
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            service.catalog()
        }

        assertTrue(error.message!!.contains("https://example.com/catalog.json?<hidden>"))
        assertFalse(error.message!!.contains("token=secret"))
        assertFalse(error.message!!.contains("frag"))
    }

    @Test
    fun catalogShouldFallbackToLocalDefaultCatalogWhenOfficialUrlFails() {
        val localDir = createTempDirectory("plugin-catalog-local").toFile()
        localDir.resolve("catalog.json").writeText(
            catalogJson(item("demo-plugin")),
            Charsets.UTF_8,
        )
        val service = PluginCatalogService(
            configProvider = { PluginCatalogConfig() },
            pluginProvider = { emptyList() },
            pluginDirPathProvider = { localDir.path },
            pluginInstaller = { _, id, version, _, _ ->
                PluginInstallResult(
                    pluginId = id,
                    success = true,
                    changed = true,
                    pluginState = PluginState.ACTIVE,
                    newVersion = version,
                    message = "ok",
                )
            },
            downloader = FailingCatalogDownloader(
                fallback = FakeDownloader(
                    catalogBytes = ByteArray(0),
                    jarBytes = pluginJarBytes("demo-plugin", "1.0.0"),
                ),
            ),
        )

        val response = service.catalog()

        assertEquals("demo-plugin", response.plugins.single().id)
        assertEquals("LOCAL_FALLBACK", response.source)
        assertTrue(response.warning!!.contains("远程插件目录获取失败"))
    }

    @Test
    fun officialPluginCatalogFileShouldUseReleaseSourcesWithoutVersionOrSha256() {
        val file = File("plugins/catalog.json")
        assertTrue(file.isFile, "缺少官方插件目录文件：${file.absolutePath}")
        val raw = file.readText(Charsets.UTF_8)
        val service = service(catalogBytes = file.readBytes(), releaseVersion = "0.0.1")

        val response = service.catalog()

        assertEquals(2, response.schemaVersion)
        assertTrue(response.plugins.isNotEmpty())
        assertFalse(Regex("\"version\"\\s*:").containsMatchIn(raw))
        assertFalse(Regex("\"downloadUrl\"\\s*:").containsMatchIn(raw))
        assertFalse(Regex("\"sha256\"\\s*:").containsMatchIn(raw))
        response.plugins.forEach { plugin ->
            assertTrue(
                plugin.downloadUrl.contains("/v${plugin.version}/"),
                "插件 ${plugin.id} 的下载地址缺少版本 tag：${plugin.downloadUrl}",
            )
            assertTrue(
                plugin.downloadUrl.substringAfterLast('/').contains(plugin.version),
                "插件 ${plugin.id} 的下载文件名未包含版本号：${plugin.downloadUrl}",
            )
            assertTrue(plugin.sha256.matches(Regex("^[a-fA-F0-9]{64}$")))
            assertTrue(plugin.sizeBytes > 0)
        }
    }

    @Test
    fun installShouldDownloadVerifyJarAndCallInstaller() {
        val jarBytes = pluginJarBytes(id = "demo-plugin", version = "1.0.0")
        val calls = mutableListOf<String>()
        val service = service(
            catalogBytes = catalogJson(item("demo-plugin")).toByteArray(Charsets.UTF_8),
            jarBytes = jarBytes,
            includeDigest = false,
            installer = { file, id, version, startAfterInstall, requireInstalled ->
                assertTrue(file.exists())
                assertEquals("demo-plugin", id)
                assertEquals("1.0.0", version)
                assertTrue(startAfterInstall)
                assertFalse(requireInstalled)
                calls += "$id:$version"
                PluginInstallResult(
                    pluginId = id,
                    success = true,
                    changed = true,
                    pluginState = PluginState.ACTIVE,
                    newVersion = version,
                    message = "ok",
                )
            },
        )

        val response = service.install("demo-plugin")

        assertEquals("demo-plugin", response.pluginId)
        assertEquals("1.0.0", response.installedVersion)
        assertEquals(listOf("demo-plugin:1.0.0"), calls)
    }

    @Test
    fun installShouldRejectSha256Mismatch() {
        val jarBytes = pluginJarBytes(id = "demo-plugin", version = "1.0.0")
        val service = service(
            catalogBytes = catalogJson(item("demo-plugin")).toByteArray(Charsets.UTF_8),
            jarBytes = jarBytes,
            includeDigest = false,
            checksumOverride = "0".repeat(64),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.install("demo-plugin")
        }

        assertTrue(error.message!!.contains("sha256"))
    }

    private fun service(
        catalogBytes: ByteArray,
        jarBytes: ByteArray = pluginJarBytes("demo-plugin", "1.0.0"),
        releaseVersion: String = "1.0.0",
        installed: List<PluginInfo> = emptyList(),
        includeDigest: Boolean = true,
        unsafeAssetUrl: Boolean = false,
        checksumOverride: String? = null,
        installer: (File, String, String, Boolean, Boolean) -> PluginInstallResult = { _, id, version, _, _ ->
            PluginInstallResult(
                pluginId = id,
                success = true,
                changed = true,
                pluginState = PluginState.ACTIVE,
                newVersion = version,
                message = "ok",
            )
        },
    ): PluginCatalogService {
        return PluginCatalogService(
            configProvider = {
                PluginCatalogConfig(
                    url = "https://example.com/catalog.json",
                    cacheSeconds = 600,
                    downloadTimeoutSeconds = 1.0,
                    maxDownloadMegabytes = 1.0,
                )
            },
            pluginProvider = { installed },
            pluginDirPathProvider = { createTempDirectory("plugin-catalog-service").toString() },
            pluginInstaller = installer,
            downloader = FakeDownloader(
                catalogBytes = catalogBytes,
                jarBytes = jarBytes,
                releaseVersion = releaseVersion,
                includeDigest = includeDigest,
                unsafeAssetUrl = unsafeAssetUrl,
                checksumOverride = checksumOverride,
            ),
            clock = { 1000L },
        )
    }

    private fun installedPlugin(id: String, version: String): PluginInfo {
        return PluginInfo(
            descriptor = PluginDescriptor(
                id = id,
                name = id,
                version = version,
                mainClass = "Demo",
            ),
            capabilities = setOf(PluginCapability.MESSAGE_SINK),
            state = PluginState.LOADED,
            sourceJarPath = "plugins/$id.jar",
        )
    }

    private fun catalogJson(vararg items: String): String {
        return """
            {
              "schemaVersion": 2,
              "plugins": [${items.joinToString(",")}]
            }
        """.trimIndent()
    }

    private fun item(
        id: String,
        repository: String = "Colter23/$id",
        assetPattern: String = "$id-*-all.jar",
        checksumAssetPattern: String = "$id-*-all.jar.sha256",
    ): String {
        return """
            {
              "id": "$id",
              "name": "$id",
              "description": "测试插件",
              "release": {
                "provider": "GITHUB_RELEASE",
                "repository": "$repository",
                "assetPattern": "$assetPattern",
                "checksumAssetPattern": "$checksumAssetPattern"
              },
              "homepageUrl": "https://github.com/$repository",
              "capabilities": ["MESSAGE_SINK"]
            }
        """.trimIndent()
    }

    private fun pluginJarBytes(id: String, version: String): ByteArray {
        val output = ByteArrayOutputStream()
        JarOutputStream(output).use { jar ->
            jar.putNextEntry(JarEntry("plugin.yml"))
            jar.write(
                """
                    id: $id
                    name: $id
                    version: $version
                    mainClass: top.colter.dynamic.plugin.LifecycleRecordingPlugin
                    apiVersion: $CORE_PLUGIN_API_VERSION
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            jar.closeEntry()
        }
        return output.toByteArray()
    }

    private fun ByteArray.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
    }

    private class FakeDownloader(
        private val catalogBytes: ByteArray,
        private val jarBytes: ByteArray,
        private val releaseVersion: String = "1.0.0",
        private val includeDigest: Boolean = true,
        private val unsafeAssetUrl: Boolean = false,
        private val checksumOverride: String? = null,
    ) : PluginCatalogDownloader {
        override fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray {
            return when {
                url.contains("api.github.com/repos/") -> releaseJson(url).toByteArray(Charsets.UTF_8)
                url.endsWith(".sha256") -> checksumText(url).toByteArray(Charsets.UTF_8)
                else -> catalogBytes
            }
        }

        override fun downloadToFile(
            url: String,
            destination: File,
            timeoutSeconds: Double,
            maxBytes: Long,
        ): PluginCatalogDownloadResult {
            destination.parentFile?.mkdirs()
            destination.writeBytes(jarBytes)
            return PluginCatalogDownloadResult(
                bytesRead = jarBytes.size.toLong(),
                sha256 = sha256(jarBytes),
            )
        }

        private fun releaseJson(url: String): String {
            val repo = url.substringAfter("/repos/").substringBefore("/releases/")
            val repoName = repo.substringAfterLast('/')
            val jarName = "$repoName-$releaseVersion-all.jar"
            val downloadUrl = if (unsafeAssetUrl) {
                "http://example.com/$jarName"
            } else {
                "https://github.com/$repo/releases/download/v$releaseVersion/$jarName"
            }
            val checksumUrl = "https://github.com/$repo/releases/download/v$releaseVersion/$jarName.sha256"
            val digest = if (includeDigest) """, "digest": "sha256:${sha256(jarBytes)}"""" else ""
            return """
                {
                  "tag_name": "v$releaseVersion",
                  "html_url": "https://github.com/$repo/releases/tag/v$releaseVersion",
                  "assets": [
                    {
                      "name": "$jarName",
                      "browser_download_url": "$downloadUrl",
                      "size": ${jarBytes.size}$digest
                    },
                    {
                      "name": "$jarName.sha256",
                      "browser_download_url": "$checksumUrl",
                      "size": 96
                    }
                  ]
                }
            """.trimIndent()
        }

        private fun checksumText(url: String): String {
            val jarName = url.substringAfterLast('/').removeSuffix(".sha256")
            return "${checksumOverride ?: sha256(jarBytes)}  $jarName\n"
        }

        private fun sha256(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
    }

    private class FailingCatalogDownloader(
        private val fallback: FakeDownloader,
    ) : PluginCatalogDownloader {
        override fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray {
            if (url == PluginCatalogConfig.DEFAULT_URL) throw java.io.FileNotFoundException("404")
            return fallback.downloadToByteArray(url, timeoutSeconds, maxBytes)
        }

        override fun downloadToFile(
            url: String,
            destination: File,
            timeoutSeconds: Double,
            maxBytes: Long,
        ): PluginCatalogDownloadResult {
            return fallback.downloadToFile(url, destination, timeoutSeconds, maxBytes)
        }
    }
}
