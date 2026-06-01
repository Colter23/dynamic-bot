package top.colter.dynamic.admin

import io.ktor.http.ContentType
import java.nio.charset.StandardCharsets

public data class AdminStaticResource(
    val bytes: ByteArray,
    val contentType: ContentType,
)

public object AdminStatic {
    private const val INDEX_RESOURCE: String = "admin/index.html"

    public val indexHtml: String by lazy {
        String(readResource(INDEX_RESOURCE), StandardCharsets.UTF_8)
    }

    public fun resource(path: String): AdminStaticResource {
        val normalized = normalizePath(path)
        return AdminStaticResource(
            bytes = readResource("admin/$normalized"),
            contentType = contentTypeFor(normalized),
        )
    }

    private fun readResource(path: String): ByteArray {
        val loader = Thread.currentThread().contextClassLoader ?: AdminStatic::class.java.classLoader
        val stream = loader.getResourceAsStream(path)
            ?: error("后台资源不存在：$path")
        return stream.use { input -> input.readBytes() }
    }

    private fun normalizePath(path: String): String {
        val normalized = path.replace('\\', '/').trim('/')
        require(normalized.isNotBlank()) { "后台资源路径不能为空" }
        val segments = normalized.split("/")
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "后台资源路径非法：$path" }
        return segments.joinToString("/")
    }

    private fun contentTypeFor(path: String): ContentType {
        return when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "html" -> ContentType.Text.Html
            "css" -> ContentType.Text.CSS
            "js",
            "mjs" -> ContentType.Text.JavaScript
            "json" -> ContentType.Application.Json
            "svg" -> ContentType.Image.SVG
            "png" -> ContentType.Image.PNG
            "jpg",
            "jpeg" -> ContentType.Image.JPEG
            else -> ContentType.Application.OctetStream
        }
    }
}
