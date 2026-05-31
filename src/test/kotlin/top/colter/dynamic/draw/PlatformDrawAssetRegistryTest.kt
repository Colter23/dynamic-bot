package top.colter.dynamic.draw

import java.nio.file.Files
import java.nio.file.Path
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PlatformDrawAssetDescriptor
import top.colter.dynamic.core.plugin.PlatformDrawAssetKind
import top.colter.dynamic.core.plugin.PlatformDrawAssetKeys
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry

class PlatformDrawAssetRegistryTest {
    private val classLoader: ClassLoader = javaClass.classLoader

    @Test
    fun `plugin descriptor should parse draw assets from yaml`() {
        val yaml = """
            id: bilibili-publisher
            name: Bilibili
            version: 1.0.0
            mainClass: example.Plugin
            drawAssets:
              - platformId: bilibili
                key: default.header
                kind: DEFAULT_HEADER
                resourcePath: draw/bilibili/header/default.png
                mimeType: image/png
        """.trimIndent()

        val descriptor = ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .readValue<PluginDescriptor>(yaml)

        assertEquals("default.header", descriptor.drawAssets.single().key)
        assertEquals(PlatformDrawAssetKind.DEFAULT_HEADER, descriptor.drawAssets.single().kind)
    }

    @Test
    fun `registry should resolve plugin raster asset`() {
        val registry = PlatformDrawAssetRegistry()
        registry.registerPluginAssets(
            pluginId = "test-plugin",
            descriptors = listOf(
                PlatformDrawAssetDescriptor(
                    platformId = "bilibili",
                    key = PlatformDrawAssetKeys.DEFAULT_HEADER,
                    kind = PlatformDrawAssetKind.DEFAULT_HEADER,
                    resourcePath = "image/banner.jpg",
                ),
            ),
            classLoader = classLoader,
        )

        val image = registry.image(PlatformId.of("bilibili"), PlatformDrawAssetKeys.DEFAULT_HEADER)

        assertNotNull(image)
    }

    @Test
    fun `registry should render svg asset with requested size`() {
        val registry = PlatformDrawAssetRegistry()
        registry.registerPluginAssets(
            pluginId = "test-plugin",
            descriptors = listOf(
                PlatformDrawAssetDescriptor(
                    platformId = "bilibili",
                    key = "avatarBadge.official.individual",
                    kind = PlatformDrawAssetKind.AVATAR_BADGE,
                    resourcePath = "icon/test_badge.svg",
                    mimeType = "image/svg+xml",
                ),
            ),
            classLoader = classLoader,
        )

        val image = registry.image(
            platformId = PlatformId.of("bilibili"),
            key = "avatarBadge.official.individual",
            width = 32,
            height = 32,
        )

        assertNotNull(image)
        assertEquals(32, image.width)
        assertEquals(32, image.height)
    }

    @Test
    fun `registry should reject duplicate platform asset keys`() {
        val registry = PlatformDrawAssetRegistry()
        val descriptor = PlatformDrawAssetDescriptor(
            platformId = "bilibili",
            key = PlatformDrawAssetKeys.DEFAULT_HEADER,
            kind = PlatformDrawAssetKind.DEFAULT_HEADER,
            resourcePath = "image/banner.jpg",
        )
        registry.registerPluginAssets("plugin-a", listOf(descriptor), classLoader)

        val error = assertFailsWith<IllegalArgumentException> {
            registry.registerPluginAssets("plugin-b", listOf(descriptor), classLoader)
        }

        assertTrue(error.message.orEmpty().contains("平台绘图资源重复"))
    }

    @Test
    fun `registry should reject missing plugin resource`() {
        val registry = PlatformDrawAssetRegistry()

        val error = assertFailsWith<IllegalArgumentException> {
            registry.registerPluginAssets(
                pluginId = "test-plugin",
                descriptors = listOf(
                    PlatformDrawAssetDescriptor(
                        platformId = "bilibili",
                        key = PlatformDrawAssetKeys.DEFAULT_HEADER,
                        kind = PlatformDrawAssetKind.DEFAULT_HEADER,
                        resourcePath = "draw/missing.png",
                    ),
                ),
                classLoader = classLoader,
            )
        }

        assertTrue(error.message.orEmpty().contains("插件绘图资源不存在"))
    }

    @Test
    fun `registry should remove assets when plugin is unregistered`() {
        val registry = PlatformDrawAssetRegistry()
        registry.registerPluginAssets(
            pluginId = "test-plugin",
            descriptors = listOf(
                PlatformDrawAssetDescriptor(
                    platformId = "bilibili",
                    key = PlatformDrawAssetKeys.DEFAULT_HEADER,
                    kind = PlatformDrawAssetKind.DEFAULT_HEADER,
                    resourcePath = "image/banner.jpg",
                ),
            ),
            classLoader = classLoader,
        )

        registry.unregisterPluginAssets("test-plugin")

        assertNull(registry.image(PlatformId.of("bilibili"), PlatformDrawAssetKeys.DEFAULT_HEADER))
    }

    @Test
    fun `main resources should not contain bilibili platform draw assets`() {
        val resourceRoot = Path.of("src/main/resources")
        val offenders = Files.walk(resourceRoot).use { paths ->
            paths
                .filter { it.isRegularFile() }
                .map { resourceRoot.relativize(it).toString() }
                .filter { it.contains("BILIBILI", ignoreCase = true) }
                .sorted()
                .toList()
        }

        assertEquals(emptyList(), offenders)
    }
}
