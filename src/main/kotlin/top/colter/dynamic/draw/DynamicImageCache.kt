package top.colter.dynamic.draw

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.LazyImage

public object DynamicImageCache {
    private val images: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap()

    public fun contains(image: LazyImage): Boolean = images.containsKey(image.uri)

    public fun put(image: LazyImage, bytes: ByteArray) {
        images[image.uri] = bytes
    }

    public fun bytes(image: LazyImage): ByteArray {
        return images[image.uri] ?: error("image is not loaded: ${image.uri}")
    }
}
