package top.colter.dynamic.draw.resource

import org.jetbrains.skia.Data
import top.colter.dynamic.DrawFontSettings
import top.colter.skiko.FontConfig
import top.colter.skiko.FontRegistry
import java.nio.file.Files
import java.nio.file.Paths

internal object DrawFonts {
    private const val FONT_PATH = "font"
    private const val TEXT_FONT = "HarmonyOS_SansSC_Medium.ttf"
    private const val EMOJI_FONT = "NotoColorEmoji.ttf"

    fun ensureDefaultFonts(fontRegistry: FontRegistry, settings: DrawFontSettings) {
        if (fontRegistry.defaultFont == null) {
            loadConfiguredFont(fontRegistry, settings.textFontFile, settings.textFamily, emoji = false)
                || loadConfiguredFamily(fontRegistry, settings.textFamily, emoji = false)
                || loadBundledTextFont(fontRegistry)
        }

        if (fontRegistry.emojiFont == null) {
            loadConfiguredFont(fontRegistry, settings.emojiFontFile, settings.emojiFamily, emoji = true)
                || loadConfiguredFamily(fontRegistry, settings.emojiFamily, emoji = true)
                || loadBundledEmojiFont(fontRegistry)
        }

        fontRegistry.ensureDefaultFont(FontConfig())
    }

    private fun loadConfiguredFont(
        fontRegistry: FontRegistry,
        path: String,
        alias: String,
        emoji: Boolean,
    ): Boolean {
        if (path.isBlank()) return false
        val normalized = Paths.get(path).toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized)) return false

        return runCatching {
            val previousDefault = fontRegistry.defaultFont
            val face = if (emoji) {
                fontRegistry.loadEmojiTypeface(normalized.toString(), alias.takeIf { it.isNotBlank() })
            } else {
                fontRegistry.loadTypeface(normalized.toString(), alias.takeIf { it.isNotBlank() })
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
        if (family.isBlank()) return false

        return runCatching {
            val fontSet = fontRegistry.matchFamily(family)
            if (fontSet.count() <= 0) return@runCatching false

            val previousDefault = fontRegistry.defaultFont
            val face = fontSet.getTypeface(0) ?: return@runCatching false
            fontRegistry.loadTypeface(face, family)

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
}
