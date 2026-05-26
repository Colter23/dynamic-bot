package top.colter.dynamic.admin

import java.nio.charset.StandardCharsets

public object AdminStatic {
    private const val INDEX_RESOURCE: String = "admin/index.html"

    public val indexHtml: String by lazy {
        val loader = Thread.currentThread().contextClassLoader ?: AdminStatic::class.java.classLoader
        val stream = loader.getResourceAsStream(INDEX_RESOURCE)
            ?: error("Admin resource not found: $INDEX_RESOURCE")
        stream.use { input ->
            String(input.readBytes(), StandardCharsets.UTF_8)
        }
    }
}
