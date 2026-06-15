package top.colter.dynamic.draw.resource

import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface
import top.colter.dynamic.DrawFontSettings
import top.colter.dynamic.DrawTypographySettings
import top.colter.dynamic.core.tools.loggerFor
import top.colter.skiko.FontRegistry
import top.colter.skiko.FontTypographySettings
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger = loggerFor<DrawFonts>()

internal object DrawFonts {
    private const val FONT_PATH = "font"
    private const val TEXT_FONT = "HarmonyOS_SansSC_Medium.ttf"
    private const val EMOJI_FONT = "NotoColorEmoji.ttf"
    private const val DEFAULT_DATA_FONT_DIR = "data/fonts"
    private const val DATA_FONT_DIR_PROPERTY = "dynamic.bot.fonts.dir"
    private const val DATA_FONT_DIR_ENV = "DYNAMIC_BOT_FONTS_DIR"

    private val fontMgr: FontMgr = FontMgr.default
    private val bundledFontFiles = mutableMapOf<String, Path>()

    private var cachedKey: FontCacheKey? = null
    private var cachedRegistry: FontRegistry? = null
    private var cachedDataFontsRoot: Path? = null
    private var cachedDataFontsRootSignature: Long? = null
    private var cachedDataFontFiles: List<Path> = emptyList()

    @Synchronized
    fun registry(settings: DrawFontSettings): FontRegistry {
        val dataFontsRoot = dataFontsRoot()
        val dataFontFiles = dataFontFiles(dataFontsRoot)
        val cacheKey = FontCacheKey(
            settings = settings,
            dataFontsRoot = dataFontsRoot.toString(),
            dataFonts = dataFontFiles.map(::dataFontSnapshot),
        )
        cachedRegistry?.takeIf { cachedKey == cacheKey }?.let {
            logger.debug { "绘图字体使用缓存：settings=$settings，dataFontsRoot=$dataFontsRoot" }
            return it
        }

        val fontRegistry = FontRegistry()
        fontRegistry.typographySettings = settings.typography.toFontTypographySettings()
        val text = loadTextFonts(fontRegistry, settings.text, dataFontFiles)
        val emoji = loadEmojiFonts(fontRegistry, settings.emoji, dataFontFiles)

        if (text.primary == null) {
            logger.warn { "绘图正文字体未加载成功：configured=${settings.text.ifBlank { "<blank>" }}，dataFontsRoot=$dataFontsRoot" }
        }
        if (emoji.primary == null) {
            logger.warn { "绘图 Emoji 字体未加载成功：configured=${settings.emoji.ifBlank { "<blank>" }}，dataFontsRoot=$dataFontsRoot" }
        }

        logger.info {
            "绘图字体加载结果：text=${text.summary()}，emoji=${emoji.summary()}，dataFontsRoot=$dataFontsRoot"
        }

        cachedKey = cacheKey
        cachedRegistry = fontRegistry
        return fontRegistry
    }

    private fun loadTextFonts(
        fontRegistry: FontRegistry,
        setting: String,
        dataFontFiles: List<Path>,
    ): FontLoadGroup {
        return loadFontGroup(
            fontRegistry = fontRegistry,
            setting = setting,
            dataFontFiles = dataFontFiles,
            bundledFont = TEXT_FONT,
            emoji = false,
        )
    }

    private fun loadEmojiFonts(
        fontRegistry: FontRegistry,
        setting: String,
        dataFontFiles: List<Path>,
    ): FontLoadGroup {
        return loadFontGroup(
            fontRegistry = fontRegistry,
            setting = setting,
            dataFontFiles = dataFontFiles,
            bundledFont = EMOJI_FONT,
            emoji = true,
        )
    }

