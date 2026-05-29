package top.colter.dynamic.admin

import java.nio.charset.StandardCharsets

public object AdminStatic {
    private const val INDEX_RESOURCE: String = "admin/index.html"

    public val indexHtml: String by lazy {
        val loader = Thread.currentThread().contextClassLoader ?: AdminStatic::class.java.classLoader
        val stream = loader.getResourceAsStream(INDEX_RESOURCE)
            ?: error("后台资源不存在：$INDEX_RESOURCE")
        stream.use { input ->
            String(input.readBytes(), StandardCharsets.UTF_8)
        }
    }
}
