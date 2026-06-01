package top.colter.dynamic.draw.resource

import org.jetbrains.skia.Data
import org.jetbrains.skia.Typeface
import top.colter.dynamic.DrawFontSettings
import top.colter.dynamic.core.tools.loggerFor
import top.colter.skiko.FontConfig
import top.colter.skiko.FontRegistry
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger = loggerFor<DrawFonts>()

internal object DrawFonts {
    private const val FONT_PATH = "font"
    private const val TEXT_FONT = "HarmonyOS_SansSC_Medium.ttf"
    private const val EMOJI_FONT = "NotoColorEmoji.ttf"

    private val bundledFontFiles = mutableMapOf<String, Path>()

    fun ensureDefaultFonts(fontRegistry: FontRegistry, settings: DrawFontSettings) {
        val previousDefault = fontRegistry.defaultFont
        val previousEmoji = fontRegistry.emojiFont

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

        val beforeFallbackDefault = fontRegistry.defaultFont
        val beforeFallbackEmoji = fontRegistry.emojiFont
        fontRegistry.ensureDefaultFont(FontConfig())

        if (beforeFallbackDefault == null && fontRegistry.defaultFont != null) {
            logger.info { "绘图正文字体已通过系统回退加载：family=${fontRegistry.defaultFont?.familyName}" }
        }
        if (beforeFallbackEmoji == null && fontRegistry.emojiFont != null) {
            logger.info { "绘图 Emoji 字体已通过系统回退加载：family=${fontRegistry.emojiFont?.familyName}" }
        }
        if (fontRegistry.defaultFont == null) {
            logger.warn { "绘图正文字体未加载成功：configured=${settings.text.ifBlank { "<blank>" }}" }
        }
        if (fontRegistry.emojiFont == null) {
            logger.warn { "绘图 Emoji 字体未加载成功：configured=${settings.emoji.ifBlank { "<blank>" }}" }
        }
        if (previousDefault !== fontRegistry.defaultFont || previousEmoji !== fontRegistry.emojiFont) {
            logger.info {
                "绘图字体加载结果：text=${fontRegistry.defaultFont.summary()}，emoji=${fontRegistry.emojiFont.summary()}"
            }
        }
    }

