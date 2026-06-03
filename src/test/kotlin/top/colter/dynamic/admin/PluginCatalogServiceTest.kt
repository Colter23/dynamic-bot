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
    fun catalogShouldMergeInstalledStatusAndRejectIncompatibleApi() {
        val catalog = catalogJson(
            item("demo-plugin", "1.1.0", sha256 = "a".repeat(64)),
            item("old-plugin", "1.0.0", apiVersion = "3.0.0", sha256 = "b".repeat(64)),
        )
        val service = service(
            catalogBytes = catalog.toByteArray(Charsets.UTF_8),
            installed = listOf(installedPlugin("demo-plugin", "1.0.0")),
        )

        val response = service.catalog()
        val byId = response.plugins.associateBy { it.id }

        assertEquals("UPDATE_AVAILABLE", byId.getValue("demo-plugin").catalogStatus)
        assertTrue(byId.getValue("demo-plugin").updateAvailable)
        assertEquals("INCOMPATIBLE", byId.getValue("old-plugin").catalogStatus)
        assertFalse(byId.getValue("old-plugin").updateAvailable)
    }

    @Test
    fun catalogShouldRejectUnsafeDownloadUrl() {
        val catalog = catalogJson(
            item(
                id = "demo-plugin",
                version = "1.0.0",
                downloadUrl = "http://example.com/demo.jar",
                sha256 = "a".repeat(64),
            ),
        )
        val service = service(catalogBytes = catalog.toByteArray(Charsets.UTF_8))

        val error = assertFailsWith<IllegalArgumentException> {
            service.catalog()
        }

        assertTrue(error.message!!.contains("https"))
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
    fun catalogShouldFallbackToLocalDefaultCatalogWhenOfficialUrlFails() {
        val localDir = createTempDirectory("plugin-catalog-local").toFile()
        localDir.resolve("catalog.json").writeText(
            catalogJson(item("demo-plugin", "1.0.0", sha256 = "a".repeat(64))),
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

        val response = service.catalog()

        assertEquals("demo-plugin", response.plugins.single().id)
        assertEquals("LOCAL_FALLBACK", response.source)
        assertTrue(response.warning!!.contains("远程插件目录获取失败"))
    }

    @Test
    fun officialPluginCatalogFileShouldBeValidAndUseVersionedReleaseArtifacts() {
        val file = File("plugins/catalog.json")
        assertTrue(file.isFile, "缺少官方插件目录文件：${file.absolutePath}")
        val service = service(catalogBytes = file.readBytes())

        val response = service.catalog()

        assertEquals("REMOTE", response.source)
        assertTrue(response.plugins.isNotEmpty())
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
        val catalog = catalogJson(
            item(
                id = "demo-plugin",
                version = "1.0.0",
                sha256 = jarBytes.sha256(),
            ),
        )
        val calls = mutableListOf<String>()
        val service = service(
            catalogBytes = catalog.toByteArray(Charsets.UTF_8),
            jarBytes = jarBytes,
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
        val catalog = catalogJson(
            item(
                id = "demo-plugin",
                version = "1.0.0",
                sha256 = "0".repeat(64),
            ),
        )
        val service = service(catalogBytes = catalog.toByteArray(Charsets.UTF_8), jarBytes = jarBytes)

        val error = assertFailsWith<IllegalArgumentException> {
            service.install("demo-plugin")
        }

        assertTrue(error.message!!.contains("sha256"))
    }

    private fun service(
        catalogBytes: ByteArray,
        jarBytes: ByteArray = ByteArray(0),
        installed: List<PluginInfo> = emptyList(),
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
                    maxDownloadBytes = 1024 * 1024,
                )
            },
            pluginProvider = { installed },
            pluginDirPathProvider = { createTempDirectory("plugin-catalog-service").toString() },
            pluginInstaller = installer,
            downloader = FakeDownloader(catalogBytes, jarBytes),
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
              "schemaVersion": 1,
              "plugins": [${items.joinToString(",")}]
            }
        """.trimIndent()
    }

    private fun item(
        id: String,
        version: String,
        apiVersion: String = CORE_PLUGIN_API_VERSION,
        downloadUrl: String = "https://example.com/$id.jar",
        sha256: String,
    ): String {
        return """
            {
              "id": "$id",
              "name": "$id",
              "version": "$version",
              "description": "测试插件",
              "apiVersion": "$apiVersion",
              "downloadUrl": "$downloadUrl",
              "sha256": "$sha256",
              "sizeBytes": 128,
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
    ) : PluginCatalogDownloader {
        override fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray {
            return catalogBytes
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
                sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(jarBytes)
                    .joinToString("") { "%02x".format(it) },
            )
        }
    }
}