    private fun loadFontGroup(
        fontRegistry: FontRegistry,
        setting: String,
        dataFontFiles: List<Path>,
        bundledFont: String,
        emoji: Boolean,
    ): FontLoadGroup {
        val dataFonts = dataFontFiles.mapNotNull { path ->
            loadFileCandidate(path, emoji, source = "data-font:${path.toAbsolutePath().normalize()}")
        }
        val bundled = loadBundledCandidate(bundledFont, emoji)
        val configured = loadConfiguredCandidates(
            fontRegistry = fontRegistry,
            setting = setting,
            dataFontFiles = dataFontFiles,
            namedCandidates = listOfNotNull(bundled) + dataFonts,
            emoji = emoji,
        )
        val system = loadSystemCandidate(fontRegistry, emoji)

        val ordered = if (configured.isNotEmpty()) {
            configured + dataFonts + listOfNotNull(bundled) + listOfNotNull(system)
        } else {
            listOfNotNull(bundled) + dataFonts + listOfNotNull(system)
        }
        val unique = uniqueCandidates(ordered)
        val primary = unique.firstOrNull()
        primary?.registerAsPrimary(fontRegistry, emoji)
        unique.drop(1).forEach { it.registerAsFallback(fontRegistry, emoji) }

        return FontLoadGroup(
            primary = primary?.toResult(),
            fallbacks = unique.drop(1).map { it.toResult() },
        )
    }

    private fun loadConfiguredCandidates(
        fontRegistry: FontRegistry,
        setting: String,
        dataFontFiles: List<Path>,
        namedCandidates: List<FontCandidate>,
        emoji: Boolean,
    ): List<FontCandidate> {
        val knownCandidates = namedCandidates.toMutableList()
        return parseFontEntries(setting).mapNotNull { entry ->
            loadConfiguredCandidate(
                fontRegistry = fontRegistry,
                entry = entry,
                dataFontFiles = dataFontFiles,
                namedCandidates = knownCandidates,
                emoji = emoji,
            )?.also {
                knownCandidates += it
            }
        }
    }

    private fun loadConfiguredCandidate(
        fontRegistry: FontRegistry,
        entry: String,
        dataFontFiles: List<Path>,
        namedCandidates: List<FontCandidate>,
        emoji: Boolean,
    ): FontCandidate? {
        configuredFontFile(entry, dataFontFiles)?.let { path ->
            val normalized = path.toAbsolutePath().normalize()
            loadFileCandidate(normalized, emoji, source = "configured-file:$normalized")?.let { candidate ->
                return candidate
            }
            logger.warn { "用户配置的绘图${fontRole(emoji)}文件存在，但 Skia 未能加载：path=$normalized" }
            return loadConfiguredAliases(
                fontRegistry = fontRegistry,
                entry = entry,
                aliases = fontAliases(normalized),
                namedCandidates = namedCandidates,
                emoji = emoji,
            )
        }

        return loadConfiguredAliases(
            fontRegistry = fontRegistry,
            entry = entry,
            aliases = fontAliases(entry),
            namedCandidates = namedCandidates,
            emoji = emoji,
        )
    }

    private fun loadConfiguredAliases(
        fontRegistry: FontRegistry,
        entry: String,
        aliases: List<String>,
        namedCandidates: List<FontCandidate>,
        emoji: Boolean,
    ): FontCandidate? {
        aliases.forEach { alias ->
            namedCandidates.firstOrNull { it.matchesName(alias) }?.let { candidate ->
                logger.debug {
                    "已匹配用户配置绘图${fontRole(emoji)}：configured=$entry，alias=$alias，family=${candidate.typeface.familyName}，source=${candidate.source}"
                }
                return candidate.copy(
                    source = "configured:$entry -> ${candidate.source}",
                    aliases = (candidate.aliases + entry + aliases).distinct(),
                )
            }

            loadFamilyCandidate(
                fontRegistry = fontRegistry,
                family = alias,
                emoji = emoji,
                source = "configured-family:$alias",
            )?.let { return it }
        }

        return null
    }

