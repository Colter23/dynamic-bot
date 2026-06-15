package top.colter.dynamic.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import top.colter.dynamic.NetworkProxyConfig
import top.colter.dynamic.NetworkProxyType
import top.colter.dynamic.PluginCatalogConfig
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginInstallResult
import top.colter.dynamic.plugin.PluginScanner
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.roundToInt
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<PluginCatalogService>()

public class PluginCatalogService(
    private val configProvider: () -> PluginCatalogConfig,
    private val pluginProvider: () -> List<PluginInfo>,
    private val pluginDirPathProvider: () -> String = { "plugins" },
    private val pluginInstaller: (File, String, String, Boolean, Boolean) -> PluginInstallResult,
    private val proxyConfigProvider: () -> NetworkProxyConfig = { NetworkProxyConfig() },
    private val downloader: PluginCatalogDownloader = UrlPluginCatalogDownloader(proxyConfigProvider),
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
        val startedAt = clock()
        logger.info { "开始安装插件：pluginId=$normalizedId" }
        return synchronized(operationLock) {
            val config = configProvider()
            val item = loadCatalog(config, force = false).itemOrThrow(normalizedId)
            requireInstallable(item)
            require(pluginProvider().none { it.descriptor.id == normalizedId }) {
                "插件已安装，请使用更新操作：$normalizedId"
            }

            val jarFile = downloadAndVerifyJar(item, config)
            try {
                val result = pluginInstaller(jarFile, item.id, item.version, true, false)
                logger.info {
                    "插件安装完成：pluginId=${item.id}，version=${item.version}，elapsedMs=${clock() - startedAt}"
                }
                result.toOperationResponse(item)
            } catch (e: Throwable) {
                jarFile.delete()
                logger.warn(e) {
                    "插件安装失败：pluginId=${item.id}，version=${item.version}，elapsedMs=${clock() - startedAt}"
                }
                throw e
            }
        }
    }

    public fun update(pluginId: String): PluginCatalogOperationResponse {
        val normalizedId = pluginId.trim()
        require(normalizedId.isNotBlank()) { "插件 ID 不能为空" }
        val startedAt = clock()
        logger.info { "开始更新插件：pluginId=$normalizedId" }
        return synchronized(operationLock) {
            val config = configProvider()
            val installed = pluginProvider().firstOrNull { it.descriptor.id == normalizedId }
                ?: throw NoSuchElementException("插件未安装：$normalizedId")
            val item = loadCatalog(config, force = false).itemOrThrow(normalizedId)
            requireInstallable(item)
            require(comparePluginVersions(item.version, installed.descriptor.version) > 0) {
                "当前已是最新版本：$normalizedId，installed=${installed.descriptor.version}，catalog=${item.version}"
            }

            val jarFile = downloadAndVerifyJar(item, config)
            try {
                val result = pluginInstaller(jarFile, item.id, item.version, true, true)
                logger.info {
                    "插件更新完成：pluginId=${item.id}，version=${item.version}，elapsedMs=${clock() - startedAt}"
                }
                result.toOperationResponse(item)
            } catch (e: Throwable) {
                jarFile.delete()
                logger.warn(e) {
                    "插件更新失败：pluginId=${item.id}，version=${item.version}，elapsedMs=${clock() - startedAt}"
                }
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

            val startedAt = clock()
            logger.info {
                "开始获取插件目录：url=${redactUrlForLog(url)}，force=$force，timeoutSeconds=${config.downloadTimeoutSeconds}"
            }
            val content = try {
                CatalogContent(
                    bytes = downloader.downloadToByteArray(
                        url = url,
                        timeoutSeconds = config.downloadTimeoutSeconds,
                        maxBytes = CATALOG_MAX_BYTES,
                    ),
                    source = "REMOTE",
                    sourceUrl = url,
                    warning = null,
                )
            } catch (e: Exception) {
                logger.warn {
                    "远程插件目录获取失败：url=${redactUrlForLog(url)}，elapsedMs=${clock() - startedAt}，reason=${e.safeMessage()}"
                }
                localDefaultCatalogContent(config, e)
                    ?: throw IllegalStateException("插件目录获取失败：${e.safeMessage()}", e)
            }
            val text = content.bytes.toString(Charsets.UTF_8)
            val document = try {
                json.decodeFromString<PluginCatalogDocument>(text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("插件目录 JSON 格式无效：${e.safeMessage()}", e)
            }
            validateCatalog(document)
            CachedCatalog(
                url = url,
                schemaVersion = document.schemaVersion,
                entries = document.plugins.map { source -> resolveCatalogEntry(source, config) },
                fetchedAtEpochMillis = clock(),
                source = content.source,
                sourceUrl = content.sourceUrl,
                warning = content.warning,
            )
                .also {
                    cachedCatalog = it
                    logger.info {
                        "插件目录已加载：source=${it.source}，plugins=${document.plugins.size}，elapsedMs=${clock() - startedAt}"
                    }
                }
        }
    }

    private fun resolveCatalogEntry(
        source: PluginCatalogSourceItem,
        config: PluginCatalogConfig,
    ): CachedCatalogEntry {
        return try {
            CachedCatalogEntry(
                source = source,
                item = resolvePluginRelease(source, config),
                error = null,
            )
        } catch (e: Exception) {
            logger.warn { "插件发布信息解析失败：pluginId=${source.id}，reason=${e.safeMessage()}" }
            CachedCatalogEntry(
                source = source,
                item = null,
                error = e.safeMessage(),
            )
        }
    }

    private fun resolvePluginRelease(
        source: PluginCatalogSourceItem,
        config: PluginCatalogConfig,
    ): PluginCatalogItem {
        val releaseSource = source.release
        val release = when (releaseSource.provider.uppercase()) {
            "GITHUB_RELEASE" -> fetchGitHubRelease(releaseSource, config)
            else -> error("不支持的插件发布源：${releaseSource.provider}")
        }
        val asset = release.assets
            .firstOrNull { asset -> wildcardMatches(releaseSource.assetPattern, asset.name) }
            ?: error("GitHub Release 中未找到匹配插件文件：repo=${releaseSource.repository}，pattern=${releaseSource.assetPattern}")
        require(asset.name.endsWith(".jar", ignoreCase = true)) {
            "匹配到的插件文件不是 Jar：repo=${releaseSource.repository}，asset=${asset.name}"
        }
        require(asset.size > 0) { "插件文件大小无效：repo=${releaseSource.repository}，asset=${asset.name}" }
        requireHttpsUrl(asset.browserDownloadUrl, "插件下载地址")
        val version = release.tagName.trim()
            .takeIf { it.isNotBlank() }
            ?.removePrefix("v")
            ?: error("GitHub Release 缺少 tag_name：repo=${releaseSource.repository}")
        val sha256 = asset.digest.sha256FromDigest()
            ?: fetchReleaseChecksum(releaseSource, release, asset, config)

        return PluginCatalogItem(
            id = source.id,
            name = source.name,
            version = version,
            description = source.description,
            apiVersion = CORE_PLUGIN_API_VERSION,
            downloadUrl = asset.browserDownloadUrl,
            sha256 = sha256,
            sizeBytes = asset.size,
            homepageUrl = source.homepageUrl ?: "https://github.com/${releaseSource.repository}",
            releaseNotesUrl = source.releaseNotesUrl ?: release.htmlUrl,
            capabilities = source.capabilities,
        )
    }

    private fun fetchGitHubRelease(
        source: PluginReleaseSource,
        config: PluginCatalogConfig,
    ): GitHubReleaseResponse {
        val releasePath = source.tag
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("latest", ignoreCase = true) }
            ?.let { "tags/${urlPathSegment(it)}" }
            ?: "latest"
        val url = "https://api.github.com/repos/${source.repository}/releases/$releasePath"
        logger.debug { "开始获取 GitHub Release：repo=${source.repository}，url=${redactUrlForLog(url)}" }
        val bytes = try {
            downloader.downloadToByteArray(
                url = url,
                timeoutSeconds = config.downloadTimeoutSeconds,
                maxBytes = GITHUB_RELEASE_MAX_BYTES,
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "GitHub Release 获取失败：repo=${source.repository}，${e.safeMessage()}",
                e,
            )
        }
        return try {
            json.decodeFromString<GitHubReleaseResponse>(bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("GitHub Release JSON 格式无效：repo=${source.repository}，${e.safeMessage()}", e)
        }
    }

    private fun fetchReleaseChecksum(
        source: PluginReleaseSource,
        release: GitHubReleaseResponse,
        jarAsset: GitHubReleaseAsset,
        config: PluginCatalogConfig,
    ): String {
        val patterns = buildList {
            source.checksumAssetPattern
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            add("${jarAsset.name}.sha256")
            add("${jarAsset.name}.sha256sum")
            add("SHA256SUMS")
            add("sha256.txt")
            add("*.sha256")
        }
        val checksumAsset = patterns
            .asSequence()
            .mapNotNull { pattern -> release.assets.firstOrNull { asset -> wildcardMatches(pattern, asset.name) } }
            .firstOrNull()
            ?: error("插件文件缺少 sha256 摘要：repo=${source.repository}，asset=${jarAsset.name}")
        requireHttpsUrl(checksumAsset.browserDownloadUrl, "sha256 摘要下载地址")
        val text = downloader.downloadToByteArray(
            url = checksumAsset.browserDownloadUrl,
            timeoutSeconds = config.downloadTimeoutSeconds,
            maxBytes = CHECKSUM_MAX_BYTES,
        ).toString(Charsets.UTF_8)
        return parseSha256(text, jarAsset.name)
            ?: error("sha256 摘要格式无效：repo=${source.repository}，asset=${checksumAsset.name}")
    }

    private fun downloadAndVerifyJar(item: PluginCatalogItem, config: PluginCatalogConfig): File {
        require(item.sizeBytes <= config.maxDownloadBytes) {
            "插件文件超过大小限制：pluginId=${item.id}，size=${item.sizeBytes}，maxBytes=${config.maxDownloadBytes}"
        }
        val downloadDir = File(pluginDirPathProvider()).resolve(".downloads")
        try {
            Files.createDirectories(downloadDir.toPath())
        } catch (e: Exception) {
            throw IllegalStateException(
                "无法创建插件下载目录：path=${downloadDir.absolutePath}，请检查目录权限",
                e,
            )
        }
        if (!downloadDir.canWrite()) {
            throw IllegalStateException(
                "插件下载目录不可写：path=${downloadDir.absolutePath}，请检查目录权限"
            )
        }
        val jarFile = downloadDir.resolve("${item.id}-${item.version}.jar.tmp")
        jarFile.delete()

        return try {
            val startedAt = clock()
            logger.info {
                "开始下载插件 Jar：pluginId=${item.id}，version=${item.version}，sizeBytes=${item.sizeBytes}，timeoutSeconds=${config.downloadTimeoutSeconds}"
            }
            val result = try {
                downloader.downloadToFile(
                    url = item.downloadUrl,
                    destination = jarFile,
                    timeoutSeconds = config.downloadTimeoutSeconds,
                    maxBytes = config.maxDownloadBytes,
                )
            } catch (e: Exception) {
                logger.warn {
                    "插件 Jar 下载失败：pluginId=${item.id}，version=${item.version}，elapsedMs=${clock() - startedAt}，reason=${e.safeMessage()}"
                }
                throw IllegalStateException("插件下载失败：pluginId=${item.id}，${e.safeMessage()}", e)
            }
            logger.info {
                "插件 Jar 下载完成：pluginId=${item.id}，version=${item.version}，bytes=${result.bytesRead}，elapsedMs=${clock() - startedAt}"
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
            logger.info { "插件 Jar 校验通过：pluginId=${item.id}，version=${item.version}" }
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
        require(SHA256_REGEX.matches(item.sha256)) { "插件 ${item.id} 的 sha256 无效" }
    }

    private fun CachedCatalog.toResponse(
        installedPlugins: List<PluginInfo>,
        config: PluginCatalogConfig,
    ): PluginCatalogResponse {
        val installedById = installedPlugins.associateBy { it.descriptor.id }
        return PluginCatalogResponse(
            schemaVersion = schemaVersion,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            cacheExpiresAtEpochMillis = expiresAt(config),
            source = source,
            sourceUrl = sourceUrl,
            warning = warning,
            plugins = entries
                .map { entry -> entry.toDto(installedById[entry.source.id]) }
                .sortedBy { it.id },
        )
    }

    private fun CachedCatalog.itemOrThrow(pluginId: String): PluginCatalogItem {
        val entry = entries.firstOrNull { it.source.id == pluginId }
            ?: throw NoSuchElementException("插件目录中未找到插件：$pluginId")
        return entry.item ?: throw IllegalStateException("插件发布信息解析失败：pluginId=$pluginId，${entry.error ?: "未知错误"}")
    }

    private fun CachedCatalogEntry.toDto(installed: PluginInfo?): PluginCatalogEntryDto {
        val resolved = item
        if (resolved == null) {
            return PluginCatalogEntryDto(
                id = source.id,
                name = source.name,
                version = "",
                description = source.description,
                apiVersion = "",
                downloadUrl = "",
                sha256 = "",
                sizeBytes = 0,
                homepageUrl = source.homepageUrl ?: "https://github.com/${source.release.repository}",
                releaseNotesUrl = source.releaseNotesUrl,
                capabilities = source.capabilities.sorted(),
                installedVersion = installed?.descriptor?.version,
                installedState = installed?.state?.name,
                catalogStatus = PluginCatalogStatus.RESOLVE_FAILED.name,
                updateAvailable = false,
                error = error,
            )
        }
        return resolved.toDto(installed)
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
                else -> null
            },
        )
    }

    private fun PluginCatalogItem.catalogStatus(installed: PluginInfo?): PluginCatalogStatus {
        if (apiVersion != CORE_PLUGIN_API_VERSION) return PluginCatalogStatus.INCOMPATIBLE
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
        require(document.schemaVersion == 2) { "插件目录 schemaVersion 不支持：${document.schemaVersion}" }
        val ids = mutableSetOf<String>()
        document.plugins.forEach { item ->
            require(PLUGIN_ID_REGEX.matches(item.id)) { "插件目录包含非法插件 ID：${item.id}" }
            require(ids.add(item.id)) { "插件目录包含重复插件 ID：${item.id}" }
            require(item.name.isNotBlank()) { "插件 ${item.id} 的 name 不能为空" }
            item.homepageUrl?.takeIf { it.isNotBlank() }?.let { requireHttpUrl(it, "插件 ${item.id} 的主页地址") }
            item.releaseNotesUrl?.takeIf { it.isNotBlank() }?.let { requireHttpUrl(it, "插件 ${item.id} 的更新说明地址") }
            validateReleaseSource(item.id, item.release)
        }
    }

    private fun validateReleaseSource(pluginId: String, source: PluginReleaseSource) {
        require(source.provider.equals("GITHUB_RELEASE", ignoreCase = true)) {
            "插件 $pluginId 的发布源只支持 GITHUB_RELEASE"
        }
        require(GITHUB_REPOSITORY_REGEX.matches(source.repository)) {
            "插件 $pluginId 的 GitHub 仓库格式无效，应为 owner/repo"
        }
        require(source.repository.split('/').none { it == "." || it == ".." }) {
            "插件 $pluginId 的 GitHub 仓库格式无效，应为 owner/repo"
        }
        require(source.assetPattern.isNotBlank()) { "插件 $pluginId 的 assetPattern 不能为空" }
        source.checksumAssetPattern
            ?.takeIf { it.isBlank() }
            ?.let { throw IllegalArgumentException("插件 $pluginId 的 checksumAssetPattern 不能为空字符串") }
    }

    private fun localDefaultCatalogContent(config: PluginCatalogConfig, cause: Exception): CatalogContent? {
        if (config.url.trim() != PluginCatalogConfig.DEFAULT_URL) return null
        val file = File(pluginDirPathProvider()).resolve("catalog.json")
        if (!file.isFile) return null
        val causeText = cause.safeMessage()
        return CatalogContent(
            bytes = file.readBytes(),
            source = "LOCAL_FALLBACK",
            sourceUrl = file.absolutePath,
            warning = "远程插件目录获取失败，已使用本地插件目录：$causeText",
        )
    }

    private fun CachedCatalog.expiresAt(config: PluginCatalogConfig): Long {
        return fetchedAtEpochMillis + config.cacheSeconds.coerceAtLeast(0) * 1000L
    }

    private companion object {
        private const val CATALOG_MAX_BYTES: Long = 2L * 1024L * 1024L
        private const val GITHUB_RELEASE_MAX_BYTES: Long = 512L * 1024L
        private const val CHECKSUM_MAX_BYTES: Long = 64L * 1024L
        private val PLUGIN_ID_REGEX = Regex("^[a-zA-Z0-9._-]+$")
        private val GITHUB_REPOSITORY_REGEX = Regex("^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$")
        private val SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")
        private val SHA256_FIND_REGEX = Regex("[a-fA-F0-9]{64}")
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

public class UrlPluginCatalogDownloader(
    private val proxyConfigProvider: () -> NetworkProxyConfig = { NetworkProxyConfig() },
) : PluginCatalogDownloader {
    override fun downloadToByteArray(url: String, timeoutSeconds: Double, maxBytes: Long): ByteArray {
        val deadline = DownloadDeadline(timeoutSeconds)
        val logUrl = redactUrlForLog(url)
        val connection = openConnection(url, deadline.timeoutMs)
        connection.contentLengthLong
            .takeIf { it > maxBytes }
            ?.let { throw IllegalStateException("下载内容超过大小限制：size=$it，maxBytes=$maxBytes") }

        val output = ByteArrayOutputStream()
        connection.getInputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            var lastProgressAt = deadline.startedAtMillis
            while (true) {
                deadline.check(logUrl, total)
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IllegalStateException("下载内容超过大小限制：maxBytes=$maxBytes")
                }
                output.write(buffer, 0, read)
                lastProgressAt = logDownloadProgress(
                    url = logUrl,
                    bytesRead = total,
                    contentLength = connection.contentLengthLong,
                    startedAtMillis = deadline.startedAtMillis,
                    lastProgressAtMillis = lastProgressAt,
                )
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
        val deadline = DownloadDeadline(timeoutSeconds)
        val logUrl = redactUrlForLog(url)
        val connection = openConnection(url, deadline.timeoutMs)
        connection.contentLengthLong
            .takeIf { it > maxBytes }
            ?.let { throw IllegalStateException("插件文件超过大小限制：size=$it，maxBytes=$maxBytes") }

        destination.parentFile?.toPath()?.let { Files.createDirectories(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            connection.getInputStream().buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastProgressAt = deadline.startedAtMillis
                    while (true) {
                        deadline.check(logUrl, total)
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw IllegalStateException("插件文件超过大小限制：maxBytes=$maxBytes")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        lastProgressAt = logDownloadProgress(
                            url = logUrl,
                            bytesRead = total,
                            contentLength = connection.contentLengthLong,
                            startedAtMillis = deadline.startedAtMillis,
                            lastProgressAtMillis = lastProgressAt,
                        )
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

    private fun openConnection(url: String, totalTimeoutMillis: Int): URLConnection {
        requireHttpsUrl(url, "下载地址")
        val proxy = proxyConfigProvider().toJavaProxy()
        val connection = if (proxy == Proxy.NO_PROXY) {
            URI(url).toURL().openConnection()
        } else {
            URI(url).toURL().openConnection(proxy)
        }
        val connectTimeout = totalTimeoutMillis.coerceAtMost(MAX_CONNECT_TIMEOUT_MS).coerceAtLeast(1)
        val idleTimeout = totalTimeoutMillis.coerceAtMost(MAX_READ_IDLE_TIMEOUT_MS).coerceAtLeast(1)
        connection.connectTimeout = connectTimeout
        connection.readTimeout = idleTimeout
        connection.setRequestProperty("User-Agent", "dynamic-bot-plugin-catalog")
        connection.setRequestProperty("Accept", "application/vnd.github+json,application/json,*/*")
        return connection
    }

    private fun NetworkProxyConfig.toJavaProxy(): Proxy {
        if (!enabled) return Proxy.NO_PROXY
        val normalizedHost = host.trim()
        require(normalizedHost.isNotBlank()) { "启用网络代理时代理主机不能为空" }
        require(port in 1..65_535) { "网络代理端口必须在 1 到 65535 之间" }
        val proxyType = when (type) {
            NetworkProxyType.HTTP -> Proxy.Type.HTTP
            NetworkProxyType.SOCKS -> Proxy.Type.SOCKS
        }
        return Proxy(proxyType, InetSocketAddress.createUnresolved(normalizedHost, port))
    }

    private fun logDownloadProgress(
        url: String,
        bytesRead: Long,
        contentLength: Long,
        startedAtMillis: Long,
        lastProgressAtMillis: Long,
    ): Long {
        val now = System.currentTimeMillis()
        if (now - lastProgressAtMillis < DOWNLOAD_PROGRESS_LOG_INTERVAL_MS) return lastProgressAtMillis
        val percent = contentLength
            .takeIf { it > 0 }
            ?.let { "%.1f%%".format(bytesRead.toDouble() * 100.0 / it.toDouble()) }
            ?: "未知"
        logger.info {
            "插件下载进行中：url=$url，bytes=$bytesRead，contentLength=$contentLength，percent=$percent，elapsedMs=${now - startedAtMillis}"
        }
        return now
    }

    private class DownloadDeadline(timeoutSeconds: Double) {
        val timeoutMs: Int = timeoutMillis(timeoutSeconds)
        val startedAtMillis: Long = System.currentTimeMillis()

        fun check(url: String, bytesRead: Long) {
            val elapsed = System.currentTimeMillis() - startedAtMillis
            if (elapsed > timeoutMs) {
                throw SocketTimeoutException(
                    "下载总耗时超过限制：url=$url，timeoutMs=$timeoutMs，elapsedMs=$elapsed，bytesRead=$bytesRead",
                )
            }
        }
    }

    private companion object {
        private const val DOWNLOAD_PROGRESS_LOG_INTERVAL_MS: Long = 5_000
        private const val MAX_CONNECT_TIMEOUT_MS: Int = 10_000
        private const val MAX_READ_IDLE_TIMEOUT_MS: Int = 5_000
    }
}

@Serializable
private data class PluginCatalogDocument(
    val schemaVersion: Int,
    val plugins: List<PluginCatalogSourceItem> = emptyList(),
)

@Serializable
private data class PluginCatalogSourceItem(
    val id: String,
    val name: String,
    val description: String = "",
    val release: PluginReleaseSource,
    val homepageUrl: String? = null,
    val releaseNotesUrl: String? = null,
    val capabilities: List<String> = emptyList(),
)

@Serializable
private data class PluginReleaseSource(
    val provider: String = "GITHUB_RELEASE",
    val repository: String,
    val tag: String? = null,
    val assetPattern: String,
    val checksumAssetPattern: String? = null,
)

@Serializable
private data class GitHubReleaseResponse(
    @SerialName("tag_name")
    val tagName: String = "",
    @SerialName("html_url")
    val htmlUrl: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
    val size: Long = 0,
    val digest: String? = null,
)

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
    val schemaVersion: Int,
    val entries: List<CachedCatalogEntry>,
    val fetchedAtEpochMillis: Long,
    val source: String,
    val sourceUrl: String?,
    val warning: String?,
)

private data class CachedCatalogEntry(
    val source: PluginCatalogSourceItem,
    val item: PluginCatalogItem?,
    val error: String?,
)

private data class CatalogContent(
    val bytes: ByteArray,
    val source: String,
    val sourceUrl: String?,
    val warning: String?,
)

private enum class PluginCatalogStatus {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    INCOMPATIBLE,
    RESOLVE_FAILED,
}

private fun requireHttpsUrl(url: String, label: String) {
    val scheme = runCatching { URI(url).scheme }.getOrNull()
    require(scheme.equals("https", ignoreCase = true)) { "$label 必须使用 https://" }
}

private fun requireHttpUrl(url: String, label: String) {
    val scheme = runCatching { URI(url).scheme }.getOrNull()
    require(scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)) {
        "$label 必须使用 http:// 或 https://"
    }
}

private fun Throwable.safeMessage(): String {
    return (message ?: this::class.simpleName ?: "未知错误").redactUrlsForLog()
}

private fun String.redactUrlsForLog(): String {
    return replace(Regex("""https?://[^\s，,。；;）)]+""")) { result ->
        redactUrlForLog(result.value)
    }
}

private fun redactUrlForLog(url: String): String {
    return runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: return@runCatching url.withoutQueryAndFragment()
        val authority = (uri.rawAuthority ?: "")
            .substringAfterLast("@")
            .ifBlank { uri.host.orEmpty() + uri.portSuffix() }
        val path = uri.rawPath.orEmpty()
        val redacted = if (uri.rawQuery != null || uri.rawFragment != null) "?<hidden>" else ""
        "$scheme://$authority$path$redacted"
    }.getOrElse {
        url.withoutQueryAndFragment()
    }
}

private fun URI.portSuffix(): String {
    return if (port >= 0) ":$port" else ""
}

private fun String.withoutQueryAndFragment(): String {
    return substringBefore('?').substringBefore('#')
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

private fun String?.sha256FromDigest(): String? {
    val value = this
        ?.trim()
        ?.removePrefix("sha256:")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return value.takeIf { Regex("^[a-fA-F0-9]{64}$").matches(it) }
}

private fun parseSha256(text: String, jarName: String): String? {
    val regex = Regex("[a-fA-F0-9]{64}")
    val jarLine = text
        .lineSequence()
        .firstOrNull { line -> line.contains(jarName) && regex.containsMatchIn(line) }
    return regex.find(jarLine ?: text)?.value
}

private fun wildcardMatches(pattern: String, value: String): Boolean {
    val regex = pattern
        .trim()
        .split('*')
        .joinToString(".*") { Regex.escape(it) }
        .let { "^$it$" }
        .toRegex(RegexOption.IGNORE_CASE)
    return regex.matches(value)
}

private fun urlPathSegment(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
}
