package top.colter.dynamic.listener

import java.net.URI
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.forEachLazyImageFields
import top.colter.dynamic.draw.DynamicImageCache

public fun interface DynamicImageLoader {
    public fun load(dynamic: Dynamic)
}

public class UrlDynamicImageLoader : DynamicImageLoader {
    override fun load(dynamic: Dynamic) {
        forEachLazyImageFields(dynamic) { loadIfNeeded(this) }
    }

    private fun loadIfNeeded(image: LazyImage) {
        if (DynamicImageCache.contains(image) || image.uri.isBlank()) return
        val connection = URI(image.uri).toURL().openConnection()
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        DynamicImageCache.put(image, connection.getInputStream().use { it.readBytes() })
    }

    private companion object {
        private const val TIMEOUT_MS: Int = 10_000
    }
}
