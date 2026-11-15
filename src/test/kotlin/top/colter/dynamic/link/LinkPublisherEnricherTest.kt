package top.colter.dynamic.link

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testPublisherInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class LinkPublisherEnricherTest {
    private val banner = MediaRef("https://example.com/banner.jpg", MediaKind.COVER)
    private val previewUri = "https://example.com/banner-preview.webp"

    @Test
    fun `stored banner should be prepared without fetching complete publisher`() = runBlocking {
        val plugin = PreviewLookupPlugin()
        val enricher = LinkPublisherEnricher(
            storedPublisherResolver = { testPublisher(banner = banner) },
            publisherLookupResolver = { plugin },
        )

        val publisher = enricher.enrich(testDynamicUpdate()).publisher

        assertEquals(banner.uri, publisher.banner?.uri)
        assertEquals(previewUri, publisher.banner?.previewUri)
        assertEquals(0, plugin.fetchCount)
        assertEquals(1, plugin.prepareCount)
    }

    @Test
    fun `remote banner should be prepared after missing publisher details are fetched`() = runBlocking {
        val plugin = PreviewLookupPlugin(fetched = testPublisherInfo(banner = banner))
        val enricher = LinkPublisherEnricher(
            storedPublisherResolver = { null },
            publisherLookupResolver = { plugin },
        )

        val publisher = enricher.enrich(testDynamicUpdate()).publisher

        assertEquals(banner.uri, publisher.banner?.uri)
        assertEquals(previewUri, publisher.banner?.previewUri)
        assertEquals(1, plugin.fetchCount)
        assertEquals(1, plugin.prepareCount)
    }

    @Test
    fun `prepared banner should also become a missing user preview cover`() = runBlocking {
        val plugin = PreviewLookupPlugin()
        val enricher = LinkPublisherEnricher(
            storedPublisherResolver = { testPublisher(banner = banner) },
            publisherLookupResolver = { plugin },
        )
        val preview = LinkPreview(
            platformId = plugin.platformId,
            kind = LinkKinds.USER,
            id = "123",
            url = "https://example.com/user/123",
            title = "demo",
            publisher = testPublisherInfo(),
        )

        val enriched = enricher.enrich(preview)

        assertEquals(previewUri, enriched.cover?.previewUri)
        assertEquals(enriched.publisher?.banner, enriched.cover)
        assertEquals(0, plugin.fetchCount)
    }

    @Test
    fun `mode changes should clear previous banner preview without fetching`() = runBlocking {
        val plugin = PreviewLookupPlugin()
        val enricher = LinkPublisherEnricher(
            storedPublisherResolver = { null },
            publisherLookupResolver = { plugin },
        )
        val prepared = enricher.enrich(testDynamicUpdate(publisher = testPublisherInfo(banner = banner)))
        plugin.previewEnabled = false

        val publisher = enricher.enrich(prepared).publisher

        assertEquals(banner, publisher.banner)
        assertNull(publisher.banner?.previewUri)
        assertEquals(0, plugin.fetchCount)
    }

    @Test
    fun `lookup failure should still prepare existing banner`() = runBlocking {
        val plugin = PreviewLookupPlugin(failure = IllegalStateException("lookup failed"))
        var reportedFailure: Throwable? = null
        val enricher = LinkPublisherEnricher(
            storedPublisherResolver = { null },
            publisherLookupResolver = { plugin },
            onLookupFailure = { _, error -> reportedFailure = error },
        )

        val publisher = enricher.enrich(
            testDynamicUpdate(publisher = testPublisherInfo(name = "", banner = banner)),
        ).publisher

        assertEquals(previewUri, publisher.banner?.previewUri)
        assertSame(plugin.failure, reportedFailure)
        assertEquals(1, plugin.fetchCount)
    }

    @Test
    fun `default preparation should preserve publisher metadata`() = runBlocking {
        val plugin = object : PublisherLookupPlugin {
            override val platformId = PlatformId.of("other")
            override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? = null
        }
        val publisher = testPublisherInfo(banner = banner)
        val enricher = LinkPublisherEnricher(
            storedPublisherResolver = { null },
            publisherLookupResolver = { plugin },
        )

        assertSame(publisher, enricher.enrich(testDynamicUpdate(publisher = publisher)).publisher)
    }

    @Test
    fun `cancelled lookup must not continue drawing or report platform failure`() = runBlocking {
        val plugin = PreviewLookupPlugin(failure = CancellationException("cancelled"))
        var failures = 0
        val enricher = LinkPublisherEnricher(
            storedPublisherResolver = { null },
            publisherLookupResolver = { plugin },
            onLookupFailure = { _, _ -> failures++ },
        )

        assertFailsWith<CancellationException> { enricher.enrich(testDynamicUpdate()) }
        assertEquals(0, plugin.prepareCount)
        assertEquals(0, failures)
    }

    private inner class PreviewLookupPlugin(
        val fetched: PublisherInfo? = null,
        val failure: Throwable? = null,
    ) : PublisherLookupPlugin {
        override val platformId = PlatformId.of("bilibili")
        var previewEnabled = true
        var fetchCount = 0
        var prepareCount = 0

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
            fetchCount++
            failure?.let { throw it }
            return fetched
        }

        override fun preparePublisherForDrawing(publisher: PublisherInfo): PublisherInfo {
            prepareCount++
            return publisher.copy(banner = publisher.banner?.copy(
                previewUri = previewUri.takeIf { previewEnabled },
            ))
        }
    }
}
