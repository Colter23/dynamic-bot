package top.colter.dynamic.link

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.plugin.PluginManager
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey

class LinkResolverTest {
    @Test
    fun `parsed link model should resolve to dynamic result`() = runBlocking {
        val resolver = FakeLinkResolver()
        val parsed = resolver.parseLink("https://example.com/dynamic/1")

        assertNotNull(parsed)
        assertEquals(PlatformId.of("example"), parsed.platformId)
        assertEquals("1", parsed.targetId)
        assertEquals(LinkKinds.DYNAMIC, parsed.kind)

        val resolution = resolver.resolveLink(parsed)
        assertIs<LinkResolution.Dynamic>(resolution)
        assertEquals("1", resolution.update.key.externalId)
    }

    @Test
    fun `plugin manager should expose active link resolvers`() {
        val manager = PluginManager()
        val resolver = FakeLinkResolver()
        manager.registerPluginForTest(
            descriptor = PluginDescriptor(
                id = "fake-link",
                name = "Fake Link Resolver",
                version = "0.0.1",
                mainClass = resolver::class.qualifiedName.orEmpty(),
            ),
            instance = resolver,
            state = PluginState.ACTIVE,
        )

        assertEquals(listOf(resolver), manager.getLinkResolvers())
    }

    private class FakeLinkResolver : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("example")

        override suspend fun parseLink(inputUrl: String): ParsedLink? {
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.DYNAMIC,
                targetId = inputUrl.substringAfterLast("/"),
                normalizedUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            return LinkResolution.Dynamic(
                parsedLink = parsedLink,
                update = testDynamicUpdate(
                    publisher = testPublisherInfo(
                        key = testPublisherKey(platformId = platformId.value, externalId = "publisher"),
                        name = "publisher",
                    ),
                    externalId = parsedLink.targetId,
                    payload = DynamicPayload(blocks = listOf()),
                ).copy(link = parsedLink.normalizedUrl),
            )
        }
    }
}
