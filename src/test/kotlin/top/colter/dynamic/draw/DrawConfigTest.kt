import top.colter.dynamic.DrawFontSettings
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.draw.DrawConfig
import top.colter.skiko.FontRegistry
import org.jetbrains.skia.paragraph.TextStyle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun `draw config appends emoji fallback to normal text style`() {
        val config = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
        )

        val resolved = config.fontRegistry.resolveTextStyle(TextStyle().setFontSize(24f))

        assertContentEquals(arrayOf(FontRegistry.TEXT_FAMILY, FontRegistry.EMOJI_FAMILY), resolved.fontFamilies)
    }

    @Test
    fun `draw config appends normal data font fallback before emoji fallback`() {
        withDataFontDirectory(mapOf("NotoColorEmoji.ttf" to "SymbolFallback.ttf")) {
            val config = DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            )

            val resolved = config.fontRegistry.resolveTextStyle(TextStyle().setFontSize(24f))

            assertContentEquals(
                arrayOf(FontRegistry.TEXT_FAMILY, FontRegistry.TEXT_FALLBACK_FAMILY, FontRegistry.EMOJI_FAMILY),
                resolved.fontFamilies,
            )
        }
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
    fun `draw config loads first available configured font from semicolon list`() {
        val config = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            settings = DrawSettings(
                font = DrawFontSettings(
                    text = "__missing_text_family__;${testFontPath("NotoColorEmoji.ttf")};${testFontPath("HarmonyOS_SansSC_Medium.ttf")}",
                ),
            ),
        )

        assertEquals("Noto Color Emoji", assertNotNull(config.fontRegistry.textTypeface).familyName)
    }

    @Test
    fun `draw config matches bundled font with relaxed family name`() {
        val bundledTextFamily = assertNotNull(
            DrawConfig(platform = PlatformDescriptor.of("bilibili", "Bilibili")).fontRegistry.textTypeface,
        ).familyName

        val config = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            settings = DrawSettings(
                font = DrawFontSettings(text = "HarmonyOS Sans SC Medium"),
            ),
        )

        assertEquals(bundledTextFamily, assertNotNull(config.fontRegistry.textTypeface).familyName)
    }

    @Test
    fun `draw config can resolve configured font file name from data font directory`() {
        withDataFontDirectory("NotoColorEmoji.ttf") {
            val config = DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
                settings = DrawSettings(
                    font = DrawFontSettings(text = "NotoColorEmoji.ttf"),
                ),
            )

            assertEquals("Noto Color Emoji", assertNotNull(config.fontRegistry.textTypeface).familyName)
        }
    }

    @Test
    fun `draw config does not auto add normal data fonts to emoji fallback`() {
        withDataFontDirectory("HarmonyOS_SansSC_Medium.ttf") {
            val config = DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            )

            assertEquals(
                "HarmonyOS Sans SC",
                assertNotNull(config.fontRegistry.fontRegistryFallback("HarmonyOS_SansSC_Medium.ttf")).familyName,
            )
            assertTrue("HarmonyOS Sans SC" !in config.fontRegistry.familyNames(FontRegistry.EMOJI_FAMILY))
        }
    }

    @Test
    fun `draw config keeps primary families before data font fallbacks`() {
        withDataFontDirectory(mapOf("NotoColorEmoji.ttf" to "CustomEmoji.ttf")) {
            val config = DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            )
            val primaryFamily = assertNotNull(config.fontRegistry.textTypeface).familyName
            val textFamilyFirst = assertNotNull(config.fontRegistry.fontRegistryFallback(FontRegistry.TEXT_FAMILY)).familyName
            val emojiFamily = assertNotNull(config.fontRegistry.emojiTypeface).familyName
            val emojiFamilyFirst = assertNotNull(config.fontRegistry.fontRegistryFallback(FontRegistry.EMOJI_FAMILY)).familyName
            val resolved = config.fontRegistry.resolveTextStyle(TextStyle().setFontSize(24f))

            assertEquals(primaryFamily, textFamilyFirst)
            assertEquals(emojiFamily, emojiFamilyFirst)
            assertContentEquals(arrayOf(FontRegistry.TEXT_FAMILY, FontRegistry.EMOJI_FAMILY), resolved.fontFamilies)
            assertTrue("Noto Color Emoji" !in config.fontRegistry.familyNames(FontRegistry.TEXT_FAMILY))
            assertTrue("Noto Color Emoji" !in config.fontRegistry.familyNames(FontRegistry.TEXT_FALLBACK_FAMILY))
            assertTrue("Noto Color Emoji" in config.fontRegistry.familyNames(FontRegistry.EMOJI_FAMILY))
        }
    }

    @Test
    fun `draw config falls back to bundled alias when configured font file cannot be loaded`() {
        val bundledTextFamily = assertNotNull(
            DrawConfig(platform = PlatformDescriptor.of("bilibili", "Bilibili")).fontRegistry.textTypeface,
        ).familyName
        val dataFontDir = Files.createTempDirectory("dynamic-bot-font-test-")
        val brokenFont = dataFontDir.resolve("HarmonyOS_SansSC_Medium.ttf")
        Files.writeString(brokenFont, "not a font")

        withDataFontDirectory(dataFontDir) {
            val config = DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
                settings = DrawSettings(
                    font = DrawFontSettings(text = brokenFont.toString()),
                ),
            )

            assertEquals(bundledTextFamily, assertNotNull(config.fontRegistry.textTypeface).familyName)
        }
    }

    @Test
    fun `draw config refreshes cached registry when data font directory changes`() {
        val dataFontDir = Files.createTempDirectory("dynamic-bot-font-test-")
        withDataFontDirectory(dataFontDir) {
            val first = DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
                settings = DrawSettings(font = DrawFontSettings(text = "first-cache-check")),
            ).fontRegistry

            Files.copy(testFontResource("NotoColorEmoji.ttf"), dataFontDir.resolve("NotoColorEmoji.ttf"))

            val second = DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
                settings = DrawSettings(font = DrawFontSettings(text = "first-cache-check")),
            ).fontRegistry

            assertTrue(first !== second)
            assertEquals("Noto Color Emoji", assertNotNull(second.fontRegistryFallback("NotoColorEmoji")).familyName)
        }
    }

    @Test
    fun `draw config refreshes cached registry when configured font file changes`() {
        val fontFile = Files.createTempFile("dynamic-bot-font-test-", ".ttf")
        Files.copy(testFontResource("NotoColorEmoji.ttf"), fontFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val settings = DrawSettings(font = DrawFontSettings(text = fontFile.toString()))

        val first = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            settings = settings,
        ).fontRegistry

        Files.copy(testFontResource("HarmonyOS_SansSC_Medium.ttf"), fontFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val second = DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            settings = settings,
        ).fontRegistry

        assertTrue(first !== second)
        assertEquals("HarmonyOS Sans SC", assertNotNull(second.textTypeface).familyName)
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

    private fun FontRegistry.fontRegistryFallback(name: String) =
        matchFamily(name).getTypeface(0)

    private fun FontRegistry.familyNames(name: String): Set<String> {
        val fontSet = matchFamily(name)
        return (0 until fontSet.count())
            .mapNotNull { fontSet.getTypeface(it)?.familyName }
            .toSet()
    }

    private fun testFontPath(name: String): String {
        return testFontResource(name).toString()
    }

    private fun testFontResource(name: String): Path {
        val resource = requireNotNull(Thread.currentThread().contextClassLoader.getResource("font/$name"))
        return Paths.get(resource.toURI()).toAbsolutePath().normalize()
    }

    private fun withDataFontDirectory(vararg fontNames: String, block: () -> Unit) {
        val dataFontDir = Files.createTempDirectory("dynamic-bot-font-test-")
        fontNames.forEach { name ->
            Files.copy(testFontResource(name), dataFontDir.resolve(name))
        }
        withDataFontDirectory(dataFontDir, block)
    }

    private fun withDataFontDirectory(fontNames: Map<String, String>, block: () -> Unit) {
        val dataFontDir = Files.createTempDirectory("dynamic-bot-font-test-")
        fontNames.forEach { (resourceName, fileName) ->
            Files.copy(testFontResource(resourceName), dataFontDir.resolve(fileName))
        }
        withDataFontDirectory(dataFontDir, block)
    }

    private fun withDataFontDirectory(dataFontDir: Path, block: () -> Unit) {
        val previous = System.getProperty("dynamic.bot.fonts.dir")
        System.setProperty("dynamic.bot.fonts.dir", dataFontDir.toAbsolutePath().normalize().toString())
        try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("dynamic.bot.fonts.dir")
            } else {
                System.setProperty("dynamic.bot.fonts.dir", previous)
            }
        }
    }
}