    private fun loadBundledCandidate(name: String, emoji: Boolean): FontCandidate? {
        val bytes = loadResourceBytes(FONT_PATH, name) ?: run {
            logger.warn { "内置绘图${fontRole(emoji)}资源读取失败：resource=$FONT_PATH/$name" }
            return null
        }

        val source = "resource:$FONT_PATH/$name"
        val fromData = runCatching {
            makeTypefaceFromData(bytes)
        }.onFailure {
            logger.warn(it) { "从内存加载内置绘图${fontRole(emoji)}失败：resource=$FONT_PATH/$name，bytes=${bytes.size}" }
        }.getOrNull()
        if (fromData != null) {
            logger.debug { "已读取内置绘图${fontRole(emoji)}：resource=$FONT_PATH/$name，family=${fromData.familyName}，glyphs=${fromData.glyphsCount}" }
            return FontCandidate(
                source = source,
                key = source,
                typeface = fromData,
                aliases = fontAliases(name) + fontAliases(fromData.familyName),
            )
        }

        logger.warn { "从内存加载内置绘图${fontRole(emoji)}返回空，尝试临时文件回退：resource=$FONT_PATH/$name，bytes=${bytes.size}" }
        val file = bundledFontFile(name, bytes) ?: return null
        return loadFileCandidate(file, emoji, source = source)
    }

    private fun loadSystemCandidate(fontRegistry: FontRegistry, emoji: Boolean): FontCandidate? {
        val families = if (emoji) SYSTEM_EMOJI_FONT_FAMILIES else SYSTEM_TEXT_FONT_FAMILIES
        families.forEach { family ->
            loadFamilyCandidate(
                fontRegistry = fontRegistry,
                family = family,
                emoji = emoji,
                source = "system-family:$family",
            )?.let { return it }
        }

        val paths = if (emoji) SYSTEM_EMOJI_FONT_PATHS else SYSTEM_TEXT_FONT_PATHS
        paths.forEach { path ->
            val normalized = path.toAbsolutePath().normalize()
            if (!Files.isRegularFile(normalized)) return@forEach
            loadFileCandidate(normalized, emoji, source = "system-file:$normalized")?.let {
                return it
            }
        }

        return null
    }

    private fun loadFamilyCandidate(
        fontRegistry: FontRegistry,
        family: String,
        emoji: Boolean,
        source: String,
    ): FontCandidate? {
        val familyName = family.trim()
        if (familyName.isBlank()) return null

        return runCatching {
            val fontSet = fontRegistry.matchFamily(familyName)
            if (fontSet.count() <= 0) return@runCatching null

            val face = fontSet.getTypeface(0) ?: return@runCatching null
            logger.debug { "已解析绘图${fontRole(emoji)}字体族：configured=$familyName，family=${face.familyName}，glyphs=${face.glyphsCount}" }
            FontCandidate(
                source = source,
                key = "family:${familyName.lowercase()}:${face.familyName.lowercase()}",
                typeface = face,
                aliases = fontAliases(familyName) + fontAliases(face.familyName),
            )
        }.onFailure {
            logger.warn(it) { "解析绘图${fontRole(emoji)}字体族失败：configured=$familyName" }
        }.getOrNull()
    }

    private fun loadFileCandidate(path: Path, emoji: Boolean, source: String): FontCandidate? {
        val normalized = path.toAbsolutePath().normalize()
        val face = makeTypefaceFromFile(normalized, emoji) ?: return null
        logger.debug { "已读取绘图${fontRole(emoji)}文件：source=$source，family=${face.familyName}，glyphs=${face.glyphsCount}" }
        return FontCandidate(
            source = source,
            key = "file:$normalized",
            typeface = face,
            aliases = fontAliases(normalized) + fontAliases(face.familyName),
        )
    }

    private fun makeTypefaceFromFile(path: Path, emoji: Boolean): Typeface? {
        val fromFile = runCatching {
            fontMgr.makeFromFile(path.toString(), 0)
        }.onFailure {
            logger.warn(it) { "从文件读取绘图${fontRole(emoji)}失败：path=$path" }
        }.getOrNull()
        if (fromFile != null) return fromFile

        logger.warn { "从文件读取绘图${fontRole(emoji)}返回空，尝试内存回退：path=$path" }
        return runCatching {
            makeTypefaceFromData(Files.readAllBytes(path))
        }.onFailure {
            logger.warn(it) { "从内存回退读取绘图${fontRole(emoji)}失败：path=$path" }
        }.getOrNull()
    }

    private fun makeTypefaceFromData(bytes: ByteArray): Typeface? {
        return fontMgr.makeFromData(Data.makeFromBytes(bytes), 0)
    }

