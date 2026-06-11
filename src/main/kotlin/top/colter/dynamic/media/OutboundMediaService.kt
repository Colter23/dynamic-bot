package top.colter.dynamic.media

import io.ktor.http.ContentType
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.MediaDeliveryConfig
import top.colter.dynamic.MediaDeliveryPathMapping
import top.colter.dynamic.MediaDeliveryProfile
import top.colter.dynamic.MediaDeliveryType
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdvice
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdviceRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdvisor
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryConfidence
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryMethod
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeStatus
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<OutboundMediaService>()

public class OutboundMediaService(
    private val configProvider: () -> MainDynamicConfig,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val probeCache = ConcurrentHashMap<ProbeCacheKey, CachedProbeResult>()
    private val selectionLogCache = ConcurrentHashMap<String, Boolean>()

    public suspend fun rewriteMessage(
        message: Message,
        profileId: String? = null,
        routeContext: OutboundMediaRouteContext = OutboundMediaRouteContext(),
    ): Message {
        val session = RewriteSession(configProvider(), profileId, routeContext)
        val rewritten = session.rewriteBatches(message.batches)
        return if (rewritten == message.batches) message else message.copy(batches = rewritten)
    }

    public suspend fun rewriteBatches(
        batches: List<MessageBatch>,
        profileId: String? = null,
        routeContext: OutboundMediaRouteContext = OutboundMediaRouteContext(),
    ): List<MessageBatch> {
        return RewriteSession(configProvider(), profileId, routeContext).rewriteBatches(batches)
    }

    public suspend fun rewriteMedia(
        media: MediaRef,
        profileId: String? = null,
        routeContext: OutboundMediaRouteContext = OutboundMediaRouteContext(),
    ): MediaRef {
        return RewriteSession(configProvider(), profileId, routeContext).rewriteMedia(media)
    }

    public fun invalidateRoute(routeContext: OutboundMediaRouteContext) {
        val routeKey = routeContext.routeCacheKey() ?: return
        probeCache.keys.removeIf { it.routeKey == routeKey }
    }

    public fun resolve(profileId: String?, id: String, expires: Long, signature: String): OutboundMediaResult {
        val config = configProvider()
        val profile = config.mediaDelivery.resolveProfile(profileId)
            ?: throw NoSuchElementException("媒体交付 profile 不存在：${profileId.orEmpty()}")
        val secret = profile.signedUrl.signingSecret
        require(secret.isNotBlank()) { "媒体交付 profile 签名密钥未配置：${profile.id}" }
        require(expires >= nowEpochSeconds()) { "媒体链接已过期" }
        require(secureEquals(signature, sign(secret, profile.id, id, expires))) { "媒体链接签名无效" }

        val decoded = decodeMediaId(id)
        val root = allowedRoots(config).firstOrNull { it.key == decoded.rootKey }
            ?: throw NoSuchElementException("媒体根目录不存在：${decoded.rootKey}")
        val path = root.path.resolve(decoded.relativePath).normalize()
        require(path.startsWith(root.path)) { "媒体路径无效" }
        val realRoot = root.path.realPathOrSelf()
            ?: throw IllegalArgumentException("媒体根目录无法解析真实路径")
        val realPath = path.realPathOrSelf()
            ?: throw IllegalArgumentException("媒体路径无法解析真实路径")
        require(realPath.startsWith(realRoot)) { "媒体路径不在允许目录内" }
        require(Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) { "媒体文件不存在" }
        require(!Files.isSymbolicLink(realPath)) { "不允许访问符号链接" }

        val size = Files.size(realPath)
        require(size <= root.maxBytes) {
            "媒体文件超过大小限制：size=$size，maxBytes=${root.maxBytes}"
        }
        touchAccessTime(realPath)
        val cacheMaxAgeSeconds = (expires - nowEpochSeconds()).coerceAtLeast(0)
        return when (root.type) {
            OutboundMediaRootType.IMAGE -> {
                val contentType = imageContentType(realPath, readHeader(realPath))
                    ?: throw IllegalArgumentException("不支持的媒体类型：${realPath.fileName}")
                OutboundMediaResult(
                    file = realPath.toFile(),
                    contentType = contentType,
                    cacheMaxAgeSeconds = cacheMaxAgeSeconds,
                )
            }
            OutboundMediaRootType.VIDEO -> {
                val contentType = videoContentType(realPath)
                    ?: throw IllegalArgumentException("不支持的视频类型：${realPath.fileName}")
                OutboundMediaResult(
                    file = realPath.toFile(),
                    contentType = contentType,
                    cacheMaxAgeSeconds = cacheMaxAgeSeconds,
                )
            }
        }
    }

    public fun resolveProbe(profileId: String?, expires: Long, signature: String): OutboundMediaResult {
        val profile = configProvider().mediaDelivery.resolveProfile(profileId)
            ?: throw NoSuchElementException("媒体交付 profile 不存在：${profileId.orEmpty()}")
        val secret = profile.signedUrl.signingSecret
        require(secret.isNotBlank()) { "媒体交付 profile 签名密钥未配置：${profile.id}" }
        require(expires >= nowEpochSeconds()) { "媒体探测链接已过期" }
        require(secureEquals(signature, sign(secret, profile.id, PROBE_MEDIA_ID, expires))) {
            "媒体探测链接签名无效"
        }
        return OutboundMediaResult(
            bytes = PROBE_PNG,
            contentType = ContentType.Image.PNG,
            cacheMaxAgeSeconds = (expires - nowEpochSeconds()).coerceAtLeast(0),
        )
    }

    private inner class RewriteSession(
        private val config: MainDynamicConfig,
        private val profileId: String?,
        private val routeContext: OutboundMediaRouteContext,
    ) {
        private val profile: MediaDeliveryProfile? = config.mediaDelivery.profile(profileId)
        private var adviceLoaded = false
        private var advice: MessageSinkMediaDeliveryAdvice = MessageSinkMediaDeliveryAdvice()

        suspend fun rewriteBatches(batches: List<MessageBatch>): List<MessageBatch> {
            val rewritten = batches.map { batch ->
                val content = batch.content.map { rewriteContent(it) }
                if (content == batch.content) batch else batch.copy(content = content)
            }
            return if (rewritten == batches) batches else rewritten
        }

        suspend fun rewriteMedia(media: MediaRef): MediaRef {
            val selectedProfile = profile ?: return media
            val uri = media.uri.trim()
            if (uri.isBlank() || uri.isRemoteOrEncoded()) return media
            val path = uri.localPath() ?: return media
            val normalizedPath = path.toAbsolutePath().normalize()
            if (!Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS)) return media

            val root = findAllowedRoot(normalizedPath, config) ?: return media
            val size = runCatching { Files.size(normalizedPath) }.getOrElse { return media }
            if (size > root.maxBytes) return media

            val rewritten = when (selectedProfile.type) {
                MediaDeliveryType.AUTO -> rewriteAuto(media, normalizedPath, root, size, selectedProfile)
                MediaDeliveryType.BASE64 -> rewriteAsBase64(media, normalizedPath, root, selectedProfile)
                MediaDeliveryType.LOCAL_FILE -> rewriteAsLocalFile(media, normalizedPath, selectedProfile, explicit = true)
                MediaDeliveryType.SIGNED_URL -> rewriteAsSignedUrl(
                    media = media,
                    path = normalizedPath,
                    root = root,
                    profile = selectedProfile,
                    baseUrl = selectedProfile.signedUrl.publicBaseUrl,
                )
            }
            return rewritten ?: media
        }

        private suspend fun rewriteContent(content: MessageContent): MessageContent {
            return when (content) {
                is MessageContent.Image -> {
                    val rewritten = rewriteMedia(content.image)
                    if (rewritten == content.image) content else content.copy(image = rewritten)
                }
                is MessageContent.Video -> {
                    val rewritten = rewriteMedia(content.video)
                    if (rewritten == content.video) content else content.copy(video = rewritten)
                }
                is MessageContent.Audio -> {
                    val rewritten = rewriteMedia(content.audio)
                    if (rewritten == content.audio) content else content.copy(audio = rewritten)
                }
                is MessageContent.Forward -> {
                    val rewrittenNodes = content.nodes.map { node ->
                        val rewrittenBatches = rewriteBatches(node.batches)
                        if (rewrittenBatches == node.batches) node else node.copy(batches = rewrittenBatches)
                    }
                    if (rewrittenNodes == content.nodes) content else content.copy(nodes = rewrittenNodes)
                }
                else -> content
            }
        }

        private suspend fun rewriteAuto(
            media: MediaRef,
            path: Path,
            root: OutboundMediaRoot,
            sizeBytes: Long,
            profile: MediaDeliveryProfile,
        ): MediaRef? {
            rewriteAsAutoLocalFile(media, path, root, sizeBytes, profile)?.let { return it }
            rewriteAsAutoSignedUrl(media, path, root, sizeBytes, profile)?.let { return it }
            return rewriteAsBase64(media, path, root, profile)
        }

        private suspend fun rewriteAsAutoLocalFile(
            media: MediaRef,
            path: Path,
            root: OutboundMediaRoot,
            sizeBytes: Long,
            profile: MediaDeliveryProfile,
        ): MediaRef? {
            val local = rewriteAsLocalFile(media, path, profile, explicit = false) ?: return null
            val currentAdvice = advice()
            if (currentAdvice.localFileConfidence == MessageSinkMediaDeliveryConfidence.UNAVAILABLE) return null

            val probeUri = localFileProbeUri(root) ?: local.uri
            val probeStatus = probe(
                method = MessageSinkMediaDeliveryMethod.LOCAL_FILE,
                uri = probeUri,
                cacheTarget = root.key,
                media = media.copy(uri = probeUri),
                sizeBytes = if (probeUri == local.uri) sizeBytes else PROBE_PNG.size.toLong(),
                profile = profile,
            )
            val usable = when (currentAdvice.localFileConfidence) {
                MessageSinkMediaDeliveryConfidence.CONFIRMED,
                MessageSinkMediaDeliveryConfidence.LIKELY -> probeStatus != MessageSinkMediaDeliveryProbeStatus.UNAVAILABLE
                MessageSinkMediaDeliveryConfidence.UNKNOWN -> probeStatus == MessageSinkMediaDeliveryProbeStatus.AVAILABLE
                MessageSinkMediaDeliveryConfidence.UNAVAILABLE -> false
            }
            if (!usable) return null
            logSelection(profile, "本地文件", routeContext)
            return local
        }

        private suspend fun rewriteAsAutoSignedUrl(
            media: MediaRef,
            path: Path,
            root: OutboundMediaRoot,
            sizeBytes: Long,
            profile: MediaDeliveryProfile,
        ): MediaRef? {
            val candidates = autoBaseUrlCandidates(config, advice())
            for (baseUrl in candidates) {
                val probeUrl = probeSignedUrl(baseUrl, profile) ?: continue
                val probeStatus = probe(
                    method = MessageSinkMediaDeliveryMethod.SIGNED_URL,
                    uri = probeUrl,
                    cacheTarget = baseUrl,
                    media = media,
                    sizeBytes = sizeBytes,
                    profile = profile,
                )
                if (probeStatus != MessageSinkMediaDeliveryProbeStatus.AVAILABLE) continue
                val signed = rewriteAsSignedUrl(media, path, root, profile, baseUrl) ?: continue
                logSelection(profile, "签名链接", routeContext)
                return signed
            }
            return null
        }

        private fun rewriteAsBase64(
            media: MediaRef,
            path: Path,
            root: OutboundMediaRoot,
            profile: MediaDeliveryProfile,
        ): MediaRef? {
            if (root.type != OutboundMediaRootType.IMAGE) return null
            val maxBytes = profile.base64Fallback.maxBytes
            if (maxBytes <= 0) return null
            val size = runCatching { Files.size(path) }.getOrElse { return null }
            // Base64 编码会膨胀约 33%，检查编码后的大小
            val encodedSize = (size * 4 + 2) / 3  // 向上取整
            if (encodedSize > maxBytes) return null
            val bytes = runCatching { Files.readAllBytes(path) }.getOrElse { return null }
            logSelection(profile, "Base64 兜底", routeContext)
            return media.copy(uri = "base64://${Base64.getEncoder().encodeToString(bytes)}")
        }

        private fun rewriteAsLocalFile(
            media: MediaRef,
            path: Path,
            profile: MediaDeliveryProfile,
            explicit: Boolean,
        ): MediaRef? {
            val mappedPath = if (explicit) {
                profile.localFile.pathMappings
                    .asSequence()
                    .filter { it.enabled }
                    .mapNotNull { mapping -> mapping.clientPathFor(path) }
                    .firstOrNull()
                    ?: path
            } else {
                path
            }
            return media.copy(uri = mappedPath.toUri().toString())
        }

        private fun rewriteAsSignedUrl(
            media: MediaRef,
            path: Path,
            root: OutboundMediaRoot,
            profile: MediaDeliveryProfile,
            baseUrl: String,
        ): MediaRef? {
            val normalizedBaseUrl = baseUrl.normalizedBaseUrl() ?: return null
            val secret = profile.signedUrl.signingSecret
            if (secret.isBlank()) return null

            val relative = root.path.relativize(path).portableString()
            val id = mediaId(root.key, relative)
            val expires = nowEpochSeconds() + profile.signedUrl.ttlSeconds.coerceAtLeast(1)
            val signature = sign(secret, profile.id, id, expires)
            val url = "$normalizedBaseUrl/media/outbound/${id.urlComponent()}" +
                "?profile=${profile.id.urlComponent()}&expires=$expires&sig=${signature.urlComponent()}"
            return media.copy(uri = url)
        }

        private suspend fun advice(): MessageSinkMediaDeliveryAdvice {
            if (adviceLoaded) return advice
            adviceLoaded = true
            val advisor = routeContext.advisor ?: return advice
            advice = runCatching {
                advisor.adviseMediaDelivery(
                    MessageSinkMediaDeliveryAdviceRequest(
                        routeId = routeContext.routeId,
                        accountId = routeContext.accountId,
                        webAdminEnabled = config.webAdmin.enabled,
                        webAdminHost = config.webAdmin.host,
                        webAdminPort = config.webAdmin.port,
                    )
                )
            }.onFailure {
                logger.debug(it) {
                    "媒体交付建议读取失败：route=${routeContext.routeCacheKey().orEmpty()}"
                }
            }.getOrDefault(MessageSinkMediaDeliveryAdvice())
            return advice
        }

        private suspend fun probe(
            method: MessageSinkMediaDeliveryMethod,
            uri: String,
            cacheTarget: String,
            media: MediaRef,
            sizeBytes: Long,
            profile: MediaDeliveryProfile,
        ): MessageSinkMediaDeliveryProbeStatus {
            val advisor = routeContext.advisor ?: return MessageSinkMediaDeliveryProbeStatus.UNKNOWN
            val routeKey = routeContext.routeCacheKey() ?: return MessageSinkMediaDeliveryProbeStatus.UNKNOWN
            val key = ProbeCacheKey(
                routeKey = routeKey,
                profileId = profile.id,
                method = method,
                target = cacheTarget,
            )
            val now = nowEpochSeconds()
            probeCache[key]?.takeIf { it.expiresAtEpochSeconds > now }?.let { return it.status }

            val result = runCatching {
                advisor.probeMediaDelivery(
                    MessageSinkMediaDeliveryProbeRequest(
                        routeId = routeContext.routeId,
                        accountId = routeContext.accountId,
                        method = method,
                        uri = uri,
                        media = media,
                        sizeBytes = sizeBytes,
                    )
                )
            }.onFailure {
                logger.debug(it) {
                    "媒体交付探测异常：route=$routeKey method=$method target=$cacheTarget"
                }
            }.getOrElse {
                top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeResult.unknown(it.message.orEmpty())
            }
            val ttlMinutes = when (result.status) {
                MessageSinkMediaDeliveryProbeStatus.AVAILABLE -> profile.auto.probeCacheMinutes
                MessageSinkMediaDeliveryProbeStatus.UNAVAILABLE,
                MessageSinkMediaDeliveryProbeStatus.UNKNOWN -> profile.auto.failedProbeCacheMinutes
            }.coerceAtLeast(1)
            probeCache[key] = CachedProbeResult(
                status = result.status,
                reason = result.reason,
                expiresAtEpochSeconds = now + ttlMinutes * 60L,
            )
            if (result.status != MessageSinkMediaDeliveryProbeStatus.AVAILABLE && result.reason.isNotBlank()) {
                logger.debug {
                    "媒体交付探测未通过：route=$routeKey method=$method target=$cacheTarget reason=${result.reason}"
                }
            }
            return result.status
        }
    }

    private fun probeSignedUrl(baseUrl: String, profile: MediaDeliveryProfile): String? {
        val normalizedBaseUrl = baseUrl.normalizedBaseUrl() ?: return null
        val secret = profile.signedUrl.signingSecret
        if (secret.isBlank()) return null
        val expires = nowEpochSeconds() + profile.signedUrl.ttlSeconds.coerceAtLeast(1)
        val signature = sign(secret, profile.id, PROBE_MEDIA_ID, expires)
        return "$normalizedBaseUrl/media/outbound-probe" +
            "?profile=${profile.id.urlComponent()}&expires=$expires&sig=${signature.urlComponent()}"
    }

    private fun autoBaseUrlCandidates(
        config: MainDynamicConfig,
        advice: MessageSinkMediaDeliveryAdvice,
    ): List<String> {
        if (!config.webAdmin.enabled) return emptyList()
        val port = config.webAdmin.port
        val configuredHost = config.webAdmin.host.trim()
        return buildList {
            add("http://127.0.0.1:$port")
            add("http://localhost:$port")
            if (configuredHost.isNotBlank() && !configuredHost.isWildcardBindAddress()) {
                add("http://$configuredHost:$port")
            }
            advice.signedUrlBaseCandidates.forEach(::add)
        }
            .mapNotNull { it.normalizedBaseUrl() }
            .distinct()
    }

    private fun logSelection(profile: MediaDeliveryProfile, method: String, routeContext: OutboundMediaRouteContext) {
        val key = "${routeContext.routeCacheKey().orEmpty()}|${profile.id}|$method"
        if (selectionLogCache.putIfAbsent(key, true) == null) {
            logger.info {
                "媒体交付方式已选择：route=${routeContext.routeCacheKey().orEmpty().ifBlank { "-" }}，profile=${profile.id}，方式=$method"
            }
        }
    }

    private fun MediaDeliveryPathMapping.clientPathFor(path: Path): Path? {
        val botRootPath = botRoot.trim().takeIf { it.isNotBlank() }
            ?.let { Paths.get(it).toAbsolutePath().normalize() }
            ?: return null
        if (!path.startsWith(botRootPath)) return null
        val clientRootPath = clientRoot.trim().takeIf { it.isNotBlank() }
            ?.let { Paths.get(it).toAbsolutePath().normalize() }
            ?: return null
        return clientRootPath.resolve(botRootPath.relativize(path)).normalize()
    }

    private fun localFileProbeUri(root: OutboundMediaRoot): String? {
        return runCatching {
            Files.createDirectories(root.path)
            val probePath = root.path.resolve(PROBE_FILE_NAME).normalize()
            if (!probePath.startsWith(root.path)) return@runCatching null
            if (Files.exists(probePath, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(probePath, LinkOption.NOFOLLOW_LINKS)) return@runCatching null
                if (Files.size(probePath) > MAX_PROBE_FILE_BYTES) return@runCatching null
            } else {
                // 使用 CREATE 而非 CREATE_NEW 以容忍并发创建
                try {
                    Files.write(
                        probePath,
                        PROBE_PNG,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    )
                } catch (e: Exception) {
                    // 并发创建失败不影响使用已存在的探测文件
                    if (!Files.isRegularFile(probePath, LinkOption.NOFOLLOW_LINKS)) {
                        return@runCatching null
                    }
                }
            }
            probePath.toUri().toString()
        }.getOrNull()
    }

    private fun findAllowedRoot(path: Path, config: MainDynamicConfig): OutboundMediaRoot? {
        return allowedRoots(config).firstOrNull { root -> path.startsWith(root.path) }
    }

    private fun allowedRoots(config: MainDynamicConfig): List<OutboundMediaRoot> {
        return buildList {
            add(
                OutboundMediaRoot(
                    key = "source",
                    path = Paths.get(config.imageCache.sourceRoot).toAbsolutePath().normalize(),
                    maxBytes = config.imageCache.maxImageBytes,
                    type = OutboundMediaRootType.IMAGE,
                )
            )
            add(
                OutboundMediaRoot(
                    key = "rendered",
                    path = Paths.get(config.imageCache.renderedRoot).toAbsolutePath().normalize(),
                    maxBytes = config.imageCache.maxImageBytes,
                    type = OutboundMediaRootType.IMAGE,
                )
            )
            add(
                OutboundMediaRoot(
                    key = "login-qr",
                    path = Paths.get("data", "login-qr").toAbsolutePath().normalize(),
                    maxBytes = config.imageCache.maxImageBytes,
                    type = OutboundMediaRootType.IMAGE,
                )
            )
            config.linkParsing.videoDownload.cacheRoot
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { root ->
                    add(
                        OutboundMediaRoot(
                            key = "link-video",
                            path = Paths.get(root).toAbsolutePath().normalize(),
                            maxBytes = config.linkParsing.videoDownload.maxFileBytes,
                            type = OutboundMediaRootType.VIDEO,
                        )
                    )
                }
        }
    }

    private fun mediaId(rootKey: String, relativePath: String): String {
        val payload = "$rootKey\n$relativePath"
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeMediaId(id: String): DecodedMediaId {
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8)
        }.getOrElse {
            throw IllegalArgumentException("媒体 ID 无效")
        }
        val rootKey = decoded.substringBefore('\n', missingDelimiterValue = "")
        val relativePath = decoded.substringAfter('\n', missingDelimiterValue = "")
        require(rootKey.isNotBlank() && relativePath.isNotBlank()) { "媒体 ID 无效" }
        require(relativePath.none { it == '\u0000' }) { "媒体路径无效" }
        return DecodedMediaId(rootKey, relativePath)
    }

    private fun sign(secret: String, profileId: String, id: String, expires: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal("$profileId.$id.$expires".toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun secureEquals(left: String, right: String): Boolean {
        return MessageDigest.isEqual(
            left.toByteArray(StandardCharsets.UTF_8),
            right.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun imageContentType(path: Path, bytes: ByteArray): ContentType? {
        return when {
            bytes.hasAscii(1, "PNG") -> ContentType.Image.PNG
            bytes.size >= 3 &&
                bytes[0] == 0xff.toByte() &&
                bytes[1] == 0xd8.toByte() &&
                bytes[2] == 0xff.toByte() -> ContentType.Image.JPEG
            bytes.hasAscii(0, "GIF87a") || bytes.hasAscii(0, "GIF89a") -> ContentType.Image.GIF
            bytes.hasAscii(0, "RIFF") && bytes.hasAscii(8, "WEBP") -> ContentType("image", "webp")
            else -> when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
                "png" -> ContentType.Image.PNG
                "jpg", "jpeg" -> ContentType.Image.JPEG
                "gif" -> ContentType.Image.GIF
                "webp" -> ContentType("image", "webp")
                else -> null
            }
        }
    }

    private fun videoContentType(path: Path): ContentType? {
        return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "mp4" -> ContentType("video", "mp4")
            "m4v" -> ContentType("video", "x-m4v")
            "webm" -> ContentType("video", "webm")
            "mov" -> ContentType("video", "quicktime")
            else -> null
        }
    }

    private fun readHeader(path: Path, maxBytes: Int = 16): ByteArray {
        return Files.newInputStream(path).use { input ->
            val buffer = ByteArray(maxBytes)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    }

    private fun touchAccessTime(path: Path) {
        runCatching {
            Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()))
        }
    }

    private fun MediaDeliveryConfig.profile(profileId: String?): MediaDeliveryProfile? {
        val normalizedProfileId = profileId?.trim()?.takeIf { it.isNotBlank() }
            ?: defaultProfileId.trim().takeIf { it.isNotBlank() }
        return profiles.firstOrNull { it.id == normalizedProfileId }
            ?: profiles.firstOrNull { it.id == defaultProfileId.trim() }
            ?: profiles.firstOrNull()
    }

    private fun MediaDeliveryConfig.resolveProfile(profileId: String?): MediaDeliveryProfile? {
        val normalizedProfileId = profileId?.trim()?.takeIf { it.isNotBlank() }
        return if (normalizedProfileId == null) {
            profile(null)
        } else {
            profiles.firstOrNull { it.id == normalizedProfileId }
        }
    }

    private fun ByteArray.hasAscii(offset: Int, value: String): Boolean {
        if (size < offset + value.length) return false
        return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
    }

    private fun String.isRemoteOrEncoded(): Boolean {
        return startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true) ||
            startsWith("base64://", ignoreCase = true)
    }

    private fun String.localPath(): Path? {
        val value = trim()
        if (value.isBlank()) return null
        if (WINDOWS_ABSOLUTE_PATH.matches(value) || value.startsWith("\\\\")) {
            return runCatching { Paths.get(value) }.getOrNull()
        }
        return try {
            val parsed = runCatching { URI(value) }.getOrNull()
            when {
                parsed != null && parsed.scheme.equals("file", ignoreCase = true) -> Paths.get(parsed)
                parsed != null && parsed.scheme != null -> null
                else -> Paths.get(value)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun Path.realPathOrSelf(): Path? {
        return runCatching { toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
    }

    private fun Path.portableString(): String {
        return joinToString("/") { it.toString() }
    }

    private fun String.urlComponent(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
    }

    private fun String.normalizedBaseUrl(): String? {
        val value = trim().trimEnd('/')
        if (value.isBlank()) return null
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return value
    }

    private fun String.isWildcardBindAddress(): Boolean {
        val value = trim().lowercase()
        return value == "0.0.0.0" || value == "::" || value == "[::]"
    }

    private fun OutboundMediaRouteContext.routeCacheKey(): String? {
        val route = routeId?.trim()?.takeIf { it.isNotBlank() }
        val account = accountId?.trim()?.takeIf { it.isNotBlank() }
        val transport = transportId?.trim()?.takeIf { it.isNotBlank() } ?: "sink"
        val id = route ?: account ?: return null
        return "$transport:$id"
    }

    private val WINDOWS_ABSOLUTE_PATH: Regex = Regex("""^[a-zA-Z]:[\\/].+$""")

    private companion object {
        private const val PROBE_MEDIA_ID: String = "__probe__"
        private const val PROBE_FILE_NAME: String = ".dynamic-bot-media-probe.png"
        private const val MAX_PROBE_FILE_BYTES: Long = 1_024
        private val PROBE_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
        )
    }
}

public data class OutboundMediaRouteContext(
    val transportId: String? = null,
    val routeId: String? = null,
    val accountId: String? = null,
    val advisor: MessageSinkMediaDeliveryAdvisor? = null,
)

public data class OutboundMediaResult(
    val bytes: ByteArray? = null,
    val file: File? = null,
    val contentType: ContentType,
    val cacheMaxAgeSeconds: Long,
)

private data class OutboundMediaRoot(
    val key: String,
    val path: Path,
    val maxBytes: Long,
    val type: OutboundMediaRootType,
)

private enum class OutboundMediaRootType {
    IMAGE,
    VIDEO,
}

private data class DecodedMediaId(
    val rootKey: String,
    val relativePath: String,
)

private data class ProbeCacheKey(
    val routeKey: String,
    val profileId: String,
    val method: MessageSinkMediaDeliveryMethod,
    val target: String,
)

private data class CachedProbeResult(
    val status: MessageSinkMediaDeliveryProbeStatus,
    val reason: String,
    val expiresAtEpochSeconds: Long,
)