    private fun loadConfiguredFont(
        fontRegistry: FontRegistry,
        path: String,
        emoji: Boolean,
    ): Boolean {
        if (path.isBlank()) return false
        val normalized = resolveFontPath(path) ?: return false
        if (!Files.isRegularFile(normalized)) {
            logger.debug { "绘图${fontRole(emoji)}配置不是可读字体文件，继续按字体族名尝试：value=$path，resolved=$normalized" }
            return false
        }

        val previousDefault = fontRegistry.defaultFont
        val face = loadTypefaceFromFile(fontRegistry, normalized, emoji) ?: run {
            logger.warn { "用户配置的绘图${fontRole(emoji)}文件存在，但 Skia 未能加载：path=$normalized" }
            return false
        }

        if (emoji) {
            if (previousDefault == null) fontRegistry.defaultFont = null
            fontRegistry.emojiFont = face
        } else {
            fontRegistry.defaultFont = face
        }
        logger.info { "已加载用户配置绘图${fontRole(emoji)}文件：path=$normalized，family=${face.familyName}" }
        return true
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
            logger.info { "已加载用户配置绘图${fontRole(emoji)}字体族：configured=$familyName，family=${face.familyName}" }
            true
        }.onFailure {
            logger.warn(it) { "加载用户配置绘图${fontRole(emoji)}字体族失败：configured=$familyName" }
        }.getOrDefault(false)
    }

    private fun loadBundledTextFont(fontRegistry: FontRegistry): Boolean {
        val face = loadBundledTypeface(fontRegistry, TEXT_FONT, emoji = false) ?: return false
        fontRegistry.defaultFont = face
        logger.info { "已加载内置绘图正文字体：resource=$FONT_PATH/$TEXT_FONT，family=${face.familyName}" }
        return true
    }

    private fun loadBundledEmojiFont(fontRegistry: FontRegistry): Boolean {
        val previousDefault = fontRegistry.defaultFont
        val face = loadBundledTypeface(fontRegistry, EMOJI_FONT, emoji = true) ?: return false
        if (previousDefault == null) fontRegistry.defaultFont = null
        fontRegistry.emojiFont = face
        logger.info { "已加载内置绘图 Emoji 字体：resource=$FONT_PATH/$EMOJI_FONT，family=${face.familyName}" }
        return true
    }

    private fun loadBundledTypeface(fontRegistry: FontRegistry, name: String, emoji: Boolean): Typeface? {
        val bytes = loadResourceBytes(FONT_PATH, name) ?: run {
            logger.warn { "内置绘图${fontRole(emoji)}资源读取失败：resource=$FONT_PATH/$name" }
            return null
        }

        val fromData = runCatching {
            fontRegistry.loadTypeface(Data.makeFromBytes(bytes))
        }.onFailure {
            logger.warn(it) { "从内存加载内置绘图${fontRole(emoji)}失败：resource=$FONT_PATH/$name，bytes=${bytes.size}" }
        }.getOrNull()
        if (fromData != null) return fromData

        logger.warn { "从内存加载内置绘图${fontRole(emoji)}返回空，尝试临时文件回退：resource=$FONT_PATH/$name，bytes=${bytes.size}" }
        val file = bundledFontFile(name, bytes) ?: return null
        return loadTypefaceFromFile(fontRegistry, file, emoji)
    }

    private fun loadTypefaceFromFile(fontRegistry: FontRegistry, path: Path, emoji: Boolean): Typeface? {
        val fromFile = runCatching {
            if (emoji) fontRegistry.loadEmojiTypeface(path.toString(), null)
            else fontRegistry.loadTypeface(path.toString(), null)
        }.onFailure {
            logger.warn(it) { "从文件加载绘图${fontRole(emoji)}失败：path=$path" }
        }.getOrNull()
        if (fromFile != null) return fromFile

        logger.warn { "从文件加载绘图${fontRole(emoji)}返回空，尝试内存回退：path=$path" }
        return runCatching {
            fontRegistry.loadTypeface(Data.makeFromBytes(Files.readAllBytes(path)))
        }.onFailure {
            logger.warn(it) { "从内存回退加载绘图${fontRole(emoji)}失败：path=$path" }
        }.getOrNull()
    }

    private fun resolveFontPath(path: String): Path? {
        val text = path.trim()
        return runCatching {
            if (text.startsWith("file:", ignoreCase = true)) {
                Paths.get(URI.create(text))
            } else if (text == "~" || text.startsWith("~/") || text.startsWith("~\\")) {
                val home = System.getProperty("user.home").orEmpty()
                if (home.isBlank()) Paths.get(text)
                else Paths.get(home).resolve(text.removePrefix("~").trimStart('/', '\\'))
            } else {
                Paths.get(text)
            }.toAbsolutePath().normalize()
        }.onFailure {
            logger.debug(it) { "绘图字体配置不是有效路径，继续按字体族名尝试：value=$path" }
        }.getOrNull()
    }

    @Synchronized
    private fun bundledFontFile(name: String, bytes: ByteArray): Path? {
        bundledFontFiles[name]?.takeIf { Files.isRegularFile(it) }?.let { return it }
        return runCatching {
            Files.createTempFile("dynamic-bot-font-", "-$name").also { path ->
                Files.write(path, bytes)
                path.toFile().deleteOnExit()
                bundledFontFiles[name] = path
            }
        }.onFailure {
            logger.warn(it) { "写出内置绘图字体临时文件失败：resource=$FONT_PATH/$name" }
        }.getOrNull()
    }

    private fun fontRole(emoji: Boolean): String = if (emoji) "Emoji 字体" else "正文字体"

    private fun Typeface?.summary(): String {
        return if (this == null) "<null>" else "${familyName}(glyphs=$glyphsCount)"
    }
}
