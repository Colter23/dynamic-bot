package top.colter.dynamic.draw.resource

import org.jetbrains.skia.Data
import org.jetbrains.skia.Typeface
import top.colter.dynamic.DrawFontSettings
import top.colter.dynamic.core.tools.loggerFor
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

    private var cachedSettings: DrawFontSettings? = null
    private var cachedRegistry: FontRegistry? = null

    @Synchronized
    fun registry(settings: DrawFontSettings): FontRegistry {
        cachedRegistry?.takeIf { cachedSettings == settings }?.let {
            logger.debug { "绘图字体使用缓存：settings=$settings" }
            return it
        }

        val fontRegistry = FontRegistry()
        val text = loadTextFont(fontRegistry, settings.text)
        val emoji = loadEmojiFont(fontRegistry, settings.emoji)

        if (text == null) {
            logger.warn { "绘图正文字体未加载成功：configured=${settings.text.ifBlank { "<blank>" }}" }
        }
        if (emoji == null) {
            logger.warn { "绘图 Emoji 字体未加载成功：configured=${settings.emoji.ifBlank { "<blank>" }}" }
        }

        logger.info {
            "绘图字体加载结果：text=${text.summary()}，emoji=${emoji.summary()}"
        }

        cachedSettings = settings
        cachedRegistry = fontRegistry
        return fontRegistry
    }

    private fun loadTextFont(fontRegistry: FontRegistry, setting: String): FontLoadResult? {
        return loadConfiguredFont(fontRegistry, setting, emoji = false)
            ?: loadConfiguredFamily(fontRegistry, setting, emoji = false)
            ?: loadBundledFont(fontRegistry, TEXT_FONT, emoji = false)
            ?: loadSystemFont(fontRegistry, emoji = false)
    }

    private fun loadEmojiFont(fontRegistry: FontRegistry, setting: String): FontLoadResult? {
        return loadConfiguredFont(fontRegistry, setting, emoji = true)
            ?: loadConfiguredFamily(fontRegistry, setting, emoji = true)
            ?: loadBundledFont(fontRegistry, EMOJI_FONT, emoji = true)
            ?: loadSystemFont(fontRegistry, emoji = true)
    }

    private fun loadConfiguredFont(
        fontRegistry: FontRegistry,
        path: String,
        emoji: Boolean,
    ): FontLoadResult? {
        if (path.isBlank()) return null
        val normalized = resolveFontPath(path) ?: return null
        if (!Files.isRegularFile(normalized)) {
            logger.debug { "绘图${fontRole(emoji)}配置不是可读字体文件，继续按字体族名尝试：value=$path，resolved=$normalized" }
            return null
        }

        val face = loadTypefaceFromFile(fontRegistry, normalized, emoji, alias = null) ?: run {
            logger.warn { "用户配置的绘图${fontRole(emoji)}文件存在，但 Skia 未能加载：path=$normalized" }
            return null
        }

        logger.info { "已加载用户配置绘图${fontRole(emoji)}文件：path=$normalized，family=${face.familyName}，glyphs=${face.glyphsCount}" }
        return FontLoadResult("file:$normalized", face)
    }

    private fun loadConfiguredFamily(fontRegistry: FontRegistry, family: String, emoji: Boolean): FontLoadResult? {
        val familyName = family.trim()
        if (familyName.isBlank()) return null

        return runCatching {
            val fontSet = fontRegistry.matchFamily(familyName)
            if (fontSet.count() <= 0) return@runCatching null

            val face = fontSet.getTypeface(0) ?: return@runCatching null
            if (emoji) {
                fontRegistry.registerEmojiTypeface(face, familyName)
            } else {
                fontRegistry.registerTextTypeface(face, familyName)
            }
            logger.info { "已加载用户配置绘图${fontRole(emoji)}字体族：configured=$familyName，family=${face.familyName}，glyphs=${face.glyphsCount}" }
            FontLoadResult("family:$familyName", face)
        }.onFailure {
            logger.warn(it) { "加载用户配置绘图${fontRole(emoji)}字体族失败：configured=$familyName" }
        }.getOrNull()
    }

    private fun loadBundledFont(fontRegistry: FontRegistry, name: String, emoji: Boolean): FontLoadResult? {
        val face = loadBundledTypeface(fontRegistry, name, emoji) ?: return null
        logger.info { "已加载内置绘图${fontRole(emoji)}：resource=$FONT_PATH/$name，family=${face.familyName}，glyphs=${face.glyphsCount}" }
        return FontLoadResult("resource:$FONT_PATH/$name", face)
    }

    private fun loadBundledTypeface(fontRegistry: FontRegistry, name: String, emoji: Boolean): Typeface? {
        val bytes = loadResourceBytes(FONT_PATH, name) ?: run {
            logger.warn { "内置绘图${fontRole(emoji)}资源读取失败：resource=$FONT_PATH/$name" }
            return null
        }

        val fromData = runCatching {
            loadTypefaceFromData(fontRegistry, bytes, emoji, alias = name)
        }.onFailure {
            logger.warn(it) { "从内存加载内置绘图${fontRole(emoji)}失败：resource=$FONT_PATH/$name，bytes=${bytes.size}" }
        }.getOrNull()
        if (fromData != null) return fromData

        logger.warn { "从内存加载内置绘图${fontRole(emoji)}返回空，尝试临时文件回退：resource=$FONT_PATH/$name，bytes=${bytes.size}" }
        val file = bundledFontFile(name, bytes) ?: return null
        return loadTypefaceFromFile(fontRegistry, file, emoji, alias = name)
    }

    private fun loadSystemFont(fontRegistry: FontRegistry, emoji: Boolean): FontLoadResult? {
        val families = if (emoji) SYSTEM_EMOJI_FONT_FAMILIES else SYSTEM_TEXT_FONT_FAMILIES
        families.forEach { family ->
            loadConfiguredFamily(fontRegistry, family, emoji)?.let {
                return it.copy(source = "system-family:$family")
            }
        }

        val paths = if (emoji) SYSTEM_EMOJI_FONT_PATHS else SYSTEM_TEXT_FONT_PATHS
        paths.forEach { path ->
            val normalized = path.toAbsolutePath().normalize()
            if (!Files.isRegularFile(normalized)) return@forEach
            loadTypefaceFromFile(fontRegistry, normalized, emoji, alias = null)?.let { face ->
                logger.info { "已加载系统回退绘图${fontRole(emoji)}文件：path=$normalized，family=${face.familyName}，glyphs=${face.glyphsCount}" }
                return FontLoadResult("system-file:$normalized", face)
            }
        }

        return null
    }

    private fun loadTypefaceFromFile(
        fontRegistry: FontRegistry,
        path: Path,
        emoji: Boolean,
        alias: String?,
    ): Typeface? {
        val fromFile = runCatching {
            if (emoji) {
                fontRegistry.loadEmojiTypeface(path.toString(), alias)
            } else {
                fontRegistry.loadTextTypeface(path.toString(), alias)
            }
        }.onFailure {
            logger.warn(it) { "从文件加载绘图${fontRole(emoji)}失败：path=$path" }
        }.getOrNull()
        if (fromFile != null) return fromFile

        logger.warn { "从文件加载绘图${fontRole(emoji)}返回空，尝试内存回退：path=$path" }
        return runCatching {
            loadTypefaceFromData(fontRegistry, Files.readAllBytes(path), emoji, alias)
        }.onFailure {
            logger.warn(it) { "从内存回退加载绘图${fontRole(emoji)}失败：path=$path" }
        }.getOrNull()
    }

    private fun loadTypefaceFromData(
        fontRegistry: FontRegistry,
        bytes: ByteArray,
        emoji: Boolean,
        alias: String?,
    ): Typeface? {
        val data = Data.makeFromBytes(bytes)
        return if (emoji) {
            fontRegistry.loadEmojiTypeface(data, alias)
        } else {
            fontRegistry.loadTextTypeface(data, alias)
        }
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

    private fun FontLoadResult?.summary(): String {
        if (this == null) return "<null>"
        return "${typeface.familyName}(glyphs=${typeface.glyphsCount}, source=$source)"
    }

    private data class FontLoadResult(
        val source: String,
        val typeface: Typeface,
    )

    private val SYSTEM_TEXT_FONT_FAMILIES: List<String> = listOf(
        "Microsoft YaHei",
        "Microsoft YaHei UI",
        "SimHei",
        "SimSun",
        "PingFang SC",
        "Heiti SC",
        "Noto Sans CJK SC",
        "Noto Sans CJK",
        "Noto Sans",
        "WenQuanYi Micro Hei",
        "DejaVu Sans",
        "Segoe UI",
    )

    private val SYSTEM_EMOJI_FONT_FAMILIES: List<String> = listOf(
        "Segoe UI Emoji",
        "Apple Color Emoji",
        "Noto Color Emoji",
    )

    private val SYSTEM_TEXT_FONT_PATHS: List<Path> = buildList {
        System.getenv("WINDIR")?.takeIf { it.isNotBlank() }?.let { windowsDir ->
            val fonts = Paths.get(windowsDir, "Fonts")
            add(fonts.resolve("msyh.ttc"))
            add(fonts.resolve("msyhbd.ttc"))
            add(fonts.resolve("simhei.ttf"))
            add(fonts.resolve("simsun.ttc"))
        }
        add(Paths.get("/System/Library/Fonts/PingFang.ttc"))
        add(Paths.get("/System/Library/Fonts/STHeiti Light.ttc"))
        add(Paths.get("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"))
        add(Paths.get("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"))
        add(Paths.get("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
    }

    private val SYSTEM_EMOJI_FONT_PATHS: List<Path> = buildList {
        System.getenv("WINDIR")?.takeIf { it.isNotBlank() }?.let { windowsDir ->
            add(Paths.get(windowsDir, "Fonts", "seguiemj.ttf"))
        }
        add(Paths.get("/System/Library/Fonts/Apple Color Emoji.ttc"))
        add(Paths.get("/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf"))
    }
}