    private fun FontCandidate.registerAsPrimary(fontRegistry: FontRegistry, emoji: Boolean) {
        val primaryAlias = aliases.firstOrNull()
        if (emoji) {
            fontRegistry.registerEmojiTypeface(typeface, primaryAlias)
        } else {
            fontRegistry.registerTextTypeface(typeface, primaryAlias)
        }
        aliases.drop(1).forEach { alias ->
            fontRegistry.registerTypeface(typeface, alias)
        }
        logger.debug { "已加载主绘图${fontRole(emoji)}：${toResult().summary()}" }
    }

    private fun FontCandidate.registerAsFallback(fontRegistry: FontRegistry, emoji: Boolean) {
        fontRegistry.registerTypeface(typeface)
        fontRegistry.registerTypeface(typeface, if (emoji) FontRegistry.EMOJI_FAMILY else FontRegistry.TEXT_FAMILY)
        aliases.forEach { alias ->
            fontRegistry.registerTypeface(typeface, alias)
        }
        logger.debug { "已加载回退绘图${fontRole(emoji)}：${toResult().summary()}" }
    }

    private fun configuredFontFile(entry: String, dataFontFiles: List<Path>): Path? {
        val text = entry.trim()
        if (text.isBlank()) return null

        val candidates = buildList {
            resolveFontPath(text)?.let { add(it) }
            dataFontsRoot().resolve(text).toAbsolutePath().normalize().let { add(it) }
        }
        candidates.distinct().firstOrNull { Files.isRegularFile(it) }?.let {
            return it
        }

        val configuredFileName = runCatching { Paths.get(text).fileName?.toString() }.getOrNull()
            ?: return null
        return dataFontFiles.firstOrNull {
            it.fileName.toString().equals(configuredFileName, ignoreCase = true)
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

    private fun dataFontsRoot(): Path {
        val configured = System.getProperty(DATA_FONT_DIR_PROPERTY)
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv(DATA_FONT_DIR_ENV)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DATA_FONT_DIR
        return Paths.get(configured).toAbsolutePath().normalize()
    }

    private fun dataFontFiles(root: Path): List<Path> {
        val rootSignature = dataFontsRootSignature(root)
        cachedDataFontsRoot?.takeIf { it == root && cachedDataFontsRootSignature == rootSignature }?.let {
            return cachedDataFontFiles
        }

        if (!Files.isDirectory(root)) {
            cachedDataFontsRoot = root
            cachedDataFontsRootSignature = rootSignature
            cachedDataFontFiles = emptyList()
            return emptyList()
        }

        return runCatching {
            Files.newDirectoryStream(root) { path ->
                Files.isRegularFile(path) && path.isSupportedFontFile()
            }.use { stream ->
                buildList {
                    stream.forEach { add(it) }
                }
                    .map { it.toAbsolutePath().normalize() }
                    .sortedBy { it.fileName.toString().lowercase() }
                    .also { files ->
                        cachedDataFontsRoot = root
                        cachedDataFontsRootSignature = rootSignature
                        cachedDataFontFiles = files
                    }
            }
        }.onFailure {
            logger.warn(it) { "读取绘图字体目录失败：path=$root" }
        }.getOrDefault(cachedDataFontFiles.takeIf { cachedDataFontsRoot == root && cachedDataFontsRootSignature == rootSignature } ?: emptyList())
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

    private fun parseFontEntries(setting: String): List<String> {
        return setting.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun uniqueCandidates(candidates: List<FontCandidate>): List<FontCandidate> {
        val keys = linkedSetOf<String>()
        return candidates.filter { keys.add(it.key) }
    }

    private fun fontAliases(path: Path): List<String> = fontAliases(path.fileName.toString())

    private fun fontAliases(name: String): List<String> {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val stem = fileName.removeSupportedFontExtension()
        val words = splitFontNameWords(stem)
        val displayName = words.joinToString(" ")
        val displayNameWithoutStyle = stripTrailingStyleWords(words).joinToString(" ")
        return listOf(fileName, stem, displayName, displayNameWithoutStyle)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun FontCandidate.matchesName(name: String): Boolean {
        val text = name.trim()
        val requestedKeys = fontNameKeys(text)
        val candidateKeys = fontNameKeys(typeface.familyName) + aliases.flatMap(::fontNameKeys)
        return typeface.familyName.equals(text, ignoreCase = true) ||
            aliases.any { it.equals(text, ignoreCase = true) } ||
            requestedKeys.any { it in candidateKeys }
    }

    private fun fontNameKeys(name: String): Set<String> {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val stem = fileName.removeSupportedFontExtension()
        val words = splitFontNameWords(stem)
        val withoutStyle = stripTrailingStyleWords(words)
        return buildSet {
            addFontNameKey(stem)
            addFontNameKey(words.joinToString(" "))
            addFontNameKey(withoutStyle.joinToString(" "))
        }
    }

    private fun MutableSet<String>.addFontNameKey(name: String) {
        val key = name.lowercase().filter { it.isLetterOrDigit() }
        if (key.isNotEmpty()) add(key)
    }

    private fun splitFontNameWords(name: String): List<String> {
        return name
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .split(Regex("[\\s_.\\-]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun stripTrailingStyleWords(words: List<String>): List<String> {
        val trimmed = words.dropLastWhile { it.lowercase() in FONT_STYLE_SUFFIXES }
        return trimmed.ifEmpty { words }
    }

    private fun String.removeSupportedFontExtension(): String {
        val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (extension in FONT_EXTENSIONS) substringBeforeLast('.') else this
    }

    private fun Path.isSupportedFontFile(): Boolean {
        val fileName = fileName.toString()
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in FONT_EXTENSIONS
    }

    private fun dataFontSnapshot(path: Path): DataFontSnapshot {
        return DataFontSnapshot(
            path = path.toString(),
            size = runCatching { Files.size(path) }.getOrDefault(0L),
            lastModifiedMillis = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L),
        )
    }

    private fun dataFontsRootSignature(root: Path): Long? {
        if (!Files.isDirectory(root)) return null
        return runCatching { Files.getLastModifiedTime(root).toMillis() }.getOrNull()
    }

    private fun fontRole(emoji: Boolean): String = if (emoji) "Emoji 字体" else "正文字体"

    private fun DrawTypographySettings.toFontTypographySettings(): FontTypographySettings {
        return FontTypographySettings(
            normalizeLineHeight = autoNormalize,
            lineHeightScale = lineHeightScale.toFloat(),
            letterSpacingEm = letterSpacingEm.toFloat(),
        )
    }

    private fun FontLoadGroup.summary(): String {
        val primaryText = primary?.summary() ?: "<null>"
        if (fallbacks.isEmpty()) return primaryText
        val preview = fallbacks.take(5).joinToString { it.summary() }
        val suffix = if (fallbacks.size > 5) "..." else ""
        return "$primaryText，fallbacks=${fallbacks.size}[$preview$suffix]"
    }

    private fun FontLoadResult.summary(): String {
        return "${typeface.familyName}(glyphs=${typeface.glyphsCount}, source=$source)"
    }

    private fun FontCandidate.toResult(): FontLoadResult = FontLoadResult(source, typeface)

    private data class FontCacheKey(
        val settings: DrawFontSettings,
        val dataFontsRoot: String,
        val dataFonts: List<DataFontSnapshot>,
    )

    private data class DataFontSnapshot(
        val path: String,
        val size: Long,
        val lastModifiedMillis: Long,
    )

    private data class FontLoadGroup(
        val primary: FontLoadResult?,
        val fallbacks: List<FontLoadResult>,
    )

    private data class FontLoadResult(
        val source: String,
        val typeface: Typeface,
    )

    private data class FontCandidate(
        val source: String,
        val key: String,
        val typeface: Typeface,
        val aliases: List<String>,
    )

    private val FONT_EXTENSIONS: Set<String> = setOf("ttf", "otf", "ttc", "otc")

    private val FONT_STYLE_SUFFIXES: Set<String> = setOf(
        "regular",
        "normal",
        "medium",
        "light",
        "thin",
        "bold",
        "semibold",
        "demibold",
        "demi",
        "black",
        "heavy",
        "italic",
        "oblique",
        "condensed",
        "variable",
        "vf",
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
