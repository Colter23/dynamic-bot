import top.colter.dynamic.DrawFontSettings
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.draw.DrawConfig
import top.colter.skiko.FontRegistry
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class DrawConfigTest {
    @Test
    fun `draw config loads bundled default fonts`() {
        val fontRegistry = FontRegistry()

        DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            fontRegistry = fontRegistry,
        )

        assertNotNull(fontRegistry.defaultFont)
        assertNotNull(fontRegistry.emojiFont)
    }

    @Test
    fun `draw config loads bundled fonts without context classloader`() {
        val previousClassLoader = Thread.currentThread().contextClassLoader
        val fontRegistry = FontRegistry()

        try {
            Thread.currentThread().contextClassLoader = null
            DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
                fontRegistry = fontRegistry,
            )
        } finally {
            Thread.currentThread().contextClassLoader = previousClassLoader
        }

        assertNotNull(fontRegistry.defaultFont)
        assertNotNull(fontRegistry.emojiFont)
    }

    @Test
    fun `draw config loads configured font files before bundled defaults`() {
        val bundledRegistry = FontRegistry()
        DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            fontRegistry = bundledRegistry,
        )
        val bundledTextFamily = assertNotNull(bundledRegistry.defaultFont).familyName

        val configuredRegistry = FontRegistry()
        DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            settings = DrawSettings(
                font = DrawFontSettings(
                    text = testFontPath("NotoColorEmoji.ttf"),
                    emoji = testFontPath("HarmonyOS_SansSC_Medium.ttf"),
                ),
            ),
            fontRegistry = configuredRegistry,
        )

        assertNotEquals(bundledTextFamily, assertNotNull(configuredRegistry.defaultFont).familyName)
        assertEquals(bundledTextFamily, assertNotNull(configuredRegistry.emojiFont).familyName)
    }

    private fun testFontPath(name: String): String {
        val resource = requireNotNull(Thread.currentThread().contextClassLoader.getResource("font/$name"))
        return Paths.get(resource.toURI()).toAbsolutePath().normalize().toString()
    }
}
