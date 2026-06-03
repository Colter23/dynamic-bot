package top.colter.dynamic.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import top.colter.dynamic.PluginCatalogConfig
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginInstallResult
import top.colter.dynamic.plugin.PluginScanner
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLConnection
import java.security.MessageDigest
import kotlin.math.roundToInt

public class PluginCatalogService(
    private val configProvider: () -> PluginCatalogConfig,
    private val pluginProvider: () -> List<PluginInfo>,
    private val pluginDirPathProvider: () -> String = { "plugins" },
    private val pluginInstaller: (File, String, String, Boolean, Boolean) -> PluginInstallResult,
    private val downloader: PluginCatalogDownloader = UrlPluginCatalogDownloader(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val cacheLock = Any()
    private val operationLock = Any()

    @Volatile
    private var cachedCatalog: CachedCatalog? = null

    public fun catalog(force: Boolean = false): PluginCatalogResponse {
        val config = configProvider()
        val catalog = loadCatalog(config, force)
        return catalog.toResponse(pluginProvider(), config)
    }

    public fun cachedCatalog(): PluginCatalogResponse? {
        val config = configProvider()
        return cachedCatalog
            ?.takeIf { it.url == config.url.trim() }
            ?.toResponse(pluginProvider(), config)
    }

    public fun install(pluginId: String): PluginCatalogOperationResponse {
        val normalizedId = pluginId.trim()
        require(normalizedId.isNotBlank()) { "插件 ID 不能为空" }
        return synchronized(operationLock) {
            val config = configProvider()
            val item = loadCatalog(config, force = false).document.itemOrThrow(normalizedId)
            requireInstallable(item)
            require(pluginProvider().none { it.descriptor.id == normalizedId }) {
                "插件已安装，请使用更新操作：$normalizedId"
            }

            val jarFile = downloadAndVerifyJar(item, config)
            try {
                val result = pluginInstaller(jarFile, item.id, item.version, true, false)
                result.toOperationResponse(item)
            } catch (e: Throwable) {
                jarFile.delete()
                throw e
            }
        }
    }

    public fun update(pluginId: String): PluginCatalogOperationResponse {
        val normalizedId = pluginId.trim()
        require(normalizedId.isNotBlank()) { "插件 ID 不能为空" }
        return synchronized(operationLock) {
            val config = configProvider()
            val installed = pluginProvider().firstOrNull { it.descriptor.id == normalizedId }
                ?: throw NoSuchElementException("插件未安装：$normalizedId")
            val item = loadCatalog(config, force = false).document.itemOrThrow(normalizedId)
            requireInstallable(item)
            require(comparePluginVersions(item.version, installed.descriptor.version) > 0) {
                "当前已是最新版本：$normalizedId，installed=${installed.descriptor.version}，catalog=${item.version}"
            }

            val jarFile = downloadAndVerifyJar(item, config)
            try {
                val result = pluginInstaller(jarFile, item.id, item.version, true, true)
                result.toOperationResponse(item)
            } catch (e: Throwable) {
                jarFile.delete()
                throw e
            }
        }
    }

    private fun loadCatalog(config: PluginCatalogConfig, force: Boolean): CachedCatalog {
        val url = config.url.trim()
        require(url.isNotBlank()) { "插件目录地址未配置，插件下载与更新已关闭" }
        requireHttpsUrl(url, "插件目录地址")
        val now = clock()
        cachedCatalog
            ?.takeIf { !force && it.url == url && now < it.expiresAt(config) }
            ?.let { return it }

        return synchronized(cacheLock) {
            val retryNow = clock()
            cachedCatalog
                ?.takeIf { !force && it.url == url && retryNow < it.expiresAt(config) }
                ?.let { return@synchronized it }

            val bytes = try {
                downloader.downloadToByteArray(
                    url = url,
                    timeoutSeconds = config.downloadTimeoutSeconds,
                    maxBytes = CATALOG_MAX_BYTES,
                )
            } catch (e: Exception) {
                localDefaultCatalogBytes(config)?.let { it }
                    ?: throw IllegalStateException("插件目录获取失败：${e.message ?: e::class.simpleName ?: "未知错误"}", e)
            }
            val text = bytes.toString(Charsets.UTF_8)
            val document = try {
                json.decodeFromString<PluginCatalogDocument>(text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("插件目录 JSON 格式无效：${e.message}", e)
            }
            validateCatalog(document)
            CachedCatalog(url = url, document = document, fetchedAtEpochMillis = clock())
                .also { cachedCatalog = it }
        }
    }

    private fun downloadAndVerifyJar(item: PluginCatalogItem, config: PluginCatalogConfig): File {
        require(item.sizeBytes <= config.maxDownloadBytes) {
            "插件文件超过大小限制：pluginId=${item.id}，size=${item.sizeBytes}，maxBytes=${config.maxDownloadBytes}"
        }
        val downloadDir = File(pluginDirPathProvider()).resolve(".downloads")
        downloadDir.mkdirs()
        val jarFile = downloadDir.resolve("${item.id}-${item.version}.jar.tmp")
        jarFile.delete()

        return try {
            val result = try {
                downloader.downloadToFile(
                    url = item.downloadUrl,
                    destination = jarFile,
                    timeoutSeconds = config.downloadTimeoutSeconds,
                    maxBytes = config.maxDownloadBytes,
                )
            } catch (e: Exception) {
                throw IllegalStateException("插件下载失败：pluginId=${item.id}，${e.message ?: e::class.simpleName ?: "未知错误"}", e)
            }
            require(result.sha256.equals(item.sha256, ignoreCase = true)) {
                "插件下载校验失败：pluginId=${item.id}，期望 sha256=${item.sha256}，实际 sha256=${result.sha256}"
            }
            val descriptor = PluginScanner.parsePluginDescriptor(jarFile, yamlMapper)
            require(descriptor.id == item.id) {
                "插件 Jar 描述不匹配：catalog=${item.id}，jar=${descriptor.id}"
            }
            require(descriptor.version == item.version) {
                "插件 Jar 版本不匹配：catalog=${item.version}，jar=${descriptor.version}"
            }
            require(descriptor.apiVersion == item.apiVersion) {
                "插件 Jar API 版本不匹配：catalog=${item.apiVersion}，jar=${descriptor.apiVersion}"
            }
            jarFile
        } catch (e: Throwable) {
            jarFile.delete()
            throw e
        }
    }

    private fun requireInstallable(item: PluginCatalogItem) {
        require(item.apiVersion == CORE_PLUGIN_API_VERSION) {
            "插件 API 版本不兼容：pluginId=${item.id}，catalog=${item.apiVersion}，current=$CORE_PLUGIN_API_VERSION"
        }
        requireHttpsUrl(item.downloadUrl, "插件下载地址")
    }

    private fun CachedCatalog.toResponse(
        installedPlugins: List<PluginInfo>,
        config: PluginCatalogConfig,
    ): PluginCatalogResponse {
        val installedById = installedPlugins.associateBy { it.descriptor.id }
        return PluginCatalogResponse(
            schemaVersion = document.schemaVersion,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            cacheExpiresAtEpochMillis = expiresAt(config),
            plugins = document.plugins
                .map { it.toDto(installedById[it.id]) }
                .sortedBy { it.id },
        )
    }

    private fun PluginCatalogDocument.itemOrThrow(pluginId: String): PluginCatalogItem {
        return plugins.firstOrNull { it.id == pluginId }
            ?: throw NoSuchElementException("插件目录中未找到插件：$pluginId")
    }

    private fun PluginCatalogItem.toDto(installed: PluginInfo?): PluginCatalogEntryDto {
        val status = catalogStatus(installed)
        return PluginCatalogEntryDto(
            id = id,
            name = name,
            version = version,
            description = description,
            apiVersion = apiVersion,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            homepageUrl = homepageUrl,
            releaseNotesUrl = releaseNotesUrl,
            capabilities = capabilities.sorted(),
            installedVersion = installed?.descriptor?.version,
            installedState = installed?.state?.name,
            catalogStatus = status.name,
            updateAvailable = status == PluginCatalogStatus.UPDATE_AVAILABLE,
            error = when (status) {
                PluginCatalogStatus.INCOMPATIBLE ->
                    "插件 API 版本不兼容：catalog=$apiVersion，current=$CORE_PLUGIN_API_VERSION"
                PluginCatalogStatus.DOWNLOAD_DISABLED -> "插件下载地址不可用"
                else -> null
            },
        )
    }

    private fun PluginCatalogItem.catalogStatus(installed: PluginInfo?): PluginCatalogStatus {
        if (apiVersion != CORE_PLUGIN_API_VERSION) return PluginCatalogStatus.INCOMPATIBLE
        if (downloadUrl.isBlank()) return PluginCatalogStatus.DOWNLOAD_DISABLED
        if (installed == null) return PluginCatalogStatus.NOT_INSTALLED
        return if (comparePluginVersions(version, installed.descriptor.version) > 0) {
            PluginCatalogStatus.UPDATE_AVAILABLE
        } else {
            PluginCatalogStatus.INSTALLED
        }
    }

    private fun PluginInstallResult.toOperationResponse(item: PluginCatalogItem): PluginCatalogOperationResponse {
        return PluginCatalogOperationResponse(
            changed = changed,
            success = success,
            pluginId = pluginId,
            pluginState = pluginState?.name,
            message = message,
            installedVersion = newVersion ?: item.version,
            catalogVersion = item.version,
        )
    }

    private fun validateCatalog(document: PluginCatalogDocument) {
        require(document.schemaVersion == 1) { "插件目录 schemaVersion 不支持：${document.schemaVersion}" }
        val ids = mutableSetOf<String>()
        document.plugins.forEach { item ->
            require(PLUGIN_ID_REGEX.matches(item.id)) { "插件目录包含非法插件 ID：${item.id}" }
            require(ids.add(item.id)) { "插件目录包含重复插件 ID：${item.id}" }
            require(item.name.isNotBlank()) { "插件 ${item.id} 的 name 不能为空" }
            require(item.version.isNotBlank()) { "插件 ${item.id} 的 version 不能为空" }
            require(item.apiVersion.isNotBlank()) { "插件 ${item.id} 的 apiVersion 不能为空" }
            require(item.downloadUrl.isNotBlank()) { "插件 ${item.id} 的 downloadUrl 不能为空" }
            requireHttpsUrl(item.downloadUrl, "插件 ${item.id} 的下载地址")
            require(SHA256_REGEX.matches(item.sha256)) { "插件 ${item.id} 的 sha256 无效" }
            require(item.sizeBytes > 0) { "插件 ${item.id} 的 sizeBytes 必须大于 0" }
        }
    }

    private fun localDefaultCatalogBytes(config: PluginCatalogConfig): ByteArray? {
        if (config.url.trim() != PluginCatalogConfig.DEFAULT_URL) return null
        val file = File(pluginDirPathProvider()).resolve("catalog.json")
        return file.takeIf { it.isFile }?.readBytes()
    }

    private fun CachedCatalog.expiresAt(config: PluginCatalogConfig): Long {
        return fetchedAtEpochMillis + config.cacheSeconds.coerceAtLeast(0) * 1000L
    }

    private companion object {
        private const val CATALOG_MAX_BYTES: Long = 2L * 1024L * 1024L
        private val PLUGIN_ID_REGEX = Regex("^[a-zA-Z0-9._-]+$")
        private val SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")
    }
}

public interface PluginCatalogDownloader {
    public fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray

    public fun downloadToFile(
        url: String,
        destination: File,
        timeoutSeconds: Double,
        maxBytes: Long,
    ): PluginCatalogDownloadResult
}

public data class PluginCatalogDownloadResult(
    val bytesRead: Long,
    val sha256: String,
)

public class UrlPluginCatalogDownloader : PluginCatalogDownloader {
    override fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray {
        val connection = openConnection(url, timeoutSeconds)
        connection.contentLengthLong
            .takeIf { it > maxBytes }
            ?.let { throw IllegalStateException("下载内容超过大小限制：size=$it，maxBytes=$maxBytes") }

        val output = ByteArrayOutputStream()
        connection.getInputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IllegalStateException("下载内容超过大小限制：maxBytes=$maxBytes")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    override fun downloadToFile(
        url: String,
        destination: File,
        timeoutSeconds: Double,
        maxBytes: Long,
    ): PluginCatalogDownloadResult {
        val connection = openConnection(url, timeoutSeconds)
        connection.contentLengthLong
            .takeIf { it > maxBytes }
            ?.let { throw IllegalStateException("插件文件超过大小限制：size=$it，maxBytes=$maxBytes") }

        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            connection.getInputStream().buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw IllegalStateException("插件文件超过大小限制：maxBytes=$maxBytes")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }

        return PluginCatalogDownloadResult(
            bytesRead = total,
            sha256 = digest.digest().toHex(),
        )
    }

    private fun openConnection(url: String, timeoutSeconds: Double): URLConnection {
        requireHttpsUrl(url, "下载地址")
        val connection = URI(url).toURL().openConnection()
        val timeout = timeoutMillis(timeoutSeconds)
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        return connection
    }
}

@Serializable
private data class PluginCatalogDocument(
    val schemaVersion: Int,
    val plugins: List<PluginCatalogItem> = emptyList(),
)

@Serializable
private data class PluginCatalogItem(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val apiVersion: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val homepageUrl: String? = null,
    val releaseNotesUrl: String? = null,
    val capabilities: List<String> = emptyList(),
)

private data class CachedCatalog(
    val url: String,
    val document: PluginCatalogDocument,
    val fetchedAtEpochMillis: Long,
)

private enum class PluginCatalogStatus {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    INCOMPATIBLE,
    DOWNLOAD_DISABLED,
}

private fun requireHttpsUrl(url: String, label: String) {
    val scheme = runCatching { URI(url).scheme }.getOrNull()
    require(scheme.equals("https", ignoreCase = true)) { "$label 必须使用 https://" }
}

private fun timeoutMillis(seconds: Double): Int {
    require(seconds > 0.0) { "下载超时必须大于 0 秒" }
    return (seconds * 1000.0)
        .coerceAtMost(Int.MAX_VALUE.toDouble())
        .roundToInt()
        .coerceAtLeast(1)
}

private fun comparePluginVersions(left: String, right: String): Int {
    val leftParts = left.versionParts()
    val rightParts = right.versionParts()
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val leftPart = leftParts.getOrElse(index) { "0" }
        val rightPart = rightParts.getOrElse(index) { "0" }
        val leftNumber = leftPart.toLongOrNull()
        val rightNumber = rightPart.toLongOrNull()
        val compared = if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            leftPart.compareTo(rightPart, ignoreCase = true)
        }
        if (compared != 0) return compared
    }
    return 0
}

private fun String.versionParts(): List<String> {
    return trim()
        .split('.', '-', '_')
        .filter { it.isNotBlank() }
}

private fun ByteArray.toHex(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte) }
}
