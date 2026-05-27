package top.colter.dynamic.draw

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import top.colter.skiko.FontUtils

internal object DrawFontInitializer {
    @Volatile
    private var initialized: Boolean = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching {
                ensureDefaultFont()
            }.onFailure {
                System.err.println("dynamic draw font init failed: ${it::class.qualifiedName}: ${it.message}")
            }
            initialized = true
        }
    }

    private fun ensureDefaultFont() {
        if (FontUtils.defaultFont != null) return
        if (loadSystemFamily()) return
        loadSystemFontFile()
    }

    private fun loadSystemFamily(): Boolean {
        return DEFAULT_FONT_FAMILIES.any { family ->
            runCatching {
                val fontSet = FontUtils.matchFamily(family)
                if (fontSet.count() <= 0) return@runCatching false
                val typeface = fontSet.getTypeface(0) ?: return@runCatching false
                FontUtils.loadTypeface(typeface)
                true
            }.getOrDefault(false)
        }
    }

    private fun loadSystemFontFile(): Boolean {
        return DEFAULT_FONT_PATHS.any { path ->
            val normalized = path.toAbsolutePath().normalize()
            if (!Files.isRegularFile(normalized)) return@any false
            runCatching {
                FontUtils.loadTypeface(normalized.toString())
                FontUtils.defaultFont != null
            }.getOrDefault(false)
        }
    }

    private val DEFAULT_FONT_FAMILIES: List<String> = listOf(
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

    private val DEFAULT_FONT_PATHS: List<Path> = buildList {
        System.getenv("WINDIR")?.takeIf { it.isNotBlank() }?.let { windowsDir ->
            val fonts = Paths.get(windowsDir, "Fonts")
            add(fonts.resolve("msyh.ttc"))
            add(fonts.resolve("msyhbd.ttc"))
            add(fonts.resolve("simhei.ttf"))
            add(fonts.resolve("simsun.ttc"))
            add(fonts.resolve("seguiemj.ttf"))
        }
        add(Paths.get("/System/Library/Fonts/PingFang.ttc"))
        add(Paths.get("/System/Library/Fonts/STHeiti Light.ttc"))
        add(Paths.get("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"))
        add(Paths.get("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"))
        add(Paths.get("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
    }
}
