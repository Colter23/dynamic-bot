package top.colter.dynamic.link

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.link.DynamicLinkResolution
import top.colter.dynamic.core.link.DynamicLinkResolver
import top.colter.dynamic.core.link.ParsedDynamicLink
import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.plugin.PluginManager
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey

class DynamicLinkResolverTest {
    @Test
    fun `parsed dynamic link model should resolve to success`() = runBlocking {
        val resolver = FakeDynamicLinkResolver()
        val parsed = resolver.parseDynamicLink("https://example.com/dynamic/1")

        requireNotNull(parsed)
        assertEquals(PlatformId.of("example"), parsed.platformId)
        assertEquals("1", parsed.updateId)

        val resolution = resolver.resolveDynamicLink(parsed)
        assertIs<DynamicLinkResolution.Success>(resolution)
        assertEquals("1", resolution.update.key.externalId)
    }

    @Test
    fun `plugin manager should expose active link resolvers`() {
        val manager = PluginManager()
        val resolver = FakeDynamicLinkResolver()
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

        assertEquals(listOf(resolver), manager.getDynamicLinkResolvers())
    }

    private class FakeDynamicLinkResolver : DynamicLinkResolver {
        override val platformId: PlatformId = PlatformId.of("example")

        override suspend fun parseDynamicLink(inputUrl: String): ParsedDynamicLink? {
            return ParsedDynamicLink(
                platformId = platformId,
                updateId = inputUrl.substringAfterLast("/"),
                normalizedUrl = inputUrl,
            )
        }

        override suspend fun resolveDynamicLink(parsedLink: ParsedDynamicLink): DynamicLinkResolution {
            return DynamicLinkResolution.Success(
                parsedLink = parsedLink,
                update = testDynamicUpdate(
                    publisher = testPublisherInfo(
                        key = testPublisherKey(platformId = platformId.value, externalId = "publisher"),
                        name = "publisher",
                    ),
                    externalId = parsedLink.updateId,
                    payload = DynamicPayload(content = DynamicContent.text("resolved")),
                ),
            )
        }

    }
}
