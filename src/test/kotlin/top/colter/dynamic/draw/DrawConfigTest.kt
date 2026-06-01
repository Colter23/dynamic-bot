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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrawConfigTest {
    @Test
    fun `draw config loads bundled default fonts`() {
        val config = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
        )

        assertNotNull(config.fontRegistry.textTypeface)
        assertNotNull(config.fontRegistry.emojiTypeface)
    }

    @Test
    fun `draw config loads bundled fonts without context classloader`() {
        val previousClassLoader = Thread.currentThread().contextClassLoader

        val config = try {
            Thread.currentThread().contextClassLoader = null
            DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
                settings = DrawSettings(
                    font = DrawFontSettings(
                        text = "__missing_text_family__",
                        emoji = "__missing_emoji_family__",
                    ),
                ),
            )
        } finally {
            Thread.currentThread().contextClassLoader = previousClassLoader
        }

        assertNotNull(config.fontRegistry.textTypeface)
        assertNotNull(config.fontRegistry.emojiTypeface)
    }

    @Test
    fun `draw config loads configured font files before bundled defaults`() {
        val bundledConfig = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
        )
        val bundledTextFamily = assertNotNull(bundledConfig.fontRegistry.textTypeface).familyName

        val configuredConfig = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            settings = DrawSettings(
                font = DrawFontSettings(
                    text = testFontPath("NotoColorEmoji.ttf"),
                    emoji = testFontPath("HarmonyOS_SansSC_Medium.ttf"),
                ),
            ),
        )

        assertNotEquals(bundledTextFamily, assertNotNull(configuredConfig.fontRegistry.textTypeface).familyName)
        assertEquals(bundledTextFamily, assertNotNull(configuredConfig.fontRegistry.emojiTypeface).familyName)
    }

    @Test
    fun `draw config reuses registry for same font settings and refreshes on changes`() {
        val first = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
        ).fontRegistry
        val second = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
        ).fontRegistry
        val changed = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            settings = DrawSettings(
                font = DrawFontSettings(text = testFontPath("NotoColorEmoji.ttf")),
            ),
        ).fontRegistry

        assertTrue(first === second)
        assertTrue(first !== changed)
    }

    @Test
    fun `draw config does not mutate explicit font registry`() {
        val fontRegistry = FontRegistry()

        val config = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            fontRegistry = fontRegistry,
        )

        assertTrue(config.fontRegistry === fontRegistry)
        assertNull(fontRegistry.textTypeface)
        assertNull(fontRegistry.emojiTypeface)
    }

    private fun testFontPath(name: String): String {
        val resource = requireNotNull(Thread.currentThread().contextClassLoader.getResource("font/$name"))
        return Paths.get(resource.toURI()).toAbsolutePath().normalize().toString()
    }
}
