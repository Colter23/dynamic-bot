package top.colter.dynamic.media

import io.ktor.http.ContentType
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.LinkVideoDownloadConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.MediaDeliveryConfig
import top.colter.dynamic.MediaDeliveryPathMapping
import top.colter.dynamic.MediaDeliveryProfile
import top.colter.dynamic.MediaDeliveryType
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef

class OutboundMediaServiceTest {
    @Test
    fun `rewrite local rendered image to signed public url and resolve it`() {
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
        assertContentEquals(imageBytes, assertNotNull(result.bytes))
        assertNull(result.file)
    }

    @Test
    fun `rewrite local link video to signed public url and resolve it as file`() {
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
    fun `rewrite local file profile with path mapping`() {
        val renderedRoot = createTempDirectory("outbound-local-rendered")
        val clientRoot = createTempDirectory("outbound-local-client")
        val image = renderedRoot.resolve("bilibili").resolve("demo.png")
        image.parent.createDirectories()
        image.writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
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
                                pathMappings = listOf(
                                    MediaDeliveryPathMapping(
                                        botRoot = renderedRoot.toString(),
                                        clientRoot = clientRoot.toString(),
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

        assertEquals(clientRoot.resolve("bilibili").resolve("demo.png").toUri().toString(), rewritten.uri)
    }

    @Test
    fun `rewrite base64 profile for small images`() {
        val renderedRoot = createTempDirectory("outbound-base64-rendered")
        val image = renderedRoot.resolve("demo.png")
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
                                imageBase64MaxBytes = 10,
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
    fun `keep local media when auto profile cannot rewrite it`() {
        val service = OutboundMediaService(
            configProvider = { MainDynamicConfig() },
            nowEpochSeconds = { 1_000 },
        )
        val media = MediaRef(uri = "data/images/draw/missing.png", kind = MediaKind.IMAGE)

        assertEquals(media, service.rewriteMedia(media))
    }

    @Test
    fun `reject expired signed url`() {
        val renderedRoot = createTempDirectory("outbound-expired")
        val image = renderedRoot.resolve("demo.png")
        image.writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
        var now = 1_000L
        val service = OutboundMediaService(
            configProvider = {
                MainDynamicConfig(
                    imageCache = ImageCacheConfig(renderedRoot = renderedRoot.toString()),
                    mediaDelivery = signedUrlDelivery(urlTtlSeconds = 10),
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

    private fun signedUrlDelivery(urlTtlSeconds: Int = 60): MediaDeliveryConfig {
        return MediaDeliveryConfig(
            defaultProfileId = "remote",
            profiles = listOf(
                MediaDeliveryProfile(
                    id = "remote",
                    type = MediaDeliveryType.SIGNED_URL,
                    publicBaseUrl = "http://example.com:2233/",
                    urlTtlSeconds = urlTtlSeconds,
                    signingSecret = "secret",
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

    private data class SignedUrlParts(
        val profile: String,
        val id: String,
        val expires: Long,
        val signature: String,
    )
}
