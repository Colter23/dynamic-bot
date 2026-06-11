package top.colter.dynamic.media

import io.ktor.http.ContentType
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.io.path.createDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.LinkVideoDownloadConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.MediaDeliveryBase64FallbackConfig
import top.colter.dynamic.MediaDeliveryConfig
import top.colter.dynamic.MediaDeliveryLocalFileConfig
import top.colter.dynamic.MediaDeliveryPathMapping
import top.colter.dynamic.MediaDeliveryProfile
import top.colter.dynamic.MediaDeliverySignedUrlConfig
import top.colter.dynamic.MediaDeliveryType
import top.colter.dynamic.WebAdminConfig
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdvice
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdviceRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdvisor
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryConfidence
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryMethod
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeResult

class OutboundMediaServiceTest {
    @Test
    fun `rewrite local rendered image to signed public url and resolve it as file`() = runTest {
        val renderedRoot = createTempDirectory("outbound-rendered")
        val sourceRoot = createTempDirectory("outbound-source")
        val imageBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val image = renderedRoot.resolve("bilibili").resolve("demo.png")
        image.parent.createDirectories()
        image.writeBytes(imageBytes)

        val service = OutboundMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(
                        sourceRoot = sourceRoot.toString(),
                        renderedRoot = renderedRoot.toString(),
                        maxImageMegabytes = 1.0,
                    ),
                    mediaDelivery = signedUrlDelivery(),
                )
            },
            nowEpochSeconds = { 1_000 },
        )

        val rewritten = service.rewriteMedia(MediaRef(uri = image.toString(), kind = MediaKind.IMAGE))

        assertTrue(rewritten.uri.startsWith("http://example.com:2233/media/outbound/"))
        assertFalse(rewritten.uri.contains(image.toString()))
        val parts = signedUrlParts(rewritten.uri)
        assertEquals("remote", parts.profile)
        val result = service.resolve(parts.profile, parts.id, parts.expires, parts.signature)
        assertEquals(ContentType.Image.PNG, result.contentType)
        assertEquals(60, result.cacheMaxAgeSeconds)
        assertEquals(image.toFile(), result.file)
        assertNull(result.bytes)
    }

    @Test
    fun `resolve signed webp rendered image as webp content type`() = runTest {
        val renderedRoot = createTempDirectory("outbound-rendered-webp")
        val sourceRoot = createTempDirectory("outbound-source-webp")
        val image = renderedRoot.resolve("bilibili").resolve("demo.webp")
        image.parent.createDirectories()
        image.writeBytes(webpBytes())

        val service = OutboundMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(
                        sourceRoot = sourceRoot.toString(),
                        renderedRoot = renderedRoot.toString(),
                        maxImageMegabytes = 1.0,
                    ),
                    mediaDelivery = signedUrlDelivery(),
                )
            },
            nowEpochSeconds = { 1_000 },
        )

        val rewritten = service.rewriteMedia(MediaRef(uri = image.toString(), kind = MediaKind.IMAGE))
        val parts = signedUrlParts(rewritten.uri)
        val result = service.resolve(parts.profile, parts.id, parts.expires, parts.signature)

        assertEquals(ContentType("image", "webp"), result.contentType)
        assertEquals(image.toFile(), result.file)
        assertNull(result.bytes)
    }

    @Test
    fun `rewrite local link video to signed public url and resolve it as file`() = runTest {
        val renderedRoot = createTempDirectory("outbound-rendered-video")
        val sourceRoot = createTempDirectory("outbound-source-video")
        val videoRoot = createTempDirectory("outbound-video")
        val video = videoRoot.resolve("bilibili").resolve("demo.mp4")
        video.parent.createDirectories()
        video.writeBytes(byteArrayOf(0, 0, 0, 1))

        val service = OutboundMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(
                        sourceRoot = sourceRoot.toString(),
                        renderedRoot = renderedRoot.toString(),
                        maxImageMegabytes = 1.0,
                    ),
                    linkParsing = LinkParsingConfig(
                        videoDownload = LinkVideoDownloadConfig(
                            cacheRoot = videoRoot.toString(),
                            maxFileMegabytes = 1.0,
                        ),
                    ),
                    mediaDelivery = signedUrlDelivery(),
                )
            },
            nowEpochSeconds = { 1_000 },
        )

        val rewritten = service.rewriteMedia(MediaRef(uri = video.toString(), kind = MediaKind.VIDEO))

        assertTrue(rewritten.uri.startsWith("http://example.com:2233/media/outbound/"))
        val parts = signedUrlParts(rewritten.uri)
        val result = service.resolve(parts.profile, parts.id, parts.expires, parts.signature)
        assertEquals(ContentType("video", "mp4"), result.contentType)
        assertEquals(video.toFile(), result.file)
        assertNull(result.bytes)
    }

    @Test
    fun `rewrite local file profile with path mapping`() = runTest {
        val renderedRoot = createTempDirectory("outbound-local-rendered")
        val clientRoot = createTempDirectory("outbound-local-client")
        val image = renderedRoot.resolve("bilibili").resolve("demo.webp")
        image.parent.createDirectories()
        image.writeBytes(webpBytes())
        val service = OutboundMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(renderedRoot = renderedRoot.toString()),
                    mediaDelivery = MediaDeliveryConfig(
                        defaultProfileId = "local",
                        profiles = listOf(
                            MediaDeliveryProfile(
                                id = "local",
                                type = MediaDeliveryType.LOCAL_FILE,
                                localFile = MediaDeliveryLocalFileConfig(
                                    pathMappings = listOf(
                                        MediaDeliveryPathMapping(
                                            botRoot = renderedRoot.toString(),
                                            clientRoot = clientRoot.toString(),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            },
            nowEpochSeconds = { 1_000 },
        )

        val rewritten = service.rewriteMedia(MediaRef(uri = image.toString(), kind = MediaKind.IMAGE))

        assertEquals(clientRoot.resolve("bilibili").resolve("demo.webp").toUri().toString(), rewritten.uri)
    }

    @Test
    fun `rewrite base64 profile for small images using megabytes threshold`() = runTest {
        val renderedRoot = createTempDirectory("outbound-base64-rendered")
        val image = renderedRoot.resolve("demo.webp")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val service = OutboundMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(renderedRoot = renderedRoot.toString()),
                    mediaDelivery = MediaDeliveryConfig(
                        defaultProfileId = "base64",
                        profiles = listOf(
                            MediaDeliveryProfile(
                                id = "base64",
                                type = MediaDeliveryType.BASE64,
                                base64Fallback = MediaDeliveryBase64FallbackConfig(maxMegabytes = 0.001),
                            ),
                        ),
                    ),
                )
            },
            nowEpochSeconds = { 1_000 },
        )

        val rewritten = service.rewriteMedia(MediaRef(uri = image.toString(), kind = MediaKind.IMAGE))

        assertEquals("base64://AQID", rewritten.uri)
    }

    @Test
    fun `auto prefers local file when advisor reports likely local access`() = runTest {
        val renderedRoot = createTempDirectory("outbound-auto-local")
        val image = renderedRoot.resolve("demo.png")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val advisor = FakeAdvisor(localConfidence = MessageSinkMediaDeliveryConfidence.LIKELY)
        val service = autoService(renderedRoot, advisor)

        val rewritten = service.rewriteMedia(
            MediaRef(uri = image.toString(), kind = MediaKind.IMAGE),
            routeContext = routeContext(advisor),
        )

        assertEquals(image.toUri().toString(), rewritten.uri)
        assertEquals(1, advisor.probeRequests.count { it.method == MessageSinkMediaDeliveryMethod.LOCAL_FILE })
        assertEquals(0, advisor.probeRequests.count { it.method == MessageSinkMediaDeliveryMethod.SIGNED_URL })
        val localProbe = advisor.probeRequests.single { it.method == MessageSinkMediaDeliveryMethod.LOCAL_FILE }
        assertTrue(localProbe.uri.contains(".dynamic-bot-media-probe.png"))
        assertFalse(localProbe.uri.contains("demo.png"))
        assertEquals(localProbe.uri, localProbe.media?.uri)
        assertTrue((localProbe.sizeBytes ?: 0L) < 1_024L)
    }

    @Test
    fun `auto local probe does not overwrite non regular probe path`() = runTest {
        val renderedRoot = createTempDirectory("outbound-auto-local-non-regular-probe")
        val image = renderedRoot.resolve("demo.png")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val probeDirectory = renderedRoot.resolve(".dynamic-bot-media-probe.png")
        probeDirectory.createDirectory()
        val advisor = FakeAdvisor(localConfidence = MessageSinkMediaDeliveryConfidence.LIKELY)
        val service = autoService(renderedRoot, advisor)

        val rewritten = service.rewriteMedia(
            MediaRef(uri = image.toString(), kind = MediaKind.IMAGE),
            routeContext = routeContext(advisor),
        )

        val localProbe = advisor.probeRequests.single { it.method == MessageSinkMediaDeliveryMethod.LOCAL_FILE }
        assertEquals(image.toUri().toString(), rewritten.uri)
        assertEquals(image.toUri().toString(), localProbe.uri)
        assertTrue(probeDirectory.toFile().isDirectory)
    }

    @Test
    fun `auto uses signed url after local is unavailable and url probe succeeds`() = runTest {
        val renderedRoot = createTempDirectory("outbound-auto-url")
        val image = renderedRoot.resolve("demo.png")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val advisor = FakeAdvisor(
            localConfidence = MessageSinkMediaDeliveryConfidence.UNAVAILABLE,
            availableSignedUrlPrefix = "http://example.com:2233/",
        )
        val service = autoService(renderedRoot, advisor)

        val rewritten = service.rewriteMedia(
            MediaRef(uri = image.toString(), kind = MediaKind.IMAGE),
            routeContext = routeContext(advisor),
        )

        assertTrue(rewritten.uri.startsWith("http://example.com:2233/media/outbound/"))
        assertEquals(3, advisor.probeRequests.count { it.method == MessageSinkMediaDeliveryMethod.SIGNED_URL })
    }

    @Test
    fun `auto falls back to base64 after local and signed url are unavailable`() = runTest {
        val renderedRoot = createTempDirectory("outbound-auto-base64")
        val image = renderedRoot.resolve("demo.png")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val advisor = FakeAdvisor(localConfidence = MessageSinkMediaDeliveryConfidence.UNAVAILABLE)
        val service = autoService(renderedRoot, advisor)

        val rewritten = service.rewriteMedia(
            MediaRef(uri = image.toString(), kind = MediaKind.IMAGE),
            routeContext = routeContext(advisor),
        )

        assertEquals("base64://AQID", rewritten.uri)
    }

    @Test
    fun `auto caches signed url probe result by route and base url`() = runTest {
        val renderedRoot = createTempDirectory("outbound-auto-cache")
        val first = renderedRoot.resolve("first.png")
        val second = renderedRoot.resolve("second.png")
        first.writeBytes(byteArrayOf(1, 2, 3))
        second.writeBytes(byteArrayOf(4, 5, 6))
        val advisor = FakeAdvisor(
            localConfidence = MessageSinkMediaDeliveryConfidence.UNAVAILABLE,
            availableSignedUrlPrefix = "http://example.com:2233/",
        )
        val service = autoService(renderedRoot, advisor)
        val context = routeContext(advisor)

        service.rewriteMedia(MediaRef(uri = first.toString(), kind = MediaKind.IMAGE), routeContext = context)
        service.rewriteMedia(MediaRef(uri = second.toString(), kind = MediaKind.IMAGE), routeContext = context)

        assertEquals(
            3,
            advisor.probeRequests.count { it.method == MessageSinkMediaDeliveryMethod.SIGNED_URL },
        )
    }

    @Test
    fun `keep local media when auto profile cannot rewrite it`() = runTest {
        val service = OutboundMediaService(
            configProvider = { MainDynamicConfig() },
            nowEpochSeconds = { 1_000 },
        )
        val media = MediaRef(uri = "data/images/draw/missing.png", kind = MediaKind.IMAGE)

        assertEquals(media, service.rewriteMedia(media))
    }

    @Test
    fun `reject expired signed url`() = runTest {
        val renderedRoot = createTempDirectory("outbound-expired")
        val image = renderedRoot.resolve("demo.png")
        image.writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
        var now = 1_000L
        val service = OutboundMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(renderedRoot = renderedRoot.toString()),
                    mediaDelivery = signedUrlDelivery(ttlSeconds = 10),
                )
            },
            nowEpochSeconds = { now },
        )
        val parts = signedUrlParts(service.rewriteMedia(MediaRef(image.toString(), MediaKind.IMAGE)).uri)

        now = 1_011

        assertFailsWith<IllegalArgumentException> {
            service.resolve(parts.profile, parts.id, parts.expires, parts.signature)
        }
    }

    private fun autoService(renderedRoot: java.nio.file.Path, advisor: FakeAdvisor): OutboundMediaService {
        return OutboundMediaService(
            configProvider = {
                MainDynamicConfig(
                    webAdmin = WebAdminConfig(host = "example.com", port = 2233, token = "token"),
                    imageCache = ImageCacheConfig(renderedRoot = renderedRoot.toString()),
                    mediaDelivery = MediaDeliveryConfig(
                        defaultProfileId = "auto",
                        profiles = listOf(
                            MediaDeliveryProfile(
                                id = "auto",
                                type = MediaDeliveryType.AUTO,
                                signedUrl = MediaDeliverySignedUrlConfig(signingSecret = "secret", ttlSeconds = 60),
                                base64Fallback = MediaDeliveryBase64FallbackConfig(maxMegabytes = 1.0),
                            ),
                        ),
                    ),
                )
            },
            nowEpochSeconds = { 1_000 },
        )
    }

    private fun routeContext(advisor: MessageSinkMediaDeliveryAdvisor): OutboundMediaRouteContext {
        return OutboundMediaRouteContext(
            transportId = "onebot",
            routeId = "onebot:qq:42",
            accountId = "42",
            advisor = advisor,
        )
    }

    private fun signedUrlDelivery(ttlSeconds: Int = 60): MediaDeliveryConfig {
        return MediaDeliveryConfig(
            defaultProfileId = "remote",
            profiles = listOf(
                MediaDeliveryProfile(
                    id = "remote",
                    type = MediaDeliveryType.SIGNED_URL,
                    signedUrl = MediaDeliverySignedUrlConfig(
                        publicBaseUrl = "http://example.com:2233/",
                        ttlSeconds = ttlSeconds,
                        signingSecret = "secret",
                    ),
                ),
            ),
        )
    }

    private fun signedUrlParts(url: String): SignedUrlParts {
        val uri = URI(url)
        val id = uri.path.substringAfterLast('/')
        val params = uri.rawQuery.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .associate { (key, value) ->
                key to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
        return SignedUrlParts(
            profile = params.getValue("profile"),
            id = URLDecoder.decode(id, StandardCharsets.UTF_8),
            expires = params.getValue("expires").toLong(),
            signature = params.getValue("sig"),
        )
    }

    private fun webpBytes(): ByteArray {
        return byteArrayOf(
            'R'.code.toByte(),
            'I'.code.toByte(),
            'F'.code.toByte(),
            'F'.code.toByte(),
            0,
            0,
            0,
            0,
            'W'.code.toByte(),
            'E'.code.toByte(),
            'B'.code.toByte(),
            'P'.code.toByte(),
            1,
            2,
            3,
        )
    }

    private class FakeAdvisor(
        private val localConfidence: MessageSinkMediaDeliveryConfidence,
        private val availableSignedUrlPrefix: String = "",
    ) : MessageSinkMediaDeliveryAdvisor {
        val probeRequests = mutableListOf<MessageSinkMediaDeliveryProbeRequest>()

        override suspend fun adviseMediaDelivery(
            request: MessageSinkMediaDeliveryAdviceRequest,
        ): MessageSinkMediaDeliveryAdvice {
            return MessageSinkMediaDeliveryAdvice(
                localFileConfidence = localConfidence,
                signedUrlBaseCandidates = listOf("http://example.com:2233"),
            )
        }

        override suspend fun probeMediaDelivery(
            request: MessageSinkMediaDeliveryProbeRequest,
        ): MessageSinkMediaDeliveryProbeResult {
            probeRequests += request
            return when (request.method) {
                MessageSinkMediaDeliveryMethod.LOCAL_FILE -> MessageSinkMediaDeliveryProbeResult.unknown()
                MessageSinkMediaDeliveryMethod.SIGNED_URL -> if (
                    availableSignedUrlPrefix.isNotBlank() && request.uri.startsWith(availableSignedUrlPrefix)
                ) {
                    MessageSinkMediaDeliveryProbeResult.available()
                } else {
                    MessageSinkMediaDeliveryProbeResult.unavailable()
                }
            }
        }
    }

    private data class SignedUrlParts(
        val profile: String,
        val id: String,
        val expires: Long,
        val signature: String,
    )
}
