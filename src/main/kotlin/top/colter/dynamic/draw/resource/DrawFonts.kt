package top.colter.dynamic.draw.resource

import org.jetbrains.skia.Data
import top.colter.dynamic.DrawFontSettings
import top.colter.skiko.FontConfig
import top.colter.skiko.FontRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object DrawFonts {
    private const val FONT_PATH = "font"
    private const val TEXT_FONT = "HarmonyOS_SansSC_Medium.ttf"
    private const val EMOJI_FONT = "NotoColorEmoji.ttf"

    fun ensureDefaultFonts(fontRegistry: FontRegistry, settings: DrawFontSettings) {
        if (fontRegistry.defaultFont == null) {
            loadConfiguredFont(fontRegistry, settings.text, emoji = false)
                || loadConfiguredFamily(fontRegistry, settings.text, emoji = false)
                || loadBundledTextFont(fontRegistry)
        }

        if (fontRegistry.emojiFont == null) {
            loadConfiguredFont(fontRegistry, settings.emoji, emoji = true)
                || loadConfiguredFamily(fontRegistry, settings.emoji, emoji = true)
                || loadBundledEmojiFont(fontRegistry)
        }

        fontRegistry.ensureDefaultFont(FontConfig())
    }

    private fun loadConfiguredFont(
        fontRegistry: FontRegistry,
        path: String,
        emoji: Boolean,
    ): Boolean {
        if (path.isBlank()) return false
        val normalized = resolveFontPath(path) ?: return false
        if (!Files.isRegularFile(normalized)) return false

        return runCatching {
            val previousDefault = fontRegistry.defaultFont
            val face = if (emoji) {
                fontRegistry.loadEmojiTypeface(normalized.toString(), null)
            } else {
                fontRegistry.loadTypeface(normalized.toString(), null)
            } ?: return@runCatching false

            if (emoji) {
                if (previousDefault == null) fontRegistry.defaultFont = null
                fontRegistry.emojiFont = face
            } else {
                fontRegistry.defaultFont = face
            }
            true
        }.getOrDefault(false)
    }

    private fun loadConfiguredFamily(fontRegistry: FontRegistry, family: String, emoji: Boolean): Boolean {
        val familyName = family.trim()
        if (familyName.isBlank()) return false

        return runCatching {
            val fontSet = fontRegistry.matchFamily(familyName)
            if (fontSet.count() <= 0) return@runCatching false

            val previousDefault = fontRegistry.defaultFont
            val face = fontSet.getTypeface(0) ?: return@runCatching false
            fontRegistry.loadTypeface(face, familyName)

            if (emoji) {
                if (previousDefault == null) fontRegistry.defaultFont = null
                fontRegistry.emojiFont = face
            } else {
                fontRegistry.defaultFont = face
            }
            true
        }.getOrDefault(false)
    }

    private fun loadBundledTextFont(fontRegistry: FontRegistry): Boolean {
        val face = loadBundledTypeface(fontRegistry, TEXT_FONT) ?: return false
        fontRegistry.defaultFont = face
        return true
    }

    private fun loadBundledEmojiFont(fontRegistry: FontRegistry): Boolean {
        val previousDefault = fontRegistry.defaultFont
        val face = loadBundledTypeface(fontRegistry, EMOJI_FONT) ?: return false
        if (previousDefault == null) fontRegistry.defaultFont = null
        fontRegistry.emojiFont = face
        return true
    }

    private fun loadBundledTypeface(fontRegistry: FontRegistry, name: String) =
        loadResourceBytes(FONT_PATH, name)
            ?.let { Data.makeFromBytes(it) }
            ?.let { fontRegistry.loadTypeface(it) }

    private fun resolveFontPath(path: String): Path? {
        val text = path.trim()
        return runCatching {
            if (text == "~" || text.startsWith("~/") || text.startsWith("~\\")) {
                val home = System.getProperty("user.home").orEmpty()
                if (home.isBlank()) Paths.get(text)
                else Paths.get(home).resolve(text.removePrefix("~").trimStart('/', '\\'))
            } else {
                Paths.get(text)
            }.toAbsolutePath().normalize()
        }.getOrNull()
    }
}
